(ns isaac.episodes.store
  "Filesystem layout for closed episodes:
     <root>/episodes/<crew>/<episode-id>/episode.edn
     <root>/episodes/<crew>/<episode-id>/<scene-id>.md

   Scenes are markdown with YAML frontmatter (structure) + distilled text body.
   episode.edn stays EDN (all structure, no prose).

   Frontmatter split/parse matches isaac.config's md-with-frontmatter
   component (same regex + clj-yaml)."
  (:require
    [clj-yaml.core :as yaml]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.session.store.impl-common :as impl]))

(def ^:private SCENE_FRONTMATTER_KEYS
  [:id :start-id :end-id :started-at :ended-at :seal-reason :gist])

(defn episodes-root [root]
  (str root "/episodes"))

(defn crew-dir [root crew]
  (str (episodes-root root) "/" (name crew)))

(defn episode-path [root crew episode-id]
  (str (crew-dir root crew) "/" episode-id))

(defn- episode-edn-path [root crew episode-id]
  (str (episode-path root crew episode-id) "/episode.edn"))

(defn- scene-md-path [root crew episode-id scene-id]
  (str (episode-path root crew episode-id) "/" scene-id ".md"))

(defn- write-edn! [fs* path value]
  (fs/mkdirs fs* (fs/parent path))
  (fs/spit fs* path (impl/write-edn value)))

(defn- read-edn [fs* path]
  (when (fs/exists? fs* path)
    (edn/read-string (fs/slurp fs* path))))

(defn- yaml-scalar [value]
  (cond
    (keyword? value) (name value)
    (string? value)  value
    (number? value)  value
    (true? value)    true
    (false? value)   false
    (nil? value)     nil
    :else            (str value)))

(defn- scene->frontmatter [scene]
  (into (array-map)
        (keep (fn [k]
                (when-let [v (get scene k)]
                  [(name k) (yaml-scalar v)]))
              SCENE_FRONTMATTER_KEYS)))

(defn- format-scene-md [scene]
  (let [fm   (scene->frontmatter scene)
        body (or (:text scene) "")]
    (str "---\n"
         (yaml/generate-string fm :dumper-options {:flow-style :block})
         "---\n"
         (when-not (str/blank? body)
           (str "\n" body
                (when-not (str/ends-with? body "\n") "\n"))))))

(defn- write-scene-md! [fs* path scene]
  (fs/mkdirs fs* (fs/parent path))
  (fs/spit fs* path (format-scene-md scene)))

;; Same split regex as isaac.config.loader / isaac.config.parse (md frontmatter).
(defn- split-frontmatter [content]
  (when-let [[_ frontmatter body]
             (re-matches #"(?s)\A---\r?\n(.*?)\r?\n---\r?\n?(.*)\z" content)]
    {:frontmatter frontmatter
     :body        (str/replace body #"^\r?\n" "")}))

(defn- keywordize-seal-reason [v]
  (cond
    (keyword? v) v
    (string? v)  (keyword v)
    :else        v))

(defn- parse-scene-md [content]
  (when-let [{:keys [frontmatter body]} (split-frontmatter content)]
    (let [data (yaml/parse-string frontmatter :keywords true)
          ;; File convention adds a trailing newline; strip it so :text
          ;; round-trips to the sealed scene value.
          text (str/replace (or body "") #"\r?\n\z" "")]
      (cond-> (select-keys data SCENE_FRONTMATTER_KEYS)
        (:seal-reason data)
        (update :seal-reason keywordize-seal-reason)
        true
        (assoc :text text)))))

(defn- list-dir-names [fs* dir]
  (if (fs/exists? fs* dir)
    (->> (or (fs/children fs* dir) [])
         (remove #(str/starts-with? % "."))
         sort
         vec)
    []))

(defn- scene-file?
  "True for scene payloads — .md preferred; legacy .edn still recognized for cleanup."
  [name]
  (and (or (str/ends-with? name ".md")
           (str/ends-with? name ".edn"))
       (not= name "episode.edn")))

(defn write-episode!
  "Persist episode record + scene markdown files. When `:replace-scenes?` is true,
   deletes prior scene files (`.md` and legacy `.edn`; keeps episode.edn until rewrite)."
  ([fs* root episode scenes]
   (write-episode! fs* root episode scenes {}))
  ([fs* root episode scenes {:keys [replace-scenes?]}]
   (let [crew (:crew episode)
         id   (:id episode)
         dir  (episode-path root crew id)]
     (fs/mkdirs fs* dir)
     (when replace-scenes?
       (doseq [name (list-dir-names fs* dir)
               :when (scene-file? name)]
         (fs/delete fs* (str dir "/" name))))
     (write-edn! fs* (episode-edn-path root crew id) episode)
     (doseq [scene scenes]
       (write-scene-md! fs* (scene-md-path root crew id (:id scene)) scene))
     episode)))

(defn read-episode
  "Read episode.edn for crew/id, or nil."
  [fs* root crew episode-id]
  (read-edn fs* (episode-edn-path root crew episode-id)))

(defn read-scene
  "Read a scene markdown file (YAML frontmatter + body as :text)."
  [fs* root crew episode-id scene-id]
  (let [path (scene-md-path root crew episode-id scene-id)]
    (when (fs/exists? fs* path)
      (parse-scene-md (fs/slurp fs* path)))))

(defn list-scene-ids
  "Scene file basenames (sans .md), sorted — chronological when ids are timestamped."
  [fs* root crew episode-id]
  (->> (list-dir-names fs* (episode-path root crew episode-id))
       (filter #(str/ends-with? % ".md"))
       (map #(subs % 0 (- (count %) 3)))
       sort
       vec))

(defn list-scenes
  "Scenes in episode record order when :scene-ids is present; otherwise
   sorted by :started-at then id (timestamped ids alone are not chrono-stable
   within the same minute because of the chaos suffix)."
  [fs* root crew episode-id]
  (let [ep (read-episode fs* root crew episode-id)
        ids (or (not-empty (:scene-ids ep))
                (list-scene-ids fs* root crew episode-id))
        scenes (mapv #(read-scene fs* root crew episode-id %) ids)]
    (if (seq (:scene-ids ep))
      scenes
      (->> scenes
           (sort-by (fn [s] [(or (:started-at s) "") (:id s)]))
           vec))))

(defn list-episodes
  "All episode records under a crew, sorted by id."
  [fs* root crew]
  (->> (list-dir-names fs* (crew-dir root crew))
       (keep (fn [id]
               (when-let [ep (read-episode fs* root crew id)]
                 (assoc ep :id (or (:id ep) id)))))
       vec))

(defn find-by-migrated-from
  "Return the episode whose :migrated-from equals session-id, if any."
  [fs* root crew session-id]
  (some (fn [ep]
          (when (= session-id (or (:migrated-from ep) (:migrated_from ep)))
            ep))
        (list-episodes fs* root crew)))

(defn find-migrated-anywhere
  "Scan all crews for a migrated-from match. Returns [crew episode] or nil."
  [fs* root session-id]
  (some (fn [crew]
          (when-let [ep (find-by-migrated-from fs* root crew session-id)]
            [crew ep]))
        (list-dir-names fs* (episodes-root root))))
