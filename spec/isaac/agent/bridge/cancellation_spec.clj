(ns isaac.agent.bridge.cancellation-spec
  (:require
    [isaac.foundation.logger :as log]
    [isaac.agent.bridge.cancellation :as sut]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.session.spec-helper :as helper]
    [isaac.agent.session.store.spi :as store]
    [speclj.core :refer :all]))

(describe "bridge cancellation"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (sut/clear!)
    (example)
    (sut/clear!))

  (it "cancels an active turn across system bindings"
    (let [called? (atom false)
          turn    (nexus/-with-nexus {}
                    (sut/begin-turn! "abc"))]
      (nexus/-with-nexus {}
        (sut/on-cancel! "abc" #(reset! called? true))
        (sut/cancel! "abc")
        (should @called?)
        (should (sut/cancelled? "abc")))
      (nexus/-with-nexus {}
        (sut/end-turn! "abc" turn)
        (should-not (sut/cancelled? "abc")))))

  (it "logs cancel applied with hook count for an active turn"
    (let [turn (nexus/-with-nexus {}
                 (sut/begin-turn! "cancel-test"))]
      (log/capture-logs
        (nexus/-with-nexus {}
          (sut/on-cancel! "cancel-test" (fn [] nil))
          (sut/cancel! "cancel-test")
          (let [entry (first (filter #(= :bridge/cancel-applied (:event %)) @log/captured-logs))]
            (should-not-be-nil entry)
            (should= :info (:level entry))
            (should= "cancel-test" (:session entry))
            (should= 1 (:hooks entry)))))
      (nexus/-with-nexus {}
        (sut/end-turn! "cancel-test" turn))))

  (it "logs cancel noop when no hooks are registered"
    (log/capture-logs
      (nexus/-with-nexus {}
        (sut/cancel! "idle-session")
        (let [entry (first (filter #(= :bridge/cancel-noop (:event %)) @log/captured-logs))]
          (should-not-be-nil entry)
          (should= :info (:level entry))
          (should= "idle-session" (:session entry))
          (should= 0 (:hooks entry))))))

  (it "applies a cancel to an admitted turn that has not begun yet"
    (helper/with-memory-store
      (let [ss (store/registered-store)]
        (store/open-session! ss "later" {:crew "main"})
        (store/mark-in-flight! ss "later")
        (try
          (nexus/-with-nexus {}
            (sut/cancel! "later"))
          (let [turn (nexus/-with-nexus {}
                       (sut/begin-turn! "later"))]
            (nexus/-with-nexus {}
              (should (sut/cancelled? "later"))
              (sut/end-turn! "later" turn)
              (should-not (sut/cancelled? "later"))))
          (finally
            (store/clear-in-flight! ss "later"))))))

  (context "stale cancels"

    (it "a cancel with no turn running or admitted is a logged no-op"
      (log/capture-logs
        (sut/cancel! "idle")
        (should= [:bridge/cancel-noop]
                 (map :event (filter #(= "idle" (:session %)) @log/captured-logs)))
        (should-not (sut/cancelled? "idle"))))

    (it "a cancel with no turn running does not cancel the next turn"
      (sut/cancel! "idle")
      (let [turn (sut/begin-turn! "idle")]
        (should-not (sut/cancelled? "idle"))
        (sut/end-turn! "idle" turn)))

    (it "a cancel during a running turn cancels it and the next turn starts clean"
      (let [first-turn (sut/begin-turn! "busy")]
        (sut/cancel! "busy")
        (should (sut/cancelled? "busy"))
        (sut/end-turn! "busy" first-turn))
      (let [next-turn (sut/begin-turn! "busy")]
        (should-not (sut/cancelled? "busy"))
        (sut/end-turn! "busy" next-turn)))

    (it "a cancel landing just after the session is released does not leak to the next turn"
      (helper/with-memory-store
        (let [ss (store/registered-store)]
          (store/open-session! ss "racy" {:crew "main"})
          (store/mark-in-flight! ss "racy")
          (let [turn (sut/begin-turn! "racy")]
            (sut/end-turn! "racy" turn))
          (store/clear-in-flight! ss "racy")
          (sut/cancel! "racy")
          (let [next-turn (sut/begin-turn! "racy")]
            (should-not (sut/cancelled? "racy"))
            (sut/end-turn! "racy" next-turn)))))))
