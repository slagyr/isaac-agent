(ns isaac.turn.queue
  "Durable waiting room for held turn requests. Mirrors comm.delivery.queue
   (edn under the isaac root) without sharing its store."
  (:require
    [clojure.edn :as edn]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.tool.memory :as memory])
  (:import
    (java.util UUID)))

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

(def ^:dynamic *root* nil)

(defn- runtime-root []
  (or *root* (loader/root) (throw (ex-info "turn queue requires :root" {}))))

(defn- filesystem []
  (or (fs/instance) (throw (ex-info "turn.queue requires :fs in system" {}))))

(defn- held-dir []
  (str (runtime-root) "/turns/held"))

(defn- held-path [id]
  (str (held-dir) "/" id ".edn"))

(defn- new-id []
  (-> (str (UUID/randomUUID))
      (str/replace "-" "")
      (subs 0 8)))

(defn- normalize-record [record]
  (-> record
      (update :id #(or % (new-id)))
      (update :state #(or % :held))
      (update :created-at #(or % (str (memory/now))))))

;; A live comm channel cannot be written to an EDN record, but a turn parked
;; by a still-running process (a continuation, isaac-xpkf) should wake on the
;; channel that asked for it. The channel is a process-local attachment keyed
;; by record id: present while this process lives, gone after a restart, when
;; the turn wakes comm-less exactly like a resumed one.
(defonce ^:private live-comms* (atom {}))

(defn live-comm [id]
  (get @live-comms* id))

(defn forget-live-comm! [id]
  (swap! live-comms* dissoc id))

(defn- read-record [path]
  (let [fs* (filesystem)]
    (when (fs/exists? fs* path)
      (let [record (edn/read-string (fs/slurp fs* path))]
        (if (map? record)
          (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword k)) v]) record))
          record)))))

(defn enqueue! [record]
  (let [fs*    (filesystem)
        comm   (:comm record)
        record (normalize-record (dissoc record :comm))
        path   (held-path (:id record))]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (write-edn record))
    (when comm
      (swap! live-comms* assoc (:id record) comm))
    (log/info :turn.queue/held
              :id (:id record)
              :session (:session record))
    record))

(defn read-held [id]
  (read-record (held-path id)))

(defn delete-held! [id]
  (forget-live-comm! id)
  (fs/delete (filesystem) (held-path id)))

(defn list-held []
  (let [fs* (filesystem)
        dir (held-dir)]
    (if-let [children (fs/children fs* dir)]
      (->> children
           (map #(read-record (str dir "/" %)))
           (remove nil?)
           (sort-by :created-at)
           vec)
      [])))
