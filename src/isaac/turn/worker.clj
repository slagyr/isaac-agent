(ns isaac.turn.worker
  "Wake the turn-request waiting room: clock ticks plus release-token nudges."
  (:require
    [clojure.string :as str]
    [isaac.bridge.core :as bridge]
    [isaac.charge :as charge]
    [isaac.config.loader :as loader]
    [isaac.drive.weather :as weather]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.store.spi :as store]
    [isaac.session.frequencies :as frequencies]
    [isaac.session.context :as session-context]
    [isaac.naming :as naming]
    [isaac.tool.memory :as memory]
    [isaac.turn.queue :as queue]
    [isaac.resource-pool :as pool]))

(def default-tick-ms 10000)

;; The smallest weather backoff is 30 s, so a 10 s sweep is prompt without
;; being busy.
(def default-sweep-tick-ms 10000)

(defonce ^:private tick-state* (atom :idle))

(defn- wake-config [record]
  (or (let [snapshot (loader/snapshot "turn-queue wake — resolve parked request against live config")]
        (when (seq snapshot) snapshot))
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
        ss      (or (nexus/get-in [:sessions :store]) (store/registered-store))
        target  (when-let [address (:frequencies record)]
                  (frequencies/resolve-session-targets address ss cfg
                    (set (store/in-flight-sessions ss))))
        session-key (or (:session-key target)
                        (when (and (:create? target) (not (:busy? target)))
                          (let [name (if (= :sequential (get-in cfg [:sessions :naming-strategy]))
                                       (naming/generate (store/make-naming-strategy
                                                          cfg (or (nexus/get :root) (loader/root)) ss
                                                          (or (nexus/get :fs) (fs/instance))))
                                       (store/mint-name))]
                            (session-context/create-with-resolved-behavior!
                              name (merge {:config cfg :session-store ss
                                           :origin (assoc (:origin record) :kind :hail)}
                                          (:create-identity target)
                                          (frequencies/behavioral-override (:frequencies record))))
                            name))
                        (:session record))
        request (cond-> {:session-key session-key
                         :input       (:input record)
                         :now         now
                         :origin      (or (:origin record) {:kind :queue})
                         :config      cfg}
                  (:preamble record) (assoc :preamble (:preamble record))
                  (:cycle record) (assoc :cycle (:cycle record))
                  (get-in record [:frequencies :with-crew]) (assoc :crew (get-in record [:frequencies :with-crew]))
                  (get-in record [:frequencies :with-model]) (assoc :model-override (get-in record [:frequencies :with-model]))
                  (:crew record) (assoc :crew (:crew record))
                  (queue/live-comm (:id record)) (assoc :comm (queue/live-comm (:id record)))
                  (:observers record) (assoc :observers (:observers record))
                  (:resource-pools record) (assoc :resource-pools (:resource-pools record))
                  (:key record) (assoc :key (:key record))
                  (:cwd record) (assoc :cwd (:cwd record))
                  (:input-persisted? record) (assoc :input-persisted? true))
        built   (try
                  (charge/build request)
                  (catch Throwable t
                    (log/warn :turn.queue/build-failed
                              :id (:id record)
                              :error (.getMessage t))
                    (assoc request :charge/type :charge)))]
    (assoc built
           :address-busy? (:busy? target)
           :now now
           :from-queue? true
           :held-id (:id record)
           :turn-id (:id record)
           :root (or (:root record) (nexus/get :root) (loader/root))
           :session-store (or (nexus/get-in [:sessions :store]) (store/registered-store)))))

(defn- still-held? [result]
  (or (:held result)
      (and (:error result) (= :hold (:reason result)))))

(defn- coalesced-record [records]
  (let [first-record (first records)
        last-record  (last records)]
    (assoc first-record
           :input (str/join "\n" (map :input records))
           :origin (:origin last-record)
           :held-ids (mapv :id records))))

(defn- process-record! [now record]
  (let [result (try
                 (let [charge (wake-charge record now)]
                   (if (:address-busy? charge)
                     {:held true}
                     (bridge/dispatch! charge)))
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
    (if (still-held? result)
      (when (= :running (:state (queue/read-held (:id record))))
        (queue/update-turn! (:id record) {:state :held}))
      (doseq [id (or (:held-ids record) [(:id record)])]
        (queue/update-turn! id (cond-> {:state :finished :outcome (if (:error result) :error :ok)}
                                 (not= id (:id record)) (assoc :merged-into (:id record))
                                 (:error result) (assoc :reason (or (:message result) (name (:error result))))))
        (queue/forget-live-comm! id)))))

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
                              (let [records (queue/list-held)
                                    waiting-sessions (distinct (map :session (filter #(= :waiting-session (:state %)) records)))
                                    waiting-ids (set (mapcat #(map :id (mapcat identity (queue/waiting-groups %))) waiting-sessions))
                                    ordinary (remove #(contains? waiting-ids (:id %)) records)]
                                (doseq [record ordinary]
                                  (when (queue/claim! (:id record))
                                    (process-record! now record)))
                                (doseq [session waiting-sessions
                                        :when (not (store/in-flight? (or (nexus/get-in [:sessions :store])
                                                                          (store/registered-store)) session))
                                        records (queue/waiting-groups session)]
                                  (let [record (coalesced-record records)]
                                    (when (> (count records) 1)
                                      (log/info :turn/coalesced :session session :key (:coalesce-key record) :count (count records)))
                                    (when (queue/claim! (:id record))
                                      (process-record! now record)))))
                              nil
                              (catch Throwable t t))
                 next-state (finish-tick!)]
             (when failure
               (throw failure))
             (when (= :run next-state)
               (recur)))))))))

(defn sweep-tick!
  "One weather sweep: re-drive the turns parked on provider weather whose
   :retry-at has come due. The sweep had no production caller before isaac-f3hq
   — it was reachable only from the feature steps, so a parked turn waited for
   the next boot to resume."
  ([] (sweep-tick! {}))
  ([{:keys [now]}]
   (when-let [session-store (or (nexus/get-in [:sessions :store]) (store/registered-store))]
     (weather/sweep-weather! {:session-store session-store
                              :cfg           (or (loader/snapshot "weather sweep — re-drive parked turns") {})
                              :now           (or now (memory/now))
                              :trigger       :sweep}))))

(defn start!
  [{:keys [tick-ms sweep-tick-ms]
    :or   {tick-ms default-tick-ms sweep-tick-ms default-sweep-tick-ms}}]
  (let [shared-scheduler (or (nexus/get :scheduler)
                             (throw (ex-info "turn queue worker requires :scheduler in isaac.nexus" {})))]
    (scheduler/schedule! shared-scheduler
                         {:id      :turn.queue/tick
                          :trigger {:kind :interval :ms tick-ms}
                          :handler (fn [_] (tick! {}))})
    (scheduler/schedule! shared-scheduler
                         {:id      :turn/sweep-weather
                          :trigger {:kind :interval :ms sweep-tick-ms}
                          :handler (fn [_] (sweep-tick! {}))})
    (pool/set-wake-hook! tick!)
    {:scheduler      shared-scheduler
     :task-id        :turn.queue/tick
     :sweep-task-id  :turn/sweep-weather}))

(defn stop! [{:keys [scheduler task-id sweep-task-id]}]
  (when scheduler
    (scheduler/cancel! scheduler task-id)
    (when sweep-task-id
      (scheduler/cancel! scheduler sweep-task-id))
    (pool/set-wake-hook! nil)))
