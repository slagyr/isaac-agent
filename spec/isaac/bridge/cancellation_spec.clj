(ns isaac.bridge.cancellation-spec
  (:require
    [isaac.logger :as log]
    [isaac.bridge.cancellation :as sut]
    [isaac.nexus :as nexus]
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

  (it "applies a pending cancel to the next turn across system bindings"
    (nexus/-with-nexus {}
      (sut/cancel! "later"))
    (let [turn (nexus/-with-nexus {}
                 (sut/begin-turn! "later"))]
      (nexus/-with-nexus {}
        (should (sut/cancelled? "later"))
        (sut/end-turn! "later" turn)
        (should-not (sut/cancelled? "later"))))))
