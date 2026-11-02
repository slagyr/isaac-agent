(ns isaac.agent.tool.subagent-spec
  (:require
    [isaac.agent.drive.observer :as observer]
    [isaac.agent.resource-pool :as pool]
    [isaac.agent.session.context :as context]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.tool.fs-bounds :as bounds]
    [isaac.agent.tool.subagent :as sut]
    [isaac.agent.turn.submit :as submit]
    [isaac.foundation.config.loader :as loader]
    [speclj.core :refer [describe it should=]]))

(describe "agent__spawn"
  (it "queues a scout turn with its own crew and session"
    (let [queued (atom nil)
          opts (atom nil)
          cfg {:crew {"main" {:model "grover"} "scout" {:model "parrot"}}
               :models {"grover" {} "parrot" {}}}]
      (with-redefs [loader/snapshot (constantly cfg)
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
          factory (sut/observer-factory)]
      (with-redefs [submit/submit! (fn [request] (reset! delivered request))]
        (observer/on-turn-ended (factory ["bridge" "child" "scout" "parrot"])
                                {:request-id "turn-1" :content "Three moorings."
                                 :result {:usage {:prompt-tokens 10 :output-tokens 5}
                                          :cycle-usages [{}]}} :ok))
      (should= {:kind :subagent :session "child"} (:origin @delivered))
      (should= ["bridge"] (get-in @delivered [:frequencies :session]))
      (should= "subagent-child-turn-1" (:id @delivered))
      (should= "Three moorings.\ncrew: scout model: parrot cycles: 1 tokens: 15 session: child"
               (:prompt @delivered))))

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
