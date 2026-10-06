(ns isaac.agent.turn.worker-spec
  (:require
    [isaac.agent.bridge.core :as bridge]
    [isaac.agent.charge :as charge]
    [isaac.foundation.config.loader :as loader]
    [isaac.agent.drive.weather :as weather]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.scheduler.runtime :as scheduler]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.spec-helper :as helper]
    [isaac.agent.turn.queue :as queue]
    [isaac.agent.turn.worker :as sut]
    [isaac.agent.resource-pool :as pool]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

;; run-one-pass! claims a record and starts its turn on its own thread, then
;; moves on (isaac-e9jl) — tick! returns before the turn finishes. Every
;; assertion on a record's post-dispatch state must first wait for that
;; thread to finish the isaac.agent.turn.worker/process-record! bookkeeping (claim!
;; already moved it off :queued/:held/:waiting-session, so "settled" is
;; simply "no longer :running"). Call from inside the enclosing with-redefs
;; so the mocked vars are still in effect while the turn's thread runs
;; (with-redefs alters a var's root binding, visible to every thread, but
;; only for as long as the form is active).
(defn- await-settled! [id]
  (helper/await-condition #(not= :running (:state (queue/read-held id)))))

(describe "turn.worker"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (with-redefs [store/mark-in-flight! (fn [_ _] true)
                    store/clear-in-flight! (fn [_ _] nil)
                    pool/resolve-submitted (fn [_ _] {:resource-pools []})]
        (example))))

  (it "returns a worksite lease when the queue claim throws"
    (queue/enqueue! {:id "berth-1" :session "harbor" :input "Leave harbor"
                     :resource-pools [:worksite]})
    (let [released (atom [])
          lease {:resource-pool :worksite :release-id "chart-room|token"}]
      (with-redefs [pool/resolve-submitted (fn [_ _] {:resource-pools [:worksite]})
                    pool/acquire-all! (fn [_ _] {:leases [lease]})
                    pool/release-all! (fn [leases] (swap! released conj leases))
                    queue/claim! (fn [_] (throw (ex-info "queue disk failed" {})))]
        (should-throw Exception "queue disk failed" (sut/tick!)))
      (should= [[lease]] @released)))

  (it "returns a worksite lease if admission fails after acquisition"
    (queue/enqueue! {:id "berth-2" :session "harbor" :input "Leave harbor"
                     :resource-pools [:worksite]})
    (let [released (atom [])
          broken-binding (reify clojure.lang.ILookup
                           (valAt [_ _] (throw (ex-info "binding failed" {})))
                           (valAt [_ _ _] (throw (ex-info "binding failed" {}))))
          lease {:resource-pool :worksite :release-id "chart-room|token" :bindings broken-binding}]
      (with-redefs [pool/resolve-submitted (fn [_ _] {:resource-pools [:worksite]})
                    pool/acquire-all! (fn [_ _] {:leases [lease]})
                    pool/release-all! (fn [leases] (swap! released conj leases))]
        (should-throw Exception "binding failed" (sut/tick!)))
      (should= [[lease]] @released)))

  (it "leaves a held turn parked when dispatch parks again"
    (queue/enqueue! {:id         "berth-1"
                     :session    "harbor"
                     :input      "Leave harbor"
                     :resource-pools [:night-watch]
                     :state      :held})
    (let [ran (atom [])]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (swap! ran conj charge)
                                       {:held true :id "berth-1" :reason :hold})]
        (sut/tick! {:now (Instant/parse "2026-03-01T14:00:00Z")})
        (await-settled! "berth-1"))
      (should= 1 (count @ran))
      (should= "berth-1" (:id (queue/read-held "berth-1")))))

  (it "keeps a request held when its selected session is already in flight"
    (queue/enqueue! {:id "berth-1" :frequencies {:session-tags #{:project/warp} :create :never}
                     :input "Seal leak"})
    (with-redefs [store/registered-store (fn [] :sessions)
                  store/in-flight-sessions (fn [_] #{"engine-room"})
                  isaac.agent.frequencies/resolve-session-targets
                  (fn [_ _ _ busy]
                    (should= #{"engine-room"} busy)
                    {:busy? true})
                  bridge/dispatch! (fn [_] (throw (ex-info "should not dispatch" {})))]
      (sut/tick! {:now (Instant/parse "2026-03-01T18:00:00Z")})
      (await-settled! "berth-1"))
    (should= :held (:state (queue/read-held "berth-1"))))

  (it "creates a session for a create-enabled hail at admission with its tags and origin"
    (queue/enqueue! {:id "coil-1" :frequencies {:session-tags #{:project/warp}
                                                  :create :if-missing :with-crew "bartholomew"}
                     :origin {:source :hail} :input "Seal leak"})
    (let [opened (atom nil)]
      (with-redefs [loader/snapshot (fn [_] {:crew {"bartholomew" {:model "grover"}}})
                    store/registered-store (fn [] :sessions)
                    isaac.agent.frequencies/resolve-session-targets
                    (fn [_ _ _ _] {:create? true :create-identity {:tags #{:project/warp}}})
                    store/mint-name (fn [] "session-1")
                    isaac.agent.session.context/create-with-resolved-behavior!
                    (fn [name opts] (reset! opened [name opts]) {:id name})
                    charge/build identity
                    bridge/dispatch! (fn [_] {})]
        (sut/tick! {:now (Instant/parse "2026-03-01T18:00:00Z")})
        (await-settled! "coil-1"))
      (should= "session-1" (first @opened))
      (should= #{:project/warp} (get-in @opened [1 :tags]))
      (should= "bartholomew" (get-in @opened [1 :crew]))
      (should= {:source :hail :kind :hail} (get-in @opened [1 :origin]))))

  (it "creates the named session with its crew before dispatching a queued turn"
    (queue/enqueue! {:id "coil-named" :frequencies {:session "crows-nest" :crew "lookout"
                                                    :create :if-missing}
                     :input "Signal the fleet"})
    (let [opened (atom nil)
          ran    (atom nil)]
      (with-redefs [loader/snapshot (fn [_] {:crew {"lookout" {:model "echo"}}})
                    store/registered-store (fn [] :sessions)
                    isaac.agent.frequencies/resolve-session-targets
                    (fn [_ _ _ _] {:session-key "crows-nest" :create? true
                                   :create-identity {:crew "lookout"}})
                    isaac.agent.session.context/create-with-resolved-behavior!
                    (fn [name opts] (reset! opened [name opts]) {:id name})
                    charge/build identity
                    bridge/dispatch! (fn [charge] (reset! ran charge) {})]
        (sut/tick! {:now (Instant/parse "2026-03-01T18:00:00Z")})
        (await-settled! "coil-named"))
      (should= "crows-nest" (first @opened))
      (should= "lookout" (get-in @opened [1 :crew]))
      (should= "crows-nest" (:session-key @ran))))

  (it "passes the submitted cycle override to the charge at admission"
    (queue/enqueue! {:id "coil-1" :session "coil-work" :input "Seal leak"
                     :cycle {:limit 1 :checkpoint-every 1}})
    (let [seen (atom nil)]
      (with-redefs [charge/build (fn [request] (reset! seen request) request)
                    bridge/dispatch! (fn [_] {})]
        (sut/tick! {:now (Instant/parse "2026-03-01T18:00:00Z")})
        (await-settled! "coil-1"))
      (should= {:limit 1 :checkpoint-every 1} (:cycle @seen))))

  (it "uses sequential session naming when the loaded configuration requests it"
    (queue/enqueue! {:id "coil-2" :frequencies {:session-tags #{:project/warp} :create :if-missing
                                                   :with-crew "bartholomew"}
                     :origin {:source :hail} :input "Seal leak"})
    (let [opened (atom nil)]
      (with-redefs [loader/snapshot (fn [_] {:sessions {:naming-strategy :sequential}
                                            :crew {"bartholomew" {:model "grover"}}})
                    store/registered-store (fn [] :sessions)
                    isaac.agent.frequencies/resolve-session-targets
                    (fn [_ _ _ _] {:create? true :create-identity {:tags #{:project/warp}}})
                    isaac.agent.session.context/create-with-resolved-behavior!
                    (fn [name _] (reset! opened name) {:id name})
                    charge/build identity
                    bridge/dispatch! (fn [_] {})]
        (sut/tick! {:now (Instant/parse "2026-03-01T18:00:00Z")})
        (await-settled! "coil-2"))
      (should= "session-1" @opened)))

  (it "passes a submitted preamble to the charge at admission"
    (queue/enqueue! {:id "berth-1" :session "harbor" :input "Leave harbor"
                     :preamble "Bridge watch instructions"})
    (let [seen (atom nil)]
      (with-redefs [charge/build (fn [request] (reset! seen request) request)
                    bridge/dispatch! (fn [_] {})]
        (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")})
        (await-settled! "berth-1"))
      (should= "Bridge watch instructions" (:preamble @seen))))

  (it "runs a held turn whose stack now passes and drops it"
    (queue/enqueue! {:id         "berth-1"
                     :session    "harbor"
                     :input      "Leave harbor"
                     :resource-pools [:night-watch]
                     :state      :held})
    (let [ran (atom [])]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (swap! ran conj charge)
                                       {:content "Setting sail"})]
        (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")})
        (await-settled! "berth-1"))
      (should= 1 (count @ran))
      (should= "harbor" (:session-key (first @ran)))
      (should= "Leave harbor" (:input (first @ran)))
      (should= :ok (:outcome (queue/read-held "berth-1")))))

  (it "records a failed wake reason and finish time"
    (queue/enqueue! {:id "lamp-7" :session "harbor" :input "Trim lamp" :state :queued})
    (with-redefs [bridge/dispatch! (fn [_] {:error :provider :message "lamp oil spilled"})]
      (sut/tick! {:now (Instant/parse "2026-03-01T18:00:00Z")})
      (await-settled! "lamp-7"))
    (let [record (queue/read-held "lamp-7")]
      (should= :error (:outcome record))
      (should= "lamp oil spilled" (:reason record))
      (should (:started-at record))
      (should (:finished-at record))))

  (it "runs every held turn whose stack now passes"
    (queue/enqueue! {:id "later" :session "quay" :input "three"
                     :resource-pools [] :created-at "2026-03-01T14:00:02Z"})
    (queue/enqueue! {:id "first" :session "jetty" :input "two"
                     :resource-pools [] :created-at "2026-03-01T14:00:01Z"})
    (let [ran (atom [])]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (swap! ran conj (:session-key charge))
                                       {})]
        (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")})
        (await-settled! "later")
        (await-settled! "first"))
      ;; Both run — but no longer necessarily in submit order: each claimed
      ;; turn starts on its own thread and moves on (isaac-e9jl), so
      ;; completion order across different sessions is no longer guaranteed.
      (should= #{"jetty" "quay"} (set @ran))
      (should= [] (queue/list-held))))

  (it "starts a claimed turn on its own thread so a slow session doesn't stall another session in the same pass (isaac-e9jl)"
    (queue/enqueue! {:id "first" :session "jetty" :input "one" :resource-pools []})
    (queue/enqueue! {:id "second" :session "quay" :input "two" :resource-pools []})
    (let [started (promise)
          release (promise)
          ran     (atom [])]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (swap! ran conj (:session-key charge))
                                       (when (= "jetty" (:session-key charge))
                                         (deliver started true)
                                         @release)
                                       {})]
        ;; The pre-fix synchronous tick would block here for as long as
        ;; "jetty" holds dispatch!, since run-one-pass! dispatched each
        ;; record inline. Bound the deref so a regression fails fast
        ;; instead of hanging the suite.
        (should= false (= ::timeout
                          (deref (future (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")}))
                                 2000 ::timeout)))
        @started
        ;; "quay" (second) finishes without waiting for "jetty" (first),
        ;; which is still blocked inside dispatch!.
        (await-settled! "second")
        (should= :finished (:state (queue/read-held "second")))
        (should= :running (:state (queue/read-held "first")))
        (deliver release true)
        (await-settled! "first"))
      (should= #{"jetty" "quay"} (set @ran))
      (should= [] (queue/list-held))))

  (it "does not run a duplicate turn for a coalesced member exposed by a nested drain (isaac-2tez)"
    ;; bridge.core's own-session drain-waiting-session fires a nested tick!
    ;; from inside dispatch! (its finally, after clearing in-flight but
    ;; before process-record! marks the coalesced group's members
    ;; :finished). Before isaac-2tez, run-one-pass! claimed only the merged
    ;; record's own id, leaving the other coalesced member still
    ;; :waiting-session — visible to that nested tick!, which re-discovers
    ;; it and starts a second, spurious turn for it alone.
    (queue/enqueue! {:id "two" :session "harbor" :input "two" :state :waiting-session
                     :coalesce-key "t1" :created-at "2026-03-01T14:00:01Z"})
    (queue/enqueue! {:id "three" :session "harbor" :input "three" :state :waiting-session
                     :coalesce-key "t1" :created-at "2026-03-01T14:00:02Z"})
    (let [ran (atom [])]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (swap! ran conj (:input charge))
                                       (sut/tick! {:now (Instant/parse "2026-03-01T14:00:03Z")})
                                       {:content "Both answered."})]
        (sut/tick! {:now (Instant/parse "2026-03-01T14:00:00Z")})
        (sut/await-idle!))
      (should= ["two\nthree"] @ran)))

  (it "accepts the next tick after queue inspection fails"
    (let [calls (atom 0)]
      (with-redefs [queue/list-held (fn []
                                     (if (= 1 (swap! calls inc))
                                       (throw (ex-info "broken queue" {}))
                                       []))]
        (should-throw Exception "broken queue" (sut/tick!))
        (sut/tick!))
      (should= 2 @calls)))

  (it "does not wedge the tick loop forever when a coalesced wake arrives during a failing pass (isaac-2lc4)"
    ;; A wake (interval fire, or the resource-pool release hook) that lands
    ;; while a tick is already running is coalesced: request-tick! moves
    ;; :running -> :pending instead of dropping it, and the running tick's
    ;; finish-tick! picks that up as one more owed pass. Before isaac-2lc4,
    ;; a pass that threw re-raised immediately without taking that owed
    ;; pass, stranding tick-state* off :idle — every later tick (scheduled
    ;; or wake-hook) then no-ops forever, exactly like the production
    ;; turn-queue going silent after one bad pass. Simulate the coalesced
    ;; wake by calling tick! recursively from inside queue/list-held, from
    ;; the still-:running first pass, then let that first pass throw.
    (let [calls (atom 0)]
      (with-redefs [queue/list-held (fn []
                                     (let [n (swap! calls inc)]
                                       (when (= 1 n)
                                         (sut/tick! {:now (Instant/parse "2026-03-01T18:00:00Z")})
                                         (throw (ex-info "broken queue" {})))
                                       []))]
        (should-throw Exception "broken queue"
                      (sut/tick! {:now (Instant/parse "2026-03-01T18:00:00Z")})))
      ;; The coalesced pass (owed because of the nested wake) must have run
      ;; as part of draining back to :idle, not been abandoned.
      (should= 2 @calls)
      ;; And the mechanism must still be live for the next call — not wedged.
      (with-redefs [queue/list-held (fn [] (swap! calls inc) [])]
        (sut/tick! {:now (Instant/parse "2026-03-01T18:00:10Z")}))
      (should= 3 @calls)))

  (it "does not drop a held turn that parks again on wake"
    (queue/enqueue! {:id         "berth-1"
                     :session    "harbor"
                     :input      "Leave harbor"
                     :resource-pools [:night-watch]
                     :state      :held})
    (with-redefs [bridge/dispatch! (fn [_]
                                     {:held true :id "berth-1" :reason :hold})]
      (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")})
      (await-settled! "berth-1"))
    (should= "berth-1" (:id (queue/read-held "berth-1"))))

  (it "builds the wake charge from the current config snapshot"
    (queue/enqueue! {:id         "berth-1"
                     :session    "harbor"
                     :input      "Leave harbor"
                     :resource-pools [:night-watch]
                     :state      :held})
    (let [seen (atom nil)
          cfg  {:defaults {:frequencies {:crew "main"} :crew {:model "echo"}}
                :crew     {"main" {:model "echo"}}
                :models   {"echo" {:model "echo" :provider "grover"}}}]
      (with-redefs [loader/snapshot (fn [_] cfg)
                    charge/build    (fn [request]
                                      (reset! seen request)
                                      (assoc request :charge/type :charge :model "echo"))
                    bridge/dispatch! (fn [_] {:content "Setting sail"})]
        (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")})
        (await-settled! "berth-1"))
      (should= cfg (:config @seen))
      (should= "harbor" (:session-key @seen))
      (should= "Leave harbor" (:input @seen))))

  (it "loads config from the isaac root when the snapshot is empty"
    (let [fs* (nexus/get :fs)]
      (fs/mkdirs fs* "/test/isaac/config/models")
      (fs/mkdirs fs* "/test/isaac/config/crew")
      (fs/mkdirs fs* "/test/isaac/config/providers")
      (fs/spit fs* "/test/isaac/config/isaac.edn"
               (pr-str {:defaults {:frequencies {:crew "main"} :crew {:model "grover"}}}))
      (fs/spit fs* "/test/isaac/config/models/grover.edn"
               (pr-str {:model "echo" :provider :grover}))
      (fs/spit fs* "/test/isaac/config/crew/main.edn"
               (pr-str {:model :grover :soul "You are Atticus."}))
      (fs/spit fs* "/test/isaac/config/providers/grover.edn"
               (pr-str {})))
    (queue/enqueue! {:id         "berth-1"
                     :session    "harbor"
                     :input      "Leave harbor"
                     :resource-pools [:night-watch]
                     :state      :held})
    (let [seen (atom nil)]
      (with-redefs [loader/snapshot  (fn [_] nil)
                    charge/build     (fn [request]
                                       (reset! seen request)
                                       (assoc request :charge/type :charge :model "echo"))
                    bridge/dispatch! (fn [_] {:content "Setting sail"})]
        (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")})
        (await-settled! "berth-1"))
      (should= "grover" (get-in @seen [:config :defaults :crew :model]))
      (should= "harbor" (:session-key @seen))))

  (it "does not wake a turn from a process without a running queue worker"
    (queue/enqueue! {:id "berth-shell" :session "harbor" :input "Leave harbor"})
    (with-redefs [sut/tick! (fn [] (throw (ex-info "shell ticked" {})))]
      (sut/wake!))
    (should= :held (:state (queue/read-held "berth-shell"))))

  (it "registers its tick and the weather sweep with the shared scheduler"
    (nexus/-with-nexus {}
      (let [sched (-> (scheduler/create {:clock (fn [] (Instant/parse "2026-03-01T14:00:00Z"))})
                      scheduler/start!)]
        (try
          (nexus/register! [:scheduler] sched)
          (let [handle (sut/start! {:tick-ms 10000})
                ticks  (atom 0)]
            (with-redefs [sut/tick! (fn [] (swap! ticks inc))]
              (sut/wake!))
            (should= 1 @ticks)
            (should= [{:id :turn.queue/tick :trigger {:kind :interval :ms 10000}}
                      {:id :turn/sweep-weather :trigger {:kind :interval :ms 10000}}]
                     (mapv #(select-keys % [:id :trigger]) (scheduler/list-tasks sched)))
            (sut/stop! handle)
            (with-redefs [sut/tick! (fn [] (throw (ex-info "stopped worker ticked" {})))]
              (sut/wake!))
            (should= [] (mapv :id (scheduler/list-tasks sched))))
          (finally
            (scheduler/stop! sched)
            (pool/set-wake-hook! nil))))))

  (it "sweeps weather on its own tick"
    (let [swept (atom [])
          store (reify Object)]
      (nexus/-with-nested-nexus {:sessions {:store store}}
        (with-redefs [weather/sweep-weather! (fn [opts] (swap! swept conj opts))]
          (sut/sweep-tick! {:now (Instant/parse "2026-03-01T14:00:00Z")})))
      (should= 1 (count @swept))
      (should= store (:session-store (first @swept)))
      (should= :sweep (:trigger (first @swept)))
      (should= (Instant/parse "2026-03-01T14:00:00Z") (:now (first @swept)))))

  (it "sweeps nothing when no session store is registered"
    (let [swept (atom [])]
      (nexus/-with-nexus {}
        (with-redefs [store/registered-store    (fn [] nil)
                      weather/sweep-weather! (fn [opts] (swap! swept conj opts))]
          (sut/sweep-tick!)))
      (should= [] @swept)))

  (it "does not wake parked turns on token release until start!"
    (pool/set-wake-hook! nil)
    (let [ran (atom [])]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (swap! ran conj charge)
                                       {:content "should not run"})]
        (queue/enqueue! {:id "orphan" :session "harbor" :input "stay parked" :state :held})
        (let [gate (reify pool/ResourcePool
                     (try-acquire [_ _] (pool/->ReleaseToken "lease"))
                     (release! [_ _] nil))
              {:keys [leases]} (pool/acquire-all! [{:name :dock :pool gate}] {})]
          (pool/release-all! leases)))
      (should= [] @ran)
      (should= "orphan" (:id (queue/read-held "orphan")))))

  (it "resolves wake-config synchronously, before tick! returns, so a caller's own nexus scope (isaac.foreman.core/retry!, loading a fresh snapshot before waking the queue, exactly as a CLI would) has not yet moved on when the config is read (isaac-8evx)"
    (let [real-snapshot loader/snapshot
          tick-returned? (atom false)
          read-when      (atom ::not-yet)]
      (queue/enqueue! {:id "berth-1" :session "harbor" :input "Leave harbor"
                       :resource-pools [] :created-at "2026-03-01T14:00:00Z"})
      (with-redefs [bridge/dispatch! (fn [_] {:content "Setting sail"})
                    loader/snapshot  (fn [reason]
                                       (when (= ::not-yet @read-when)
                                         (reset! read-when (if @tick-returned? :after-tick-returned :before-tick-returned)))
                                       (real-snapshot reason))]
        (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")})
        (reset! tick-returned? true)
        (await-settled! "berth-1"))
      ;; Before isaac-8evx, this read happened inside process-record!, on the
      ;; turn's own future thread, which only starts once tick! has already
      ;; returned (isaac-e9jl: claim + start, then return) — so a caller that
      ;; installs a fresh config and calls tick! from inside its own nexus
      ;; scope could have already unwound that scope (isaac.foundation.nexus
      ;; is one process-wide atom, not a dynamic var) by the time this read
      ;; actually ran, and could see whatever the now-restored outer scope
      ;; holds instead — including nothing at all.
      (should= :before-tick-returned @read-when))))
