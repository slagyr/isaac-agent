(ns isaac.recall.index
  "Per-crew retrieval index: <root>/episodes/<crew>/index.ednl.

   Rows {:episode-id :scene-id :kind :model :vector}. Derived data —
   always rebuildable from sealed scene .md files. Idempotent by
   (scene-id, kind, embedding-model)."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.recall.embedding :as embedding]
    [isaac.session.store.impl-common :as impl]))

(defn index-path [root crew]
  (str (store/crew-dir root crew) "/index.ednl"))

(defn- parse-ednl [s]
  (->> (str/split-lines (or s ""))
       (remove str/blank?)
       (mapv edn/read-string)))

(defn read-index
  "EDNL rows, or [] when the file is absent."
  [fs* root crew]
  (let [path (index-path root crew)]
    (if (fs/exists? fs* path)
      (parse-ednl (fs/slurp fs* path))
      [])))

(defn write-index!
  "Overwrite index.ednl with `rows` (one EDN map per line)."
  [fs* root crew rows]
  (let [path (index-path root crew)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (apply str (map impl/write-edn rows)))))

(defn row-key [row]
  [(:scene-id row) (:kind row) (:model row)])

(defn- configured-model [cfg]
  (get-in cfg [:embedding :model]))

(defn- scene-payloads [scene]
  [{:kind :gist :text (or (:gist scene) "")}
   {:kind :text :text (or (:text scene) "")}])

(defn- list-closed-scenes
  "All sealed scenes across closed/partial episodes for a crew.
   Returns [{:episode-id :scene ...}]."
  [fs* root crew]
  (mapcat (fn [ep]
            (let [eid (:id ep)]
              (map (fn [scene]
                     {:episode-id eid :scene scene})
                   (store/list-scenes fs* root crew eid))))
          (store/list-episodes fs* root crew)))

(defn- embed-or-error [cfg texts]
  (let [result (embedding/embed-texts cfg texts)]
    (if (:error result)
      result
      (:vectors result))))

(defn index-crew!
  "Embed sealed scenes into the per-crew index.

   Returns {:new N} on success, or {:error :no-embedding :message ...}.
   `--rebuild` (`:rebuild? true`) drops existing rows first."
  [fs* root crew cfg {:keys [rebuild?]}]
  (let [embedder-result (embedding/embed-texts cfg [""])]
    (if (:error embedder-result)
      embedder-result
      (let [model    (or (configured-model cfg) "")
            existing (if rebuild? [] (read-index fs* root crew))
            keyed    (into {} (map (juxt row-key identity) existing))
            pairs    (list-closed-scenes fs* root crew)
            needed   (for [{:keys [episode-id scene]} pairs
                           {:keys [kind text]} (scene-payloads scene)
                           :let [k [(:id scene) kind model]]
                           :when (not (contains? keyed k))]
                       {:episode-id episode-id
                        :scene-id   (:id scene)
                        :kind       kind
                        :model      model
                        :text       text})
            texts    (mapv :text needed)
            vectors  (when (seq texts) (embed-or-error cfg texts))]
        (if (and (map? vectors) (:error vectors))
          vectors
          (let [fresh (mapv (fn [row v]
                              (-> row
                                  (dissoc :text)
                                  (assoc :vector v)))
                            needed
                            (or vectors []))
                kept  (if rebuild? [] existing)
                rows  (vec (concat kept fresh))]
            (when (or rebuild? (seq fresh) (seq existing))
              (write-index! fs* root crew rows))
            {:new (count fresh)
             :rows rows}))))))
