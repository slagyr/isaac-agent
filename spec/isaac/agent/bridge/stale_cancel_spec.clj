(ns isaac.agent.bridge.stale-cancel-spec
  (:require
    [isaac.agent.bridge.cancellation :as bridge-cancel]
    [isaac.agent.bridge.core :as bridge]
    [isaac.agent.charge :as charge]
    [isaac.agent.drive.turn :as single-turn]
    [isaac.agent.session.spec-helper :as helper]
    [isaac.agent.session.store.spi :as store]
    [isaac.foundation.config.api :as config]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.marigold :as marigold]
    [isaac.foundation.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private cfg
  {:defaults  {:frequencies {:crew "main"} :crew {:model "fast"}}
   :crew      {"main" {:soul "Main soul" :model "fast"}}
   :models    {"fast" {:model "anvil-x-mini" :provider marigold/quantum-anvil :context-window 16000}}
   :providers {marigold/quantum-anvil {:api marigold/anvil-api}}})

(defn- dispatch-with-turn! [session-key run-turn]
  (with-redefs [single-turn/run-turn! run-turn]
    (bridge/dispatch! (charge/build {:session-key session-key :input "hello" :root (nexus/get :root)}))))

(describe "bridge stale cancels"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (bridge-cancel/clear!)
    (helper/with-memory-store
      (nexus/-with-nested-nexus {:root (str "/tmp/stale-cancel-spec-" (random-uuid)) :fs (fs/mem-fs)}
        (config/dangerously-install-config! cfg "spec")
        (helper/create-session! (nexus/get :root) "late-cancel" {:crew "main"})
        (example)))
    (bridge-cancel/clear!))

  (it "drops a cancel that lands after the turn ends but before the session is released"
    (dispatch-with-turn! "late-cancel"
                         (fn [_charge]
                           (let [turn (bridge-cancel/begin-turn! "late-cancel")]
                             (bridge-cancel/end-turn! "late-cancel" turn))
                           (bridge-cancel/cancel! "late-cancel")
                           {:message {:role "assistant" :content "ok"}}))
    (should-not (bridge-cancel/cancelled? "late-cancel")))

  (it "still cancels a turn when the cancel lands after admission but before the turn begins"
    (let [seen (atom nil)]
      (dispatch-with-turn! "late-cancel"
                           (fn [_charge]
                             (bridge-cancel/cancel! "late-cancel")
                             (let [turn (bridge-cancel/begin-turn! "late-cancel")]
                               (reset! seen (bridge-cancel/cancelled? "late-cancel"))
                               (bridge-cancel/end-turn! "late-cancel" turn))
                             {:message {:role "assistant" :content "ok"}}))
      (should= true @seen)
      (should-not (bridge-cancel/cancelled? "late-cancel")))))
