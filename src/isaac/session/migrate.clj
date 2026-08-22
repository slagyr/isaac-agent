(ns isaac.session.migrate
  "One-shot conversion of flat jsonl+sidecar/index sessions into
   sessions/<id>/{session.edn,current.ednl,n.ednl}."
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.session.store.impl-common :as c]))

(defn- parse-jsonl [fs path]
  (when (c/exists?* fs path)
    (->> (str/split-lines (or (c/slurp* fs path) ""))
         (remove str/blank?)
         (mapv #(json/parse-string % true)))))

(defn split-on-compaction
  "Partition a flat transcript on compaction entries.
   Returns [frozen-segments current] where each frozen segment is a vector
   of entries and current is the live tail (after the last compaction, or
   the whole file when none)."
  [entries]
  (let [parts (reduce (fn [acc e]
                        (if (= "compaction" (:type e))
                          (conj acc [e])
                          (update acc (dec (count acc)) (fnil conj []) e)))
                      [[]]
                      entries)
        parts (vec (remove empty? parts))]
    (if (empty? parts)
      [[] []]
      [(vec (butlast parts)) (peek parts)])))

(defn- read-flat-entry [root id fs]
  (let [sidecar (c/flat-sidecar-path root id)
        index   (c/index-path root)]
    (or (when (c/exists?* fs sidecar)
          (let [raw (edn/read-string (c/slurp* fs sidecar))]
            (when (map? raw) (c/keywordize-map raw))))
        (when (c/exists?* fs index)
          (let [store (edn/read-string (c/slurp* fs index))
                entry (when (map? store)
                        (or (get store id) (get store (keyword id))))]
            (when (map? entry) (c/keywordize-map entry)))))))

(defn leftover-ids [root fs]
  (let [dir (c/sessions-dir root)
        names (or (c/children* fs dir) [])
        jsonl (->> names
                   (filter #(str/ends-with? % ".jsonl"))
                   (map #(subs % 0 (- (count %) 6))))
        sidecars (->> names
                      (filter #(and (str/ends-with? % ".edn") (not= % "index.edn")))
                      (map #(subs % 0 (- (count %) 4))))
        index-ids (when (c/exists?* fs (c/index-path root))
                    (let [raw (edn/read-string (c/slurp* fs (c/index-path root)))]
                      (when (map? raw)
                        (map (fn [k] (if (keyword? k) (name k) (str k))) (keys raw)))))]
    (->> (concat jsonl sidecars index-ids)
         (remove str/blank?)
         distinct
         sort
         vec)))

(defn- already-migrated? [root id fs]
  (c/exists?* fs (c/current-transcript-path root id)))

(defn- write-migrated! [root id entry frozen current fs]
  (c/mkdirs*! fs (c/session-dir root id))
  (doseq [[n segment] (map-indexed vector frozen)]
    (c/write-ednl! fs (c/frozen-transcript-path root id n) segment))
  (c/write-ednl! fs (c/current-transcript-path root id) current)
  (let [cleaned (-> entry
                    (assoc :id id :key (or (:key entry) id))
                    (dissoc :session-file :effective-history-offset)
                    (assoc :segment (count frozen)))]
    (c/spit*! fs (c/session-edn-path root id) (c/write-edn cleaned)))
  (let [legacy-turn (c/legacy-turn-marker-path root id)
        turn        (c/turn-marker-path root id)]
    (when (and (c/exists?* fs legacy-turn) (not (c/exists?* fs turn)))
      (c/mkdirs*! fs (c/session-dir root id))
      (fs/move fs legacy-turn turn)))
  (when (c/exists?* fs (c/flat-jsonl-path root id))
    (c/delete*! fs (c/flat-jsonl-path root id)))
  (when (c/exists?* fs (c/flat-sidecar-path root id))
    (c/delete*! fs (c/flat-sidecar-path root id))))

(defn- dissoc-index-id! [root id fs]
  (let [path (c/index-path root)]
    (when (c/exists?* fs path)
      (let [raw (edn/read-string (c/slurp* fs path))]
        (when (map? raw)
          (let [next (dissoc raw id (keyword id))]
            (if (seq next)
              (c/spit*! fs path (c/write-edn next))
              (c/delete*! fs path))))))))

(defn migrate-session!
  "Migrate one leftover flat session. Returns
   {:status :migrated|:skipped|:missing :id id}."
  [root id fs]
  (let [id (c/session-id id)]
    (cond
      (already-migrated? root id fs)
      {:status :skipped :id id}

      (not (or (c/exists?* fs (c/flat-jsonl-path root id))
               (c/exists?* fs (c/flat-sidecar-path root id))
               (read-flat-entry root id fs)))
      {:status :missing :id id}

      :else
      (let [entries (or (parse-jsonl fs (c/flat-jsonl-path root id)) [])
            [frozen current] (split-on-compaction entries)
            entry (or (read-flat-entry root id fs)
                      {:id id :key id :name id})]
        (write-migrated! root id entry frozen current fs)
        (dissoc-index-id! root id fs)
        {:status :migrated :id id}))))

(defn migrate-all!
  "Migrate every leftover flat session. Returns a seq of result maps."
  [root fs]
  (mapv #(migrate-session! root % fs) (leftover-ids root fs)))
