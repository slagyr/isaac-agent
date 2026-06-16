(ns isaac.comm.factory-spec
  (:require
    [c3kit.apron.log :as apron-log]
    [isaac.comm.registry :as comm-registry]
    [isaac.comm.factory :as sut]
    [isaac.schema.registered-in :as registered-in]
    [isaac.spec-helper :as helper]
    [speclj.core :refer :all]))

(defmethod sut/create :probe [node-path slice]
  {:probe true :slot (last node-path) :slice slice})

(describe "comm slots"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (binding [comm-registry/*registry*     (atom (comm-registry/fresh-registry))
              registered-in/*module-index* {}]
      (example)))

  (it "creates the instance through the impl's defmethod"
    (let [instance (sut/create! [:comms :bert] {:type :probe :x 1})]
      (should= {:probe true :slot :bert :slice {:type :probe :x 1}} instance)))

  (it "derives the impl from the slot name when :type is absent"
    (should= :probe (:slot (sut/create! [:comms :probe] {:x 1}))))

  (it "prefers a programmatically registered constructor"
    (comm-registry/register-factory! "probe" (fn [host] {:legacy (:name host)}))
    (should= {:legacy :bert} (sut/create! [:comms :bert] {:type :probe})))

  (it "returns nil and logs when no implementation exists"
    (apron-log/capture-logs
      (should-be-nil (sut/create! [:comms :bert] {:type :ghost}))))

  (it "loads the contributing module's :namespace on first dispatch"
    (binding [registered-in/*module-index*
              {:isaac.comm.lazy {:manifest {:isaac.server/comm
                                            {:lazyimpl {:namespace 'isaac.comm.factory-lazy-fixture}}}}}]
      (should= :isaac.comm.factory-lazy-fixture/lazy (sut/create! [:comms :bert] {:type :lazyimpl})))))
