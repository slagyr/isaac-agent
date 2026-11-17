(ns isaac.agent.bridge.cancel-expectation-spec
  (:require
    [isaac.agent.bridge.cancellation :as sut]
    [isaac.agent.session.spec-helper :as helper]
    [isaac.agent.session.store.spi :as store]
    [speclj.core :refer :all]))

(describe "bridge cancellation: requested and admitted turns"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (sut/clear!)
    (example)
    (sut/clear!))

  (it "keeps a cancel for a requested turn the bridge has not admitted yet"
    (let [token (sut/expect-turn! "requested")]
      (sut/cancel! "requested")
      (let [turn (sut/begin-turn! "requested")]
        (should (sut/cancelled? "requested"))
        (sut/end-turn! "requested" turn))
      (sut/settle-turn! "requested" token)
      (should-not (sut/cancelled? "requested"))))

  (it "drops a cancel that lands after a requested turn ended once the request settles"
    (let [token (sut/expect-turn! "settled")
          turn  (sut/begin-turn! "settled")]
      (sut/end-turn! "settled" turn)
      (sut/cancel! "settled")
      (sut/settle-turn! "settled" token))
    (let [next-turn (sut/begin-turn! "settled")]
      (should-not (sut/cancelled? "settled"))
      (sut/end-turn! "settled" next-turn)))

  (it "discard-pending! drops an unadopted cancel but leaves a running turn's cancel alone"
    (helper/with-memory-store
      (let [ss (store/registered-store)]
        (store/open-session! ss "admitted" {:crew "main"})
        (store/mark-in-flight! ss "admitted")
        (sut/cancel! "admitted")
        (store/clear-in-flight! ss "admitted")
        (sut/discard-pending! "admitted")
        (should-not (sut/cancelled? "admitted"))))
    (let [turn (sut/begin-turn! "running")]
      (sut/cancel! "running")
      (sut/discard-pending! "running")
      (should (sut/cancelled? "running"))
      (sut/end-turn! "running" turn))))
