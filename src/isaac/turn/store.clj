(ns isaac.turn.store
  "Durable turn records. The port serializes acceptance and claims per store."
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [isaac.fs :as fs]))

(defprotocol TurnStore
  (submit! [store record])
  (read-turn [store id])
  (list-turns [store])
  (update-turn! [store id attrs])
  (claim! [store id]))

(defn- write-edn [record]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint record))))

(defn- path [root id] (str root "/turns/" id ".edn"))

(defonce ^:private locks* (atom {}))

(defn- lock-for [root]
  (get (swap! locks* update root #(or % (Object.))) root))

(deftype FileStore [fs root lock]
  TurnStore
  (read-turn [_ id]
    (when-let [s (fs/slurp fs (path root id))]
      (let [record (edn/read-string s)]
        (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword k)) v]) record)))))
  (list-turns [this]
    (->> (fs/children fs (str root "/turns"))
         (filter #(str/ends-with? % ".edn"))
         (keep #(read-turn this (subs % 0 (- (count %) 4))))
         (sort-by :created-at)
         vec))
  (submit! [this record]
    (locking lock
      (if-let [existing (when-let [key (:key record)]
                          (first (filter #(= key (:key %)) (list-turns this))))]
        (assoc existing :already-accepted? true)
        (do
          (fs/mkdirs fs (str root "/turns"))
          (fs/spit fs (path root (:id record)) (write-edn record))
          record))))
  (update-turn! [this id attrs]
    (locking lock
      (when-let [record (read-turn this id)]
        (let [updated (merge record attrs)]
          (fs/spit fs (path root id) (write-edn updated))
          updated))))
  (claim! [this id]
    (locking lock
      (when (contains? #{:queued :held :waiting-session} (:state (read-turn this id)))
        (update-turn! this id {:state :running})))))

(defn file-store [fs root]
  (->FileStore fs root (lock-for root)))

(deftype MemoryStore [state]
  TurnStore
  (read-turn [_ id] (get @state id))
  (list-turns [_] (->> (vals @state) (sort-by :created-at) vec))
  (submit! [this record]
    (locking state
      (if-let [existing (when-let [key (:key record)]
                          (first (filter #(= key (:key %)) (list-turns this))))]
        (assoc existing :already-accepted? true)
        (do (swap! state assoc (:id record) record) record))))
  (update-turn! [this id attrs]
    (locking state
      (when (read-turn this id)
        (swap! state update id merge attrs)
        (read-turn this id))))
  (claim! [this id]
    (locking state
      (when (contains? #{:queued :held :waiting-session} (:state (read-turn this id)))
        (update-turn! this id {:state :running})))))

(defn memory-store [] (->MemoryStore (atom {})))
