(ns isaac.comm.delivery.worker
  (:require
    [isaac.comm.protocol :as comm]
    [isaac.comm.delivery.queue :as queue]
    [isaac.comm.registry :as comm-registry]
    [isaac.logger :as log]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory])
  (:import
    (java.time Instant)))

(def default-tick-ms 10000)

(def ^:private delays-ms
  {1 1000
   2 5000
   3 30000
   4 120000
   5 600000})

(defn- backoff-ms [attempts]
  (get delays-ms attempts))

(defn- audit-fields [record]
  {:id     (:id record)
   :comm   (:comm record)
   :target (:target record)})

(defn- dead-letter! [record attempts reason]
  (queue/move-to-failed! (:id record) {:attempts attempts})
  (log/error :comm.delivery/dead-lettered
             (assoc (audit-fields record)
                    :attempts attempts
                    :reason   reason)))

(defn send! [record]
  (if-let [comm-inst (comm-registry/comm-for (:comm record))]
    (comm/send! comm-inst record)
    {:ok false :transient? false}))

(defn- due? [record now]
  (if-let [next-attempt-at (:next-attempt-at record)]
    (not (.isAfter (Instant/parse next-attempt-at) now))
    true))

(defn- reschedule! [now record]
  (let [attempts (inc (:attempts record 0))]
    (if-let [delay-ms (backoff-ms attempts)]
      (if (= attempts 5)
        (dead-letter! record attempts :exhausted)
        (do
          (log/info :comm.delivery/attempt-failed
                    (assoc (audit-fields record) :attempts attempts))
          (queue/update-pending! (:id record) {:attempts        attempts
                                               :next-attempt-at (str (.plusMillis now delay-ms))})))
      (dead-letter! record attempts :exhausted))))

(defn- process-record! [now record]
  (when (due? record now)
    (let [result (try
                   (send! record)
                   (catch Exception e
                     {:error (.getMessage e) :ok false :transient? true}))]
      (cond
        (:ok result)
        (do
          (log/info :comm.delivery/delivered
                    (assoc (audit-fields record) :attempts (:attempts record 0)))
          (queue/delete-pending! (:id record)))

        (false? (:transient? result))
        (dead-letter! record (:attempts record 0) :permanent)

        :else
        (reschedule! now record)))))

(defn tick!
  [{:keys [now]}]
  (let [now (or now (memory/now))]
    (doseq [record (queue/list-pending)]
      (process-record! now record))))

(defn start!
  [{:keys [tick-ms]
    :or   {tick-ms default-tick-ms}}]
  (let [shared-scheduler (or (nexus/get :scheduler)
                             (throw (ex-info "delivery worker requires :scheduler in isaac.nexus" {})))]
    (scheduler/schedule! shared-scheduler
                         {:id      :delivery/tick
                          :trigger {:kind :interval :ms tick-ms}
                          :handler (fn [_] (tick! {}))})
    {:scheduler shared-scheduler
     :task-id   :delivery/tick}))

(defn stop! [{:keys [scheduler task-id]}]
  (when scheduler
    (scheduler/cancel! scheduler task-id)))
