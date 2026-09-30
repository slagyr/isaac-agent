(ns isaac.agent.resource-pool
  "Named resource pool instances and nonblocking type factories. A throwing
  release is isolated so other leases are always returned."
  (:require
    [clojure.string :as str]
    [isaac.foundation.logger :as log]
    [isaac.agent.tool.memory :as memory])
  (:import (java.time Instant LocalTime ZoneOffset)
           (java.time.format DateTimeFormatter)))

(defprotocol ResourcePool
  (try-acquire [this ctx])
  (release! [this token]))

(defrecord ReleaseToken [id])

(defonce ^:private factories* (atom {}))

(defn register!
  "Register a type factory. Registration does not create a pool instance."
  [name factory]
  (swap! factories* assoc (keyword name) factory)
  name)

(defn unregister! [name]
  (swap! factories* dissoc (keyword name)))

(defonce ^:private released-ids* (atom #{}))

(defn clear! []
  (reset! factories* {})
  (reset! released-ids* #{}))

(defn resolve
  "Construct a configured named instance; factory registration alone never leases."
  [config pool-name]
  (let [instance (get (:resource-pools config) (name pool-name))
         instance (or instance (get (:resource-pools config) (keyword pool-name)))]
     (when-let [factory (get @factories* (:type instance))]
       (factory instance))))

(defn unknown-resource-pool-message [pool-name]
  (str "unknown resource pool: " (name pool-name)))

(def ^:private TIME-FMT (DateTimeFormatter/ofPattern "H:mm"))

(defn- parse-clock [s]
  (LocalTime/parse s TIME-FMT))

(defn- ->instant [now]
  (cond
    (instance? Instant now) now
    (string? now) (Instant/parse now)
    :else nil))

(defn- now-of [ctx]
  (or (->instant (:now ctx))
      (memory/now)
      (Instant/now)))

(defn- clock-of [instant]
  (.toLocalTime (.atOffset instant ZoneOffset/UTC)))

(defn- in-window? [now open close]
  (if (.isBefore close open)
    (or (not (.isBefore now open))
        (.isBefore now close))
    (and (not (.isBefore now open))
         (.isBefore now close))))

(defn- window-of [raw]
  (try
    (let [parts (when (string? raw) (str/split raw #"-"))]
      (when (= 2 (count parts))
        [(parse-clock (first parts)) (parse-clock (second parts))]))
    (catch Exception _ nil)))

(defn valid-window? [raw]
  (boolean (window-of raw)))

(defn tide
  "Clock-window resource pool. Factory receives {:type :tide :window ...}."
  [{:keys [window]}]
  (let [[open close] (window-of window)]
    (reify ResourcePool
      (try-acquire [_ ctx]
        (if (in-window? (clock-of (now-of ctx)) open close)
          (->ReleaseToken (str (java.util.UUID/randomUUID)))
          :busy))
      (release! [_ _lease] nil))))

(defn ensure-builtins! []
  (register! :tide tide))

(defn resolve-submitted
  "Resolve named instances from config before dispatch."
  [config names]
  (ensure-builtins!)
  (loop [remaining names acc []]
    (if-let [pool-name (first remaining)]
      (if-let [pool (resolve config pool-name)]
        (recur (rest remaining) (conj acc {:name pool-name :pool pool}))
        {:error :unknown-resource-pool
         :message (unknown-resource-pool-message pool-name)
         :ref pool-name})
      {:resource-pools acc})))

(defn register-entry! [[name entry]]
  (let [factory (some-> (:factory entry) requiring-resolve var-get)]
    (register! name factory)))

(defn registered-names [] (set (keys @factories*)))

(defonce ^:private wake-hook* (atom nil))

(defn set-wake-hook!
  "Register a zero-arg callback invoked after releasing leases."
  [f]
  (reset! wake-hook* f))

(defn- nudge-wake! []
  (when-let [hook @wake-hook*]
    (try
      (hook)
      (catch Throwable t
        (log/warn :pool/wake-failed
                  :error (.getMessage t)
                  :ex-class (.getName (class t)))))))

(defn- release-one! [{:keys [resource-pool token release-id]}]
  (try
    (when (or (nil? release-id)
              (not (contains? (first (swap-vals! released-ids* conj release-id)) release-id)))
      (release! resource-pool (or token {:release-id release-id})))
    (catch Throwable t
      (log/warn :pool/release-failed
                :error (.getMessage t)
                :ex-class (.getName (class t))))))

(defn- release-acquired! [tokens]
  (doseq [acquired (reverse tokens)]
    (release-one! acquired)))

(defn release-all!
  "Return leases in reverse order and wake the waiting queue."
  [tokens]
  (release-acquired! tokens)
  (nudge-wake!))

(defn- receipt [lease]
  (if (instance? ReleaseToken lease)
    {:bindings {} :release-id (str (java.util.UUID/randomUUID)) :token lease}
    lease))

(defn acquire-all!
  "Acquire in request order without waiting. A busy instance gives back all
   prior leases in reverse order; release on completion wakes the queue."
  [pools ctx]
  (loop [remaining pools acquired []]
    (if-let [{:keys [name pool]} (first remaining)]
      (let [lease (try-acquire pool ctx)]
        (if (= :busy lease)
          (do (release-acquired! acquired)
              {:error :busy :reason :hold :message (str (clojure.core/name name) " busy") :leases []})
          (let [{:keys [bindings release-id token]} (receipt lease)
                acquired-lease {:name name :resource-pool pool :token (or token lease)
                                :release-id release-id :bindings (or bindings {})}
                unknown (seq (remove #{:session/cwd} (keys bindings)))]
            (if unknown
              (do (release-acquired! (conj acquired acquired-lease))
                  {:error :unknown-binding
                   :message (str "unknown binding: " (first unknown))
                   :leases []})
              (recur (rest remaining) (conj acquired acquired-lease))))))
      {:leases acquired})))
