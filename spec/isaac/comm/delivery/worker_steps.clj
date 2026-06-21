(ns isaac.comm.delivery.worker-steps
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.comm.delivery.queue :as queue]
    [isaac.comm.delivery.worker :as worker]
    [isaac.comm.protocol :as comm]
    [isaac.comm.registry :as comm-registry]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.step-tables :as match])
  (:import
    (java.time Instant)))

(helper! isaac.comm.delivery.worker-steps)

(defn- root-dir []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (mem-fs)}
    (f)))

(defn- parse-cell [value]
  (cond
    (nil? value) nil
    (re-matches #"-?\d+" value) (parse-long value)
    (= "true" (str/lower-case value)) true
    (= "false" (str/lower-case value)) false
    (or (str/starts-with? value "[")
        (str/starts-with? value "{")
        (str/starts-with? value ":")
        (str/starts-with? value "\"")) (edn/read-string value)
    :else value))

(deftype StubComm []
  comm/Comm
  (on-turn-start [_ _ _] nil)
  (on-text-chunk [_ _ _] nil)
  (on-tool-call [_ _ _] nil)
  (on-tool-cancel [_ _ _] nil)
  (on-tool-result [_ _ _ _] nil)
  (on-compaction-start [_ _ _] nil)
  (on-compaction-success [_ _ _] nil)
  (on-compaction-failure [_ _ _] nil)
  (on-compaction-disabled [_ _ _] nil)
  (on-turn-end [_ _ _] nil)
  (send! [_ record]
    (g/update! :stub-comm-calls #(conj (or % []) record))
    (or (g/get :stub-comm-result) {:ok true})))

(def ^:private default-stub-comm-names
  #{"stub" "longwave" "skybeam" "logbook"})

(defn- stub-comm-instances []
  (into {}
        (map (fn [name] [name (->StubComm)]))
        (or (g/get :stub-comm-names) default-stub-comm-names)))

(defn- with-stub-comm [f]
  (binding [comm-registry/*registry* (atom (assoc (comm-registry/fresh-registry)
                                                  :instances (stub-comm-instances)))]
    (f)))

(defn comm-stub-returns [_comm-name table]
  (let [headers (:headers table)
        row     (first (:rows table))
        result  (into {} (map #(vector (keyword %1) (parse-cell %2)) headers row))]
    (g/assoc! :stub-comm-result result)
    (g/dissoc! :stub-comm-calls)))

(defn comm-stub-was-called-with [_comm-name table]
  (let [calls  (or (g/get :stub-comm-calls) [])
        result (match/match-entries table calls)]
    (g/should= [] (:failures result))))

(defn delivery-worker-ticks []
  (with-feature-fs
    (fn []
      (with-stub-comm
        #(nexus/-with-nexus {:root (root-dir) :fs (mem-fs)}
           (worker/tick! {}))))))

(defn delivery-worker-ticks-at [iso]
  (with-feature-fs
    (fn []
      (with-stub-comm
        #(nexus/-with-nexus {:root (root-dir) :fs (mem-fs)}
           (worker/tick! {:now (Instant/parse iso)}))))))

(defn isaac-system-started []
  (let [clock     (fn [] (Instant/parse "2026-04-21T10:00:00Z"))
        scheduler (-> (scheduler/create {:clock clock}) scheduler/start!)]
    (nexus/-with-nexus {:scheduler scheduler}
      (worker/start! {:tick-ms worker/default-tick-ms}))
    (g/assoc! :scheduler scheduler)))

(defn scheduled-tasks-include [table]
  (let [tasks   (mapv #(assoc % :id (if-let [ns (namespace (:id %))]
                                     (str ns "/" (name (:id %)))
                                     (name (:id %))))
                      (scheduler/list-tasks (g/get :scheduler)))
        result  (match/match-entries table tasks)]
    (g/should= [] (:failures result))))

(g/after-scenario
  (fn []
    (when-let [scheduler (g/get :scheduler)]
      (scheduler/stop! scheduler)
      (g/dissoc! :scheduler))))

(defwhen "the delivery worker ticks" isaac.comm.delivery.worker-steps/delivery-worker-ticks)

(defwhen #"the delivery worker ticks at \"([^\"]+)\"" isaac.comm.delivery.worker-steps/delivery-worker-ticks-at)

(defwhen "the comm delivery system is started" isaac.comm.delivery.worker-steps/isaac-system-started)

(defgiven #"the comm \"([^\"]+)\" returns:" isaac.comm.delivery.worker-steps/comm-stub-returns)

(defthen #"the comm \"([^\"]+)\" was called with:" isaac.comm.delivery.worker-steps/comm-stub-was-called-with)

(defn delivery-queue-is-empty []
  (with-feature-fs
    (fn []
      (g/should= [] (queue/list-pending)))))

(defthen "the delivery queue is empty" isaac.comm.delivery.worker-steps/delivery-queue-is-empty)

(defthen "the delivery scheduled tasks include:" isaac.comm.delivery.worker-steps/scheduled-tasks-include)
