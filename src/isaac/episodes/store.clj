(ns isaac.episodes.store
  "Filesystem layout for closed episodes:
     <root>/episodes/<crew>/<episode-id>/episode.edn
     <root>/episodes/<crew>/<episode-id>/<scene-id>.edn"
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.session.store.impl-common :as impl]))

(defn episodes-root [root]
  (str root "/episodes"))

(defn crew-dir [root crew]
  (str (episodes-root root) "/" (name crew)))

(defn episode-path [root crew episode-id]
  (str (crew-dir root crew) "/" episode-id))

(defn- episode-edn-path [root crew episode-id]
  (str (episode-path root crew episode-id) "/episode.edn"))

(defn- scene-edn-path [root crew episode-id scene-id]
  (str (episode-path root crew episode-id) "/" scene-id ".edn"))

(defn- write-edn! [fs* path value]
  (fs/mkdirs fs* (fs/parent path))
  (fs/spit fs* path (impl/write-edn value)))

(defn- read-edn [fs* path]
  (when (fs/exists? fs* path)
    (edn/read-string (fs/slurp fs* path))))

(defn- list-dir-names [fs* dir]
  (if (fs/exists? fs* dir)
    (->> (or (fs/children fs* dir) [])
         (remove #(str/starts-with? % "."))
         sort
         vec)
    []))

(defn write-episode!
  "Persist episode record + scene files. When `:replace-scenes?` is true,
   deletes prior `*.edn` scene files (keeps episode.edn until rewrite)."
  ([fs* root episode scenes]
   (write-episode! fs* root episode scenes {}))
  ([fs* root episode scenes {:keys [replace-scenes?]}]
   (let [crew (:crew episode)
         id   (:id episode)
         dir  (episode-path root crew id)]
     (fs/mkdirs fs* dir)
     (when replace-scenes?
       (doseq [name (list-dir-names fs* dir)
               :when (and (str/ends-with? name ".edn")
                          (not= name "episode.edn"))]
         (fs/delete fs* (str dir "/" name))))
     (write-edn! fs* (episode-edn-path root crew id) episode)
     (doseq [scene scenes]
       (write-edn! fs* (scene-edn-path root crew id (:id scene)) scene))
     episode)))

(defn read-episode
  "Read episode.edn for crew/id, or nil."
  [fs* root crew episode-id]
  (read-edn fs* (episode-edn-path root crew episode-id)))

(defn read-scene
  [fs* root crew episode-id scene-id]
  (read-edn fs* (scene-edn-path root crew episode-id scene-id)))

(defn list-scene-ids
  "Scene file basenames (sans .edn), sorted — chronological when ids are timestamped."
  [fs* root crew episode-id]
  (->> (list-dir-names fs* (episode-path root crew episode-id))
       (filter #(and (str/ends-with? % ".edn") (not= % "episode.edn")))
       (map #(subs % 0 (- (count %) 4)))
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
