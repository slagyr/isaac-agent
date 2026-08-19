(ns isaac.recall.index
  "Per-crew retrieval index: <root>/episodes/<crew>/index.edn + vectors.bin.

   Metadata {:dims :model :rows [{:episode-id :scene-id :kind :model} ...]}.
   Row order = blob order. Vectors are unit-normalized float32 LE.
   Legacy index.ednl is ignored. Derived data — always rebuildable."
  (:require
    [clojure.edn :as edn]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.recall.embedding :as embedding]
    [isaac.recall.score :as score]
    [isaac.session.store.impl-common :as impl])
  (:import
    (java.nio ByteBuffer ByteOrder)
    (java.nio.file Files OpenOption Paths)))

(defn index-path [root crew]
  (str (store/crew-dir root crew) "/index.edn"))

(defn vectors-path [root crew]
  (str (store/crew-dir root crew) "/vectors.bin"))

(defn- real-fs? [fs*]
  (instance? isaac.fs.RealFs fs*))

(defn- write-blob! [fs* path ^bytes ba]
  (fs/mkdirs fs* (or (fs/parent path) "/"))
  (if (real-fs? fs*)
    (Files/write (Paths/get path (into-array String []))
                 ba
                 (into-array OpenOption []))
    (fs/spit fs* path ba)))

(defn- read-blob [fs* path]
  (when (fs/exists? fs* path)
    (if (real-fs? fs*)
      (Files/readAllBytes (Paths/get path (into-array String [])))
      (let [c (fs/slurp fs* path)]
        (if (bytes? c) c (byte-array 0))))))

(defn vectors-bytes
  "Raw vectors.bin contents, or empty byte-array when absent."
  [fs* root crew]
  (or (read-blob fs* (vectors-path root crew)) (byte-array 0)))

(defn- floats->bytes [float-rows]
  (let [n (reduce + 0 (map count float-rows))
        bb (ByteBuffer/allocate (* n 4))]
    (.order bb ByteOrder/LITTLE_ENDIAN)
    (doseq [xs float-rows]
      (dotimes [i (count xs)]
        (.putFloat bb (float (nth xs i)))))
    (.array bb)))

(defn- bytes->floats
  "Decode `n` little-endian float32 values starting at `offset` bytes."
  [^bytes ba offset n]
  (let [bb (ByteBuffer/wrap ba)
        _  (.order bb ByteOrder/LITTLE_ENDIAN)
        _  (.position bb (int offset))
        xs (float-array n)]
    (dotimes [i n]
      (aset xs i (.getFloat bb)))
    xs))

(defn- ->floats [v]
  (if (score/float-array? v)
    v
    (score/normalize-vector v)))

(defn write-index!
  "Overwrite packed index.edn + vectors.bin. Vectors unit-normalized at write."
  [fs* root crew rows]
  (let [normalized (mapv (fn [row]
                           (assoc row :vector (->floats (:vector row))))
                         rows)
        dims       (if (seq normalized)
                     (count (:vector (first normalized)))
                     0)
        model      (or (:model (last normalized)) "")
        meta       {:dims  dims
                    :model model
                    :rows  (mapv #(select-keys % [:episode-id :scene-id :kind :model])
                                 normalized)}
        blob       (floats->bytes (mapv :vector normalized))
        meta-path  (index-path root crew)
        vec-path   (vectors-path root crew)]
    (fs/mkdirs fs* (or (fs/parent meta-path) "/"))
    (fs/spit fs* meta-path (impl/write-edn meta))
    (write-blob! fs* vec-path blob)
    normalized))

(defn read-index
  "Packed rows with :vector as a primitive float array, or [] when absent.
   Legacy index.ednl is ignored."
  [fs* root crew]
  (let [path (index-path root crew)]
    (if-not (fs/exists? fs* path)
      []
      (let [meta (edn/read-string (or (fs/slurp fs* path) "{}"))
            dims (int (or (:dims meta) 0))
            blob (vectors-bytes fs* root crew)
            rows (vec (or (:rows meta) []))
            blob-n (count blob)]
        (mapv (fn [row i]
                (let [offset (* i dims 4)
                      vec*   (if (and (pos? dims)
                                      (<= (+ offset (* dims 4)) blob-n))
                               (bytes->floats blob offset dims)
                               (float-array 0))]
                  (assoc row :vector vec*)))
              rows
              (range (count rows)))))))

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

(def ^:private EMBED_BATCH 64)

(defn- embed-batched
  "Embed texts in EMBED_BATCH chunks — one corpus-sized request would blow
   the embedder's HTTP timeout. Prints progress when more than one batch.
   Returns a vector of vectors, or the error map from the failing batch."
  [cfg texts]
  (let [total (count texts)
        verbose? (> total EMBED_BATCH)]
    (loop [remaining texts
           acc []]
      (if (empty? remaining)
        acc
        (let [batch  (vec (take EMBED_BATCH remaining))
              result (embedding/embed-texts cfg batch)]
          (if (:error result)
            result
            (let [acc (into acc (:vectors result))]
              (when verbose?
                (println (str "  embedded " (count acc) "/" total)))
              (recur (drop EMBED_BATCH remaining) acc))))))))

(defn index-crew!
  "Embed sealed scenes into the per-crew packed index.

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
            vectors  (when (seq texts) (embed-batched cfg texts))]
        (if (and (map? vectors) (:error vectors))
          vectors
          (let [fresh (mapv (fn [row v]
                              (-> row
                                  (dissoc :text)
                                  (assoc :vector (score/normalize-vector v))))
                            needed
                            (or vectors []))
                kept  (if rebuild? [] existing)
                rows  (vec (concat kept fresh))]
            (when (or rebuild? (seq fresh) (seq existing))
              (write-index! fs* root crew rows))
            {:new (count fresh)
             :rows rows}))))))
