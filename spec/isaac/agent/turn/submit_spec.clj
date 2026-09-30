(ns isaac.agent.turn.submit-spec
  (:require
    [isaac.agent.bridge.core :as bridge]
    [isaac.foundation.config.loader :as loader]
    [isaac.agent.drive.observer :as observer :refer [TurnObserver]]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.resource-pool :as pool]
    [isaac.agent.frequencies :as frequencies]
    [isaac.agent.session.store.spi :as sessions]
    [isaac.agent.turn.queue :as queue]
    [isaac.agent.turn.submit :as sut]
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

  (it "keeps behavioral overrides on an explicitly addressed session"
    (with-redefs [sessions/registered-store (fn [] :sessions)
                  frequencies/resolve-session-targets (fn [_ _ _] {:session-key "engine-room"})]
      (let [turn (sut/submit! {:root "/isaac-state" :config {}
                               :frequencies {:session ["engine-room"] :with-crew "navigator"}
                               :prompt "Check gauges"})]
        (should= "engine-room" (:session turn))
        (should= "navigator" (get-in turn [:frequencies :with-crew])))))

  (it "preserves a caller-supplied origin through the durable turn store"
    (let [origin {:kind :hail :thread-id "tidal-7" :data {:route ["quay" "beacon"]}}
          request {:root "/isaac-state" :config {} :frequencies {:session "lamp-room"}
                   :prompt "Light lamp" :origin origin}]
      (with-redefs [sessions/registered-store (fn [] :sessions)
                    frequencies/resolve-session-targets (fn [_ _ _] {:session-key "lamp-room"})]
        (let [accepted (sut/submit! request)]
          (should= origin (:origin accepted))
          (should= origin (:origin (queue/read-held (:id accepted))))))))

  (it "preserves a generic turn preamble through durable submission"
    (with-redefs [sessions/registered-store (fn [] :sessions)
                  frequencies/resolve-session-targets (fn [_ _ _] {:session-key "lamp-room"})]
      (let [accepted (sut/submit! {:root "/isaac-state" :config {} :frequencies {:session "lamp-room"}
                                   :prompt "Light lamp" :preamble "Seawall metadata"})]
        (should= "Seawall metadata" (:preamble accepted))
        (should= "Seawall metadata" (:preamble (queue/read-held (:id accepted)))))))

  (it "accepts a caller-provided turn id together with its preamble atomically"
    (with-redefs [sessions/registered-store (fn [] :sessions)
                  frequencies/resolve-session-targets (fn [_ _ _] {:session-key "lamp-room"})]
      (let [accepted (sut/submit! {:root "/isaac-state" :config {} :frequencies {:session "lamp-room"}
                                   :id "lamp-7" :prompt "Light lamp" :preamble "Hail id: lamp-7"
                                   :origin {:thread-id "lamp-7"}})]
        (should= "lamp-7" (:id accepted))
        (should= "Hail id: lamp-7" (:preamble (queue/read-held "lamp-7"))))))

  (it "defaults an unnamed origin to submit"
    (with-redefs [sessions/registered-store (fn [] :sessions)
                  frequencies/resolve-session-targets (fn [_ _ _] {:session-key "lamp-room"})]
      (should= {:kind :submit}
               (:origin (sut/submit! {:root "/isaac-state" :config {}
                                      :frequencies {:session "lamp-room"} :prompt "Light lamp"})))))

  (it "tag-addressed turns do not inherit the unrelated default crew"
    (let [cfg {:defaults {:frequencies {:crew "main"}}}]
      (with-redefs [sessions/registered-store (fn [] :sessions)
                    frequencies/resolve-session-targets (fn [f _ got-cfg]
                                                           (should= cfg got-cfg)
                                                           (should= {:session-tags #{:project/warp} :create :never} f)
                                                           {:session-key "engine-room"})]
        (should= #{:project/warp}
                 (get-in (sut/submit! {:root "/isaac-state" :config cfg
                                       :frequencies {:session-tags #{:project/warp} :create :never}
                                       :prompt "Seal leak"}) [:frequencies :session-tags])))))

  (it "keeps a crew address unbound until admission"
    (let [request {:root "/isaac-state" :config {:resource-pools {}}
                   :frequencies {:crew "ketch" :prefer :oldest}
                   :prompt "Status?" :key "watch/ketch/status"}]
      (with-redefs [sessions/registered-store (fn [] :sessions)
                    frequencies/resolve-session-targets (fn [_ _ _]
                                                           {:session-key "mooring"})]
        (let [accepted (sut/submit! request)]
          (should-be-nil (:session accepted))
          (should= (:frequencies request) (:frequencies accepted))))))

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
