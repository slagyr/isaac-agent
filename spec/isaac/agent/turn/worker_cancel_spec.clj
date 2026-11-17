(ns isaac.agent.turn.worker-cancel-spec
  "A worker-admitted turn holds its in-flight reservation past the bridge's
   release, through queue bookkeeping. A cancel landing there (a subagent
   cancel sees the child still :running) must not reach the next turn."
  (:require
    [isaac.agent.bridge.cancellation :as bridge-cancel]
    [isaac.agent.bridge.core :as bridge]
    [isaac.agent.session.spec-helper :as helper]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.turn.queue :as queue]
    [isaac.agent.turn.worker :as sut]
    [speclj.core :refer :all]))

(describe "worker reservation and pending cancels"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (bridge-cancel/clear!)
    (helper/with-memory-store
      (example))
    (bridge-cancel/clear!))

  (it "drops a cancel that lands during queue bookkeeping after the turn ended"
    (let [ss (store/registered-store)]
      (store/open-session! ss "child" {:crew "main"})
      (store/mark-in-flight! ss "child")
      (with-redefs [bridge/dispatch!         (fn [_]
                                               (let [t (bridge-cancel/begin-turn! "child")]
                                                 (bridge-cancel/end-turn! "child" t))
                                               (bridge-cancel/discard-pending! "child")
                                               {:stopReason "end_turn"})
                    queue/update-turn!       (fn [_ _] (bridge-cancel/cancel! "child") nil)
                    queue/forget-live-comm!  (fn [_] nil)
                    queue/list-held          (fn [] [])]
        (#'sut/process-record! nil {} {:id "rec-1" :origin {:parent "p"}}
                               {:session-reserved? true :session-store ss :session-key "child"}))
      (should-not (store/in-flight? ss "child"))
      (let [t (bridge-cancel/begin-turn! "child")]
        (should-not (bridge-cancel/cancelled? "child"))
        (bridge-cancel/end-turn! "child" t)))))
