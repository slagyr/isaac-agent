(ns isaac.turn.worker
  "Wake the turn-request waiting room: clock ticks plus release-token nudges."
  (:require
    [isaac.bridge.core :as bridge]
    [isaac.charge :as charge]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.store.spi :as store]
    [isaac.tool.memory :as memory]
    [isaac.turn.queue :as queue]
    [isaac.turnstile :as turnstile]))

(def default-tick-ms 10000)

(defonce ^:private tick-state* (atom :idle))

(defn- wake-config [record]
  (or (loader/snapshot "turn-queue wake — resolve parked request against live config")
      (when-let [root (or (:root record) (nexus/get :root) (loader/root))]
        (try
          (loader/load-config! root (or (nexus/get :fs) (fs/instance))
                               "turn-queue wake — empty snapshot, load from root")
          (catch Throwable t
            (log/warn :turn.queue/config-load-failed
                      :id (:id record)
                      :error (.getMessage t))
            nil)))
      {}))

(defn- wake-charge [record now]
  (let [cfg     (wake-config record)
        request (cond-> {:session-key (:session record)
                         :input       (:input record)
                         :now         now
                         :origin      (or (:origin record) {:kind :queue})
                         :config      cfg}
                  (:crew record) (assoc :crew (:crew record))
                  (:observers record) (assoc :observers (:observers record))
                  (:turnstiles record) (assoc :turnstiles (:turnstiles record))
                  (:cwd record) (assoc :cwd (:cwd record)))
        built   (try
                  (charge/build request)
                  (catch Throwable t
                    (log/warn :turn.queue/build-failed
                              :id (:id record)
                              :error (.getMessage t))
                    (assoc request :charge/type :charge)))]
    (assoc built
           :now now
           :from-queue? true
           :held-id (:id record)
           :root (or (:root record) (nexus/get :root) (loader/root))
           :session-store (or (nexus/get-in [:sessions :store]) (store/registered-store)))))

(defn- still-held? [result]
  (or (:held result)
      (and (:error result) (= :hold (:reason result)))))

(defn- process-record! [now record]
  (let [result (try
                 (bridge/dispatch! (wake-charge record now))
                 (catch Throwable t
                   (log/warn :turn.queue/wake-failed
                             :id (:id record)
                             :error (.getMessage t))
                   {:error :exception :message (.getMessage t)}))]
    (log/info :turn.queue/woke
              :id (:id record)
              :held? (boolean (still-held? result))
              :error (:error result)
              :session (:session record))
    (when (and (not (still-held? result))
               (not (:error result)))
      (queue/delete-held! (:id record)))))

(defn- request-tick! []
  (loop []
    (let [state @tick-state*]
      (cond
        (= :idle state)    (if (compare-and-set! tick-state* :idle :running) :run (recur))
        (= :running state) (if (compare-and-set! tick-state* :running :pending) :pending (recur))
        :else              :pending))))

(defn- finish-tick! []
  (loop []
    (let [state @tick-state*]
      (cond
        (= :pending state) (if (compare-and-set! tick-state* :pending :running) :run (recur))
        (= :running state) (if (compare-and-set! tick-state* :running :idle) :idle (recur))
        :else              :idle))))

(defn tick!
  ([] (tick! {}))
  ([{:keys [now]}]
   (let [now (or now (memory/now))]
     (when (= :run (request-tick!))
       (binding [queue/*root* (or queue/*root* (nexus/get :root) (loader/root))]
         (loop []
           (let [failure    (try
                              (doseq [record (queue/list-held)]
                                (process-record! now record))
                              nil
                              (catch Throwable t t))
                 next-state (finish-tick!)]
             (when failure
               (throw failure))
             (when (= :run next-state)
               (recur)))))))))

(defn start!
  [{:keys [tick-ms]
    :or   {tick-ms default-tick-ms}}]
  (let [shared-scheduler (or (nexus/get :scheduler)
                             (throw (ex-info "turn queue worker requires :scheduler in isaac.nexus" {})))]
    (scheduler/schedule! shared-scheduler
                         {:id      :turn.queue/tick
                          :trigger {:kind :interval :ms tick-ms}
                          :handler (fn [_] (tick! {}))})
    (turnstile/set-wake-hook! tick!)
    {:scheduler shared-scheduler
     :task-id   :turn.queue/tick}))

(defn stop! [{:keys [scheduler task-id]}]
  (when scheduler
    (scheduler/cancel! scheduler task-id)
    (turnstile/set-wake-hook! nil)))
