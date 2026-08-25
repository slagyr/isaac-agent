(ns isaac.turn.worker-spec
  (:require
    [isaac.bridge.core :as bridge]
    [isaac.charge :as charge]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.spec-helper :as helper]
    [isaac.turn.queue :as queue]
    [isaac.turn.worker :as sut]
    [isaac.turnstile :as turnstile]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(describe "turn.worker"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "leaves a held turn parked when dispatch parks again"
    (queue/enqueue! {:id         "berth-1"
                     :session    "harbor"
                     :input      "Leave harbor"
                     :turnstiles [[:tide "22:00-06:00"]]
                     :state      :held})
    (let [ran (atom [])]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (swap! ran conj charge)
                                       {:held true :id "berth-1" :reason :hold})]
        (sut/tick! {:now (Instant/parse "2026-03-01T14:00:00Z")}))
      (should= 1 (count @ran))
      (should= "berth-1" (:id (queue/read-held "berth-1")))))

  (it "runs a held turn whose stack now passes and drops it"
    (queue/enqueue! {:id         "berth-1"
                     :session    "harbor"
                     :input      "Leave harbor"
                     :turnstiles [[:tide "22:00-06:00"]]
                     :state      :held})
    (let [ran (atom [])]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (swap! ran conj charge)
                                       {:content "Setting sail"})]
        (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")}))
      (should= 1 (count @ran))
      (should= "harbor" (:session-key (first @ran)))
      (should= "Leave harbor" (:input (first @ran)))
      (should-be-nil (queue/read-held "berth-1"))))

  (it "runs every held turn whose stack now passes, in submit order"
    (queue/enqueue! {:id "later" :session "quay" :input "three"
                     :turnstiles [] :created-at "2026-03-01T14:00:02Z"})
    (queue/enqueue! {:id "first" :session "jetty" :input "two"
                     :turnstiles [] :created-at "2026-03-01T14:00:01Z"})
    (let [ran (atom [])]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (swap! ran conj (:session-key charge))
                                       {})]
        (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")}))
      (should= ["jetty" "quay"] @ran)
      (should= [] (queue/list-held))))

  (it "does not drop a held turn that parks again on wake"
    (queue/enqueue! {:id         "berth-1"
                     :session    "harbor"
                     :input      "Leave harbor"
                     :turnstiles [[:tide "22:00-06:00"]]
                     :state      :held})
    (with-redefs [bridge/dispatch! (fn [_]
                                     {:held true :id "berth-1" :reason :hold})]
      (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")}))
    (should= "berth-1" (:id (queue/read-held "berth-1"))))

  (it "builds the wake charge from the current config snapshot"
    (queue/enqueue! {:id         "berth-1"
                     :session    "harbor"
                     :input      "Leave harbor"
                     :turnstiles [[:tide "22:00-06:00"]]
                     :state      :held})
    (let [seen (atom nil)
          cfg  {:defaults {:crew "main" :model "echo"}
                :crew     {"main" {:model "echo"}}
                :models   {"echo" {:model "echo" :provider "grover"}}}]
      (with-redefs [loader/snapshot (fn [_] cfg)
                    charge/build    (fn [request]
                                      (reset! seen request)
                                      (assoc request :charge/type :charge :model "echo"))
                    bridge/dispatch! (fn [_] {:content "Setting sail"})]
        (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")}))
      (should= cfg (:config @seen))
      (should= "harbor" (:session-key @seen))
      (should= "Leave harbor" (:input @seen))))

  (it "loads config from the isaac root when the snapshot is empty"
    (let [fs* (nexus/get :fs)]
      (fs/mkdirs fs* "/test/isaac/config/models")
      (fs/mkdirs fs* "/test/isaac/config/crew")
      (fs/mkdirs fs* "/test/isaac/config/providers")
      (fs/spit fs* "/test/isaac/config/isaac.edn"
               (pr-str {:defaults {:crew "main" :model "grover"}}))
      (fs/spit fs* "/test/isaac/config/models/grover.edn"
               (pr-str {:model "echo" :provider :grover}))
      (fs/spit fs* "/test/isaac/config/crew/main.edn"
               (pr-str {:model :grover :soul "You are Atticus."}))
      (fs/spit fs* "/test/isaac/config/providers/grover.edn"
               (pr-str {})))
    (queue/enqueue! {:id         "berth-1"
                     :session    "harbor"
                     :input      "Leave harbor"
                     :turnstiles [[:tide "22:00-06:00"]]
                     :state      :held})
    (let [seen (atom nil)]
      (with-redefs [loader/snapshot  (fn [_] nil)
                    charge/build     (fn [request]
                                       (reset! seen request)
                                       (assoc request :charge/type :charge :model "echo"))
                    bridge/dispatch! (fn [_] {:content "Setting sail"})]
        (sut/tick! {:now (Instant/parse "2026-03-01T23:30:00Z")}))
      (should= "grover" (get-in @seen [:config :defaults :model]))
      (should= "harbor" (:session-key @seen))))

  (it "registers its tick with the shared scheduler"
    (nexus/-with-nexus {}
      (let [sched (-> (scheduler/create {:clock (fn [] (Instant/parse "2026-03-01T14:00:00Z"))})
                      scheduler/start!)]
        (try
          (nexus/register! [:scheduler] sched)
          (let [handle (sut/start! {:tick-ms 10000})]
            (should= [{:id :turn.queue/tick :trigger {:kind :interval :ms 10000}}]
                     (mapv #(select-keys % [:id :trigger]) (scheduler/list-tasks sched)))
            (sut/stop! handle))
          (finally
            (scheduler/stop! sched)
            (turnstile/set-wake-hook! nil))))))

  (it "does not wake parked turns on token release until start!"
    (turnstile/set-wake-hook! nil)
    (let [ran (atom [])]
      (with-redefs [bridge/dispatch! (fn [charge]
                                       (swap! ran conj charge)
                                       {:content "should not run"})]
        (queue/enqueue! {:id "orphan" :session "harbor" :input "stay parked" :state :held})
        (let [gate (reify turnstile/Turnstile
                     (admit? [_ _] :pass)
                     (release! [_ _] nil))
              {:keys [tokens]} (turnstile/admit-all! [gate] {})]
          (turnstile/release-all! tokens)))
      (should= [] @ran)
      (should= "orphan" (:id (queue/read-held "orphan"))))))
