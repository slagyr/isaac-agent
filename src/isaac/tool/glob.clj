;; mutation-tested: 2026-05-06
(ns isaac.tool.glob
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [isaac.tool.fs-bounds :as bounds])
  (:import
    [java.io File]
    [java.nio.file FileSystems Files PathMatcher]))

(def ^:dynamic *default-head-limit* 100)

(def ^:dynamic *skip-dirs*
  "Directory names pruned from the walk unless the caller's pattern or path
   names one explicitly. They hold vendored or machine-generated trees that
   swamp a scan without ever being what the caller asked for."
  #{".git" ".gitlibs" ".m2" ".cpcache" "node_modules" "target" "Library"})

(def ^:dynamic *scan-entry-budget*
  "Maximum number of filesystem entries one glob call may visit."
  20000)

(def ^:dynamic *scan-millis-budget*
  "Wall-clock ceiling, in milliseconds, for one glob scan."
  5000)

(defn- glob-root [args]
  (let [path        (get args "path")
        session-cwd (bounds/session-workdir args)]
    (or (bounds/resolve-path path session-cwd)
        (bounds/root args)
        (System/getProperty "user.dir"))))

(defn- path-segments [s]
  (set (remove str/blank? (str/split (str s) #"[/\\]"))))

(defn- pruned-dirs
  "The default skip list minus any directory the caller named in the pattern
   or the search path — an explicit mention always wins over the default."
  [pattern root]
  (let [named (into (path-segments root) (path-segments pattern))]
    (into #{} (remove named *skip-dirs*))))

(defn- matches-pattern? [^PathMatcher matcher display]
  (.matches matcher (.getPath (FileSystems/getDefault) display (make-array String 0))))

(defn- child-display [prefix ^File child]
  (if (str/blank? prefix)
    (.getName child)
    (str prefix "/" (.getName child))))

(defn- children [^File dir prefix]
  (mapv (fn [^File child] [child (child-display prefix child)])
        (or (.listFiles dir) [])))

(defn- descendable?
  "Directories are walked, but symlinked directories are not — the walk must
   not loop through a link back into its own parent."
  [^File file]
  (and (.isDirectory file)
       (not (Files/isSymbolicLink (.toPath file)))))

(defn- file-match [^File file display ^PathMatcher matcher]
  (when (and (.isFile file) (matches-pattern? matcher display))
    {:display display :mtime (.lastModified file)}))

(defn- exhausted? [entries deadline]
  (or (>= (long entries) (long *scan-entry-budget*))
      (>= (System/currentTimeMillis) (long deadline))))

(defn- scan-tree
  "Breadth-first walk that prunes `skips`, counts every entry it visits, and
   stops early when either scan budget runs out. Breadth-first so that a scan
   cut short by the budget has still covered the shallowest files."
  [^File root ^PathMatcher matcher skips]
  (let [deadline (+ (System/currentTimeMillis) (long *scan-millis-budget*))]
    (loop [queue   (conj clojure.lang.PersistentQueue/EMPTY [root ""])
           entries 0
           matches []]
      (cond
        (empty? queue)
        {:matches matches :entries entries :stopped? false}

        (exhausted? entries deadline)
        {:matches matches :entries entries :stopped? true}

        :else
        (let [[^File file display] (peek queue)
              queue                (pop queue)]
          (cond
            (and (seq display) (.isDirectory file) (contains? skips (.getName file)))
            (recur queue entries matches)

            (descendable? file)
            (recur (into queue (children file display)) (inc entries) matches)

            :else
            (recur queue (inc entries)
                   (if-let [match (file-match file display matcher)]
                     (conj matches match)
                     matches))))))))

(defn- scan-file [^File root ^PathMatcher matcher]
  {:matches  (vec (keep identity [(file-match root (.getName root) matcher)]))
   :entries  1
   :stopped? false})

(defn- glob-scan [root pattern]
  (let [file    (io/file root)
        matcher (.getPathMatcher (FileSystems/getDefault) (str "glob:" pattern))
        scan    (if (.isFile file)
                  (scan-file file matcher)
                  (scan-tree file matcher (pruned-dirs pattern root)))]
    (update scan :matches #(sort-by (juxt (comp - :mtime) :display) %))))

(defn- glob-result [{:keys [matches entries stopped?]} head-limit]
  (let [total      (count matches)
        truncated? (and (pos? head-limit) (> total head-limit))
        shown      (if (pos? head-limit) (take head-limit matches) matches)
        lines      (mapv :display shown)
        lines      (if (or (seq lines) stopped?) lines ["no matches"])
        lines      (cond-> lines
                     truncated? (conj (str "Results truncated. " total " total matches."))
                     stopped?   (conj (str "Stopped at the scan budget after " entries
                                           " entries. Results are partial.")))]
    {:result (str/join "\n" lines)}))

(defn glob-tool
  "List files matching a shell-style glob pattern.
   Args: pattern, path, head_limit."
  [args]
  (let [args       (bounds/string-key-map args)
        pattern    (get args "pattern")
        head-limit (bounds/arg-int args "head_limit" nil)
        root       (glob-root args)]
    (or (bounds/ensure-path-allowed args root)
        (glob-result (glob-scan root pattern) (or head-limit *default-head-limit*)))))
