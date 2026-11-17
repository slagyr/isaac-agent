(ns isaac.agent.tool.subagent-spec
  (:require
    [isaac.agent.drive.observer :as observer]
    [isaac.agent.resource-pool :as pool]
    [isaac.agent.session.context :as context]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.tool.fs-bounds :as bounds]
    [isaac.agent.tool.subagent :as sut]
    [isaac.agent.turn.submit :as submit]
    [isaac.agent.turn.queue :as queue]
    [isaac.agent.bridge.cancellation :as cancellation]
    [cheshire.core :as json]
    [isaac.foundation.config.loader :as loader]
    [speclj.core :refer [describe it should= should]]))

(describe "subagent__spawn"
  (it "queues a scout turn with its own crew and session"
    (let [queued (atom nil)
          opts (atom nil)
          cfg {:crew {"main" {:model "grover"} "scout" {:model "parrot"}}
               :models {"grover" {} "parrot" {}}}]
      (with-redefs [loader/snapshot (constantly cfg)
                    queue/all-turns (constantly [])
                    bounds/session-store (constantly :store)
                    bounds/root (constantly "/harbor")
                    store/get-session (fn [_ _] {:id "bridge" :crew "main" :cwd "/harbor"})
                    store/mint-name (constantly "scout-child")
                    context/create-with-resolved-behavior! (fn [_ options] (reset! opts options))
                    observer/ensure-builtins! (fn [] nil)
                    submit/submit! (fn [request] (reset! queued request) {:id "turn-1"})]
        (sut/spawn-tool {"session_key" "bridge" "prompt" "Count moorings" "crew" "scout"})
        (should= "scout" (:crew @opts))
        (should= "bridge" (:parent @opts))
        (should= {:session ["scout-child"] :create :never} (:frequencies @queued))
        (should= "Count moorings" (:prompt @queued))
        (should= "scout" (:crew @queued)))))

  (it "submits a result when the child's turn ends"
    (let [delivered (atom nil)
          recorded  (atom nil)
          factory   (sut/observer-factory)]
      (with-redefs [submit/submit! (fn [request] (reset! delivered request))
                    queue/update-turn! (fn [_ attrs] (reset! recorded attrs))]
        (observer/on-turn-ended (factory ["bridge" "child" "scout" "parrot"])
                                {:request-id "turn-1" :content "Three moorings."
                                 :result {:usage {:requests 2 :prompt-tokens 10 :output-tokens 5}}} :ok))
      (should= 2 (:cycles @recorded))
      (should= {:kind :subagent :session "child"} (:origin @delivered))
      (should= ["bridge"] (get-in @delivered [:frequencies :session]))
      (should= "subagent-child-turn-1" (:id @delivered))
      (should= "Three moorings.\ncrew: scout model: parrot cycles: 2 tokens: 15 session: child"
               (:prompt @delivered))))

  (it "lists a weather-parked child as suspended"
    (with-redefs [bounds/session-store (constantly :store)
                  bounds/root (constantly "/harbor")
                  queue/all-turns (constantly [{:id "one" :session "scout" :state :suspended
                                                :origin {:kind :subagent :parent "bridge"}}])
                  store/get-session (fn [_ _] {:crew "scout" :model "parrot"})]
      (should= "suspended" (get-in (json/parse-string (:result (sut/list-tool {"session_key" "bridge"})) true)
                                   [:children 0 :state]))))

  (it "does not deliver a result or log completion when the child parks for weather"
    (let [delivered (atom [])
          changes   (atom [])
          factory   (sut/observer-factory)]
      (with-redefs [submit/submit! (fn [request] (swap! delivered conj request))
                    queue/update-turn! (fn [id attrs] (swap! changes conj [id attrs]))]
        (observer/on-turn-ended (factory ["bridge" "child" "scout" "parrot"])
                                {:request-id "turn-1" :result {:stopReason "suspended"
                                                                 :ended-by :provider-unavailable
                                                                 :unavailable? true}} {:kind :error :reason "weather"}))
      (should= [] @delivered)
      (should= [["turn-1" {:state :suspended}]] @changes)))

  (it "lists only children owned by bridge with the effective model before their turn runs"
    (with-redefs [bounds/session-store (constantly :store)
                  bounds/root (constantly "/harbor")
                  queue/all-turns (constantly [{:id "one" :session "scout" :state :queued
                                                :origin {:kind :subagent :parent "bridge"}
                                                :observers [[:subagent "bridge" "scout" "main" "grover"]]}
                                               {:id "other" :session "other" :state :queued
                                                :origin {:kind :subagent :parent "someone-else"}}])
                  store/get-session (fn [_ _] {:crew "main"})]
      (should= [{:handle "one" :crew "main" :model "grover" :state "queued" :cycles 0}]
               (:children (json/parse-string (:result (sut/list-tool {"session_key" "bridge"})) true)))))

  (it "cancels only a running child owned by the parent"
    (let [cancelled (atom [])]
      (with-redefs [bounds/session-store (constantly :store)
                    bounds/root (constantly "/harbor")
                    queue/all-turns (constantly [{:id "other" :session "other" :state :running
                                                  :origin {:kind :subagent :parent "elsewhere"}}
                                                 {:id "one" :session "scout" :state :running
                                                  :origin {:kind :subagent :parent "bridge"}}])
                    cancellation/cancel! (fn [session] (swap! cancelled conj session))]
        (should (:isError (sut/cancel-tool {"session_key" "bridge" "handle" "other"})))
        (should= [] @cancelled)
        (sut/cancel-tool {"session_key" "bridge" "handle" "one"})
        (should= ["scout"] @cancelled))))

  (it "cancels a suspended scout, clears its marker, and delivers cancellation to bridge"
    (let [changes (atom [])
          markers (atom [])
          delivered (atom [])]
      (with-redefs [bounds/session-store (constantly :store)
                    bounds/root (constantly "/harbor")
                    queue/all-turns (constantly [{:id "one" :session "scout" :state :suspended
                                                  :origin {:kind :subagent :parent "bridge"}}])
                    queue/update-turn! (fn [id attrs] (swap! changes conj [id attrs]))
                    store/clear-turn-marker! (fn [_ session] (swap! markers conj session))
                    store/get-session (fn [_ _] {:crew "scout" :model "parrot"})
                    submit/submit! (fn [request] (swap! delivered conj request))]
        (should= {:result "Child cancelled."} (sut/cancel-tool {"session_key" "bridge" "handle" "one"})))
      (should= [["one" {:state :finished :outcome :cancelled}]] @changes)
      (should= ["scout"] @markers)
      (should= 1 (count @delivered))
      (should= "Child cancelled." (first (clojure.string/split-lines (:prompt (first @delivered)))))))

  (it "does not call a wake hook while the child releases its leases"
    (let [wakes (atom 0)]
      (pool/set-wake-hook! #(swap! wakes inc))
      (try
        (binding [pool/*defer-wake* true]
          (pool/release-all! []))
        (should= 0 @wakes)
        (pool/release-all! [])
        (should= 1 @wakes)
        (finally (pool/set-wake-hook! nil)))))
  )
