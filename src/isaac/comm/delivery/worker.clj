(ns isaac.comm.delivery.worker
  (:require
    [isaac.comm.delivery.queue :as queue]
    [isaac.comm.protocol :as comm]
    [isaac.comm.registry :as comm-registry]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
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

(defn- record-target
  "The recipient this record actually carries. Comms name it in their own
   keyword namespace (:imessage/target, :discord/target); the generic
   :target is optional (comm_send and attention config write it). Reading
   only :target logged `:target nil` on every iMessage line, which reads as
   \"this record has no recipient\" — a live and misleading hypothesis to
   hand someone diagnosing a dead delivery."
  [record]
  (or (:target record)
      (some (fn [[k v]]
              (when (and (keyword? k) (= "target" (name k))) v))
            record)))

(defn- audit-fields
  "Structured fields shared by the :comm.delivery/* audit events. The
   comm's :result carries the failure reason; without it the log says only
   that something failed."
  ([record] (audit-fields record nil))
  ([record result]
   (let [target (record-target record)]
     (cond-> {:id   (:id record)
              :comm (:comm record)}
       (some? target)          (assoc :target target)
       (some? (:error result)) (assoc :error (:error result))))))

(defn- dead-letter! [record attempts reason result]
  (queue/move-to-failed! (:id record) {:attempts attempts})
  (log/error :comm.delivery/dead-lettered
             (assoc (audit-fields record result)
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

(defn- reschedule! [now record result]
  (let [attempts (inc (:attempts record 0))]
    (if-let [delay-ms (backoff-ms attempts)]
      (if (= attempts 5)
        (dead-letter! record attempts :exhausted result)
        (do
          (log/info :comm.delivery/attempt-failed
                    (assoc (audit-fields record result) :attempts attempts))
          (queue/update-pending! (:id record) {:attempts        attempts
                                               :next-attempt-at (str (.plusMillis now delay-ms))})))
      (dead-letter! record attempts :exhausted result))))

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

        (:defer? result)
        (log/info :comm.delivery/deferred (audit-fields record result))

        (false? (:transient? result))
        (dead-letter! record (:attempts record 0) :permanent result)

        :else
        (reschedule! now record result)))))

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
