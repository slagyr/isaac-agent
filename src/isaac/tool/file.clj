;; mutation-tested: 2026-05-06
(ns isaac.tool.file
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.tool.fs-bounds :as bounds])
  (:import (java.util.regex Pattern)))

(def ^:dynamic *default-read-limit* 2000)
(def ^:private binary-check-window 8192)

(defn- binary-content? [^String content]
  (let [len (min (count content) binary-check-window)]
    (loop [i 0]
      (cond
        (>= i len) false
        (= \u0000 (.charAt content i)) true
        :else (recur (inc i))))))

(defn- format-file-content [file-path content offset limit]
  (cond
    (binary-content? content)
    {:isError true :error (str "binary file: " file-path)}

    (= "" content)
    {:result "<empty file>"}

    :else
    (let [all-lines (str/split-lines content)
          total     (count all-lines)
          start     (if offset (max 0 (dec offset)) 0)
          effective (or limit *default-read-limit*)
          end       (min total (+ start effective))
          selected  (subvec (vec all-lines) start end)
          numbered  (map-indexed (fn [i line] (str (+ start i 1) ": " line)) selected)
          lines     (cond-> (vec numbered)
                            (< end total)
                            (conj (str "... (truncated: showing " (count selected)
                                       " of " total " lines)")))]
      {:result (str/join "\n" lines)})))

(defn read-tool
  "Read file contents or list a directory.
   Args: file_path, offset, limit."
  [args]
  (let [args        (bounds/string-key-map args)
        fs*         (bounds/filesystem args)
        session-cwd (bounds/session-workdir args)
        file-path   (bounds/resolve-path (get args "file_path") session-cwd)
        offset      (bounds/arg-int args "offset" nil)
        limit       (bounds/arg-int args "limit" nil)]
    (or (bounds/ensure-path-allowed args file-path)
        (cond
          (not (fs/exists? fs* file-path))
          {:isError true :error (str "not found: " file-path)}

          (when-let [entries (fs/children fs* file-path)]
            (seq entries))
          {:result (str/join "\n" (sort (fs/children fs* file-path)))}

          :else
          (format-file-content file-path (or (fs/slurp fs* file-path) "") offset limit)))))

(defn write-tool
  "Write content to a file, creating parent directories as needed.
   Args: file_path, content."
  [args]
  (let [args        (bounds/string-key-map args)
        fs*         (bounds/filesystem args)
        session-cwd (bounds/session-workdir args)
        file-path   (bounds/resolve-path (get args "file_path") session-cwd)
        content     (get args "content")]
    (or (bounds/ensure-path-allowed args file-path)
        (try
          (fs/mkdirs fs* (fs/parent file-path))
          (fs/spit fs* file-path content)
          {:result (str "wrote " file-path)}
          (catch Exception e
            {:isError true :error (.getMessage e)})))))

(defn- match-count [content old-string]
  (-> old-string Pattern/quote Pattern/compile (re-seq content) count))

(defn- validate-replacement [content old-string replace-all]
  (let [count (match-count content old-string)]
    (cond
      (= 0 count) {:error (str "not found: " old-string)}
      (and (> count 1) (not replace-all)) {:error (str "multiple matches for: " old-string)}
      :else {:replacements (if replace-all count 1)})))

(defn- apply-replacement [content old-string new-string replace-all]
  (str/replace content old-string new-string))

(defn- single-edit-entry
  "Validate and apply one edit against current file content. Returns {:content ... :replacements n}
   or {:error ...}."
  [content {:keys [old_string new_string replace_all]}]
  (or (when (some? content)
        (let [replace-all (boolean replace_all)
              validation  (validate-replacement content old_string replace-all)]
          (if (:error validation)
            validation
            {:content      (apply-replacement content old_string new_string replace-all)
             :replacements (:replacements validation)})))
      {:error "missing file content"}))

(defn- resolve-edit-file-path [args session-cwd raw-path]
  (let [via-session (bounds/resolve-path raw-path session-cwd)]
    (cond
      (and via-session (.isAbsolute (io/file via-session))) via-session
      (and (bounds/root args) (seq (str raw-path)))
      (.getCanonicalPath (io/file (bounds/root args) raw-path))
      :else via-session)))

(defn- edit-entry-at-index [index entry content]
  (let [entry  (bounds/string-key-map entry)
        result (single-edit-entry content {:old_string  (get entry "old_string")
                                           :new_string  (get entry "new_string")
                                           :replace_all (bounds/arg-bool entry "replace_all" false)})]
    (if (:error result)
      {:error (str "edit entry " (inc index) ": " (:error result))}
      result)))

(defn edit-tool
  "Replace text in a file.
   Args: file_path, old_string, new_string, replace_all."
  [args]
  (let [args        (bounds/string-key-map args)
        fs*         (bounds/filesystem args)
        session-cwd (bounds/session-workdir args)
        file-path   (bounds/resolve-path (get args "file_path") session-cwd)
        old-string  (get args "old_string")
        new-string  (get args "new_string")
        replace-all (bounds/arg-bool args "replace_all" false)]
    (or (bounds/ensure-path-allowed args file-path)
        (if-not (fs/exists? fs* file-path)
          {:isError true :error (str "not found: " file-path)}
          (let [content (or (fs/slurp fs* file-path) "")
                result  (single-edit-entry content {:old_string  old-string
                                                    :new_string  new-string
                                                    :replace_all replace-all})]
            (if (:error result)
              {:isError true :error (:error result)}
              (do
                (fs/spit fs* file-path (:content result))
                {:result (str "edited " file-path)})))))))

(defn multi-edit-tool
  "Apply N validated string replacements atomically.
   Args: edits — vector of maps with file_path, old_string, new_string, replace_all (optional)."
  [args]
  (let [args        (bounds/string-key-map args)
        fs*         (bounds/filesystem args)
        session-cwd (bounds/session-workdir args)
        edits       (vec (or (get args "edits") []))]
    (if (empty? edits)
      {:isError true :error "edits must be a non-empty array"}
      (loop [i 0 file-contents {} summaries []]
        (if (= i (count edits))
          (try
            (doseq [[path content] file-contents]
              (fs/spit fs* path content))
            {:result (str/join "\n" summaries)}
            (catch Exception e
              {:isError true :error (.getMessage e)}))
          (let [entry     (bounds/string-key-map (nth edits i))
                file-path (resolve-edit-file-path args session-cwd (get entry "file_path"))]
            (or (bounds/ensure-path-allowed args file-path)
                (if-not (fs/exists? fs* file-path)
                  {:isError true :error (str "edit entry " (inc i) ": not found: " file-path)}
                  (let [content (or (get file-contents file-path)
                                    (fs/slurp fs* file-path)
                                    "")
                        result  (edit-entry-at-index i entry content)]
                    (if (:error result)
                      {:isError true :error (:error result)}
                      (recur (inc i)
                             (assoc file-contents file-path (:content result))
                             (conj summaries (str file-path ": "
                                                  (:replacements result)
                                                  " replacement(s)")))))))))))
))
