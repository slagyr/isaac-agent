(ns isaac.agent.turn.worker
  "Wake the turn-request waiting room: clock ticks plus release-token nudges."
  (:require
    [clojure.string :as str]
    [isaac.agent.bridge.core :as bridge]
    [isaac.agent.charge :as charge]
    [isaac.foundation.config.loader :as loader]
    [isaac.agent.drive.weather :as weather]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.logger :as log]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.scheduler.runtime :as scheduler]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.frequencies :as frequencies]
    [isaac.agent.session.context :as session-context]
    [isaac.foundation.naming :as naming]
    [isaac.agent.tool.memory :as memory]
    [isaac.agent.turn.queue :as queue]
    [isaac.agent.resource-pool :as pool]))

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

;; A turn started asynchronously can itself trigger more async turn-queue
;; work before it settles — bridge.core's own-session drain-on-release
;; (isolate-cleanup! :drain-waiting-session) calls isaac.agent.turn.worker/tick!
;; from inside a *different* turn's finally block and discards what it
;; started. Tracking every started future here — not just the ones a
;; particular tick! call happened to start — lets await-idle! below wait out
;; that whole chain regardless of which call kicked each link off.
(defonce ^:private active-futures* (atom #{}))

(defn- run-record-async!
  "Starts one claimed record's turn on its own thread and returns immediately.
   The tick's job ends at claim + start; process-record!'s bookkeeping (outcome,
   merged ids, live-comm cleanup, held->:held on a hold) happens on that thread
   once the turn ends, so a long turn on one session never blocks another
   session's claimed turn from starting in the same pass (isaac-e9jl). Follows
   the same bound-fn + future idiom as isaac.agent.drive.turn/start-async-compaction!
   so dynamic bindings active at claim time (queue/*root*, memory/*now*) carry
   into the turn's thread."
  [now record]
  (let [self (promise)
        task (bound-fn []
               (try
                 (process-record! now record)
                 (catch Throwable t
                   (log/warn :turn.queue/record-crashed
                             :id (:id record)
                             :error (.getMessage t)))
                 (finally
                   ;; Leave the registry on the way out, or a long-lived server
                   ;; keeps every finished turn's future (and result) forever.
                   (swap! active-futures* disj @self))))
        fut  (future (task))]
    (swap! active-futures* conj fut)
    (deliver self fut)
    fut))

(defn await-idle!
  "Blocks until every turn started via the turn queue — by this process,
   including any chained by an automatic drain — has finished, or timeout-ms
   elapses. Not used on the production path (the whole point of isaac-e9jl is
   that nothing should block on another session's turn); for tests and tools
   that need the queue to have gone fully quiet before asserting on it."
  ([] (await-idle! 30000))
  ([timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (swap! active-futures* #(into #{} (remove realized?) %))
       (when-let [pending (seq @active-futures*)]
         (doseq [fut pending]
           (deref fut (max 1 (- deadline (System/currentTimeMillis))) ::timeout))
         (when (< (System/currentTimeMillis) deadline)
           (recur)))))))

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

(defn- run-one-pass!
  "Claims and starts every runnable record, then returns — the pass's own job
   ends at claim + start (isaac-e9jl)."
  [now]
  (let [records (queue/list-held)
        waiting-sessions (distinct (map :session (filter #(= :waiting-session (:state %)) records)))
        waiting-ids (set (mapcat #(map :id (mapcat identity (queue/waiting-groups %))) waiting-sessions))
        ordinary (remove #(contains? waiting-ids (:id %)) records)]
    (doseq [record ordinary]
      (when (queue/claim! (:id record))
        (run-record-async! now record)))
    (doseq [session waiting-sessions
            :when (not (store/in-flight? (or (nexus/get-in [:sessions :store])
                                              (store/registered-store)) session))
            records (queue/waiting-groups session)]
      (let [record (coalesced-record records)]
        (when (> (count records) 1)
          (log/info :turn/coalesced :session session :key (:coalesce-key record) :count (count records)))
        (when (queue/claim! (:id record))
          (run-record-async! now record))))))

(defn tick!
  "Claims and starts every runnable record, then returns — a long turn on
   one session never blocks another session's claimed turn from starting in
   the same pass, nor holds tick-state* (and so every later scheduled or
   woken tick) hostage until it finishes (isaac-e9jl). A caller that needs
   the turns this call started (and anything they chain) to have actually
   finished — a test, not the production scheduler or the resource-pool
   wake-hook — calls await-idle! afterward."
  ([] (tick! {}))
  ([{:keys [now]}]
   (let [now (or now (memory/now))]
     (when (= :run (request-tick!))
       (binding [queue/*root* (or queue/*root* (nexus/get :root) (loader/root))]
         ;; A pass that throws must not abandon a coalesced wake that arrived
         ;; while it ran: finish-tick! can still hand back :run (another pass
         ;; owed), and re-throwing immediately would leave tick-state* stuck
         ;; at :running forever — every later tick (interval or wake-hook)
         ;; then CASes :running->:pending and no-ops for good, silently
         ;; disabling the queue until a process restart (isaac-2lc4). Keep
         ;; looping until finish-tick! actually returns to :idle, then
         ;; surface the last failure, if any.
         (loop [pending-failure nil]
           (let [failure    (try
                              (run-one-pass! now)
                              nil
                              (catch Throwable t t))
                 next-state (finish-tick!)
                 failure    (or failure pending-failure)]
             (if (= :run next-state)
               (recur failure)
               (when failure
                 (throw failure))))))))))

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
                             (throw (ex-info "turn queue worker requires :scheduler in isaac.foundation.nexus" {})))]
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
