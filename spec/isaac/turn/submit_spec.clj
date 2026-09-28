(ns isaac.turn.submit-spec
  (:require
    [isaac.bridge.core :as bridge]
    [isaac.config.loader :as loader]
    [isaac.drive.observer :as observer :refer [TurnObserver]]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.resource-pool :as pool]
    [isaac.session.frequencies :as frequencies]
    [isaac.session.store.spi :as sessions]
    [isaac.turn.queue :as queue]
    [isaac.turn.submit :as sut]
    [speclj.core :refer :all]))

(describe "Agent turn submission"
  (with mem (fs/mem-fs))
  (around [example]
    (nexus/-with-nested-nexus {:fs @mem :root "/isaac-state"}
      (binding [queue/*root* "/isaac-state"] (example))))

  (it "accepts a keyed turn and returns the same request on retry"
    (let [cfg {:resource-pools {}}
          request {:root "/isaac-state" :fs @mem :config cfg
                   :frequencies {:session "lamp-room"} :prompt "Light lamp"
                   :key "watch/beacon/tide/tend" :observers [[:foreman "watch" "beacon"]]}]
      (with-redefs [sessions/registered-store (fn [] :sessions)
                    frequencies/resolve-session-targets (fn [f _ _]
                                                           (should= {:session ["lamp-room"]} f)
                                                           {:session-key "lamp-room"})
                    observer/resolve-submitted (fn [_] {:observers []})]
        (let [first-turn (sut/submit! request)
              second-turn (sut/submit! request)]
          (should= (:id first-turn) (:id second-turn))
          (should= true (:already-accepted? second-turn))
          (should= 1 (count (queue/all-turns)))
          (should= :queued (:state (first (queue/all-turns))))))))

  (it "keeps observer references durable when a turn is held by its resource pool"
    (let [record (queue/enqueue! {:session "lamp-room" :observers [[:foreman "watch" "beacon"]]
                                   :resource-pools [:dock] :input "Light lamp" :key "watch/beacon/tide/tend"})
          resolved (reify TurnObserver
                     (on-turn-started [_ _])
                     (on-turn-ended [_ _ _])
                     (on-turn-died [_ _ _]))]
      (queue/claim! (:id record))
      (with-redefs [observer/resolve-submitted (fn [_] {:observers [resolved]})
                    pool/resolve-submitted (fn [_ _] {:resource-pools [{:name :dock
                                                                        :pool (reify pool/ResourcePool
                                                                                (try-acquire [_ _] :busy)
                                                                                (release! [_ _]))}]})]
        (let [result (bridge/dispatch! {:charge/type :charge :session-key "lamp-room" :input "Light lamp"
                           :root "/isaac-state" :config {} :key (:key record) :turn-id (:id record)
                           :resource-pools [:dock] :observers (:observers record)})]
          (should= true (:held result))))
      (should= [[:foreman "watch" "beacon"]] (:observers (first (queue/all-turns))))
      (should= "watch/beacon/tide/tend" (:key (first (queue/all-turns))))
      (should= :held (:state (first (queue/all-turns))))
      (should= :held (:state (queue/read-held (:id record))))))

  (it "does not wake a keyed turn twice after the request was accepted"
    (let [request {:root "/isaac-state" :fs @mem :config {} :frequencies {:session "lamp-room"}
                   :prompt "Light lamp" :key "watch/beacon/tide/tend"}]
      (with-redefs [sessions/registered-store (fn [] :sessions)
                    frequencies/resolve-session-targets (fn [_ _ _] {:session-key "lamp-room"})]
        (let [first-turn (sut/submit! request)]
          (queue/update-turn! (:id first-turn) {:state :finished})
          (should= true (:already-accepted? (sut/submit! request)))
          (should= 1 (count (queue/all-turns))))))

  (it "rejects an unknown pool without accepting a request"
    (with-redefs [pool/resolve-submitted (fn [_ _] {:error :unknown-resource-pool
                                                    :message "unknown resource pool drydock"})]
      (should-throw Exception #"unknown resource pool drydock"
        (sut/submit! {:root "/isaac-state" :fs @mem :config {} :resource-pools ["drydock"]
                      :frequencies {:session "lamp-room"} :prompt "Light lamp" :key "watch/7/tide/tend"})))
    (should= [] (queue/all-turns))))
  )
