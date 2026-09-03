(ns isaac.comm.delivery.worker-spec
  (:require
    [isaac.comm.protocol :as comm]
    [isaac.comm.delivery.queue :as queue]
    [isaac.comm.delivery.worker :as sut]
    [isaac.comm.registry :as comm-registry]
    [isaac.fs :as fs]
    [isaac.config.root :as root]
    [isaac.logger :as log]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.spec-helper :as helper]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(deftype StubComm [result])

(extend StubComm
  comm/Comm
  (merge comm/defaults
         {:send! (fn [this _] (.-result this))}))

(describe "comm.delivery.worker"

  (helper/with-captured-logs)

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (binding [root/*root*         "/test/isaac"
                comm-registry/*registry* (atom (comm-registry/fresh-registry))]
        (it))))

  (describe "send!"

    (it "delegates to the registered comm instance"
      (comm-registry/register-instance! "stub" (->StubComm {:ok true}))
      (should= {:ok true}
               (sut/send! {:comm :stub :target "T1" :content "Hello"})))

    (it "returns permanent failure when no comm is registered for that key"
      (should= {:ok false :transient? false}
               (sut/send! {:comm :pigeon :target "L1" :content "Hello"}))))

  (it "deletes a pending delivery after a successful send"
    (queue/enqueue! {:id      "7f3a"
                     :comm    :stub
                     :target  "C999"
                     :content "Hello"})
    (comm-registry/register-instance! "stub" (->StubComm {:ok true}))
    (sut/tick! {:now (Instant/parse "2026-04-21T10:00:00Z")})
    (should-be-nil (queue/read-pending "7f3a")))

  (it "reschedules a transient failure with the next backoff"
    (queue/enqueue! {:id      "7f3a"
                     :comm    :stub
                     :target  "C999"
                     :content "Hello"})
    (comm-registry/register-instance! "stub" (->StubComm {:ok false :transient? true}))
    (sut/tick! {:now (Instant/parse "2026-04-21T10:00:00Z")})
    (should= {:attempts        1
              :next-attempt-at "2026-04-21T10:00:01Z"}
             (select-keys (queue/read-pending "7f3a") [:attempts :next-attempt-at])))

  (it "moves a delivery to failed and logs when it reaches max attempts"
    (queue/enqueue! {:id       "7f3a"
                     :comm     :stub
                     :target   "C999"
                     :content  "Hello"
                     :attempts 4})
    (comm-registry/register-instance! "stub" (->StubComm {:ok false :transient? true}))
    (sut/tick! {:now (Instant/parse "2026-04-21T10:00:00Z")})
    (should-be-nil (queue/read-pending "7f3a"))
    (should= 5 (:attempts (queue/read-failed "7f3a")))
    (should= {:event :comm.delivery/dead-lettered :id "7f3a" :reason :exhausted}
             (select-keys (last @log/captured-logs) [:event :id :reason])))

  (it "moves a delivery to failed immediately on a permanent failure"
    (queue/enqueue! {:id      "7f3a"
                     :comm    :stub
                     :target  "C999"
                     :content "Hello"})
    (comm-registry/register-instance! "stub" (->StubComm {:ok false :transient? false}))
    (sut/tick! {:now (Instant/parse "2026-04-21T10:00:00Z")})
    (should-be-nil (queue/read-pending "7f3a"))
    (should= 0 (:attempts (queue/read-failed "7f3a")))
    (should= {:event :comm.delivery/dead-lettered :id "7f3a" :reason :permanent}
             (select-keys (last @log/captured-logs) [:event :id :reason])))

  (it "leaves a deferred send pending without burning an attempt"
    (queue/enqueue! {:id       "7f3a"
                     :comm     :stub
                     :target   "C999"
                     :content  "Hold the lantern."
                     :attempts 2})
    (comm-registry/register-instance! "stub" (->StubComm {:ok false :transient? true :defer? true}))
    (sut/tick! {:now (Instant/parse "2026-04-21T10:00:00Z")})
    (should= {:attempts 2 :id "7f3a" :content "Hold the lantern."}
             (select-keys (queue/read-pending "7f3a") [:attempts :id :content]))
    (should-be-nil (:next-attempt-at (queue/read-pending "7f3a")))
    (should-be-nil (queue/read-failed "7f3a"))
    (should= {:event :comm.delivery/deferred :id "7f3a"}
             (select-keys (last @log/captured-logs) [:event :id])))

  (it "registers its tick with the shared scheduler"
    (nexus/-with-nexus {}
      (let [scheduler (-> (scheduler/create {:clock (fn [] (Instant/parse "2026-04-21T10:00:00Z"))})
                          scheduler/start!)]
        (nexus/register! [:scheduler] scheduler)
        (sut/start! {:tick-ms 10000})
        (should= [{:id :delivery/tick :trigger {:kind :interval :ms 10000}}
                  {:id :turn.queue/tick :trigger {:kind :interval :ms 10000}}]
                 (mapv #(select-keys % [:id :trigger]) (scheduler/list-tasks scheduler)))
        (scheduler/stop! scheduler))))

  (it "starts the turn-queue worker on the same scheduler"
    (nexus/-with-nexus {}
      (let [scheduler (-> (scheduler/create {:clock (fn [] (Instant/parse "2026-04-21T10:00:00Z"))})
                          scheduler/start!)]
        (nexus/register! [:scheduler] scheduler)
        (sut/start! {:tick-ms 10000})
        (should= #{:delivery/tick :turn.queue/tick}
                 (set (map :id (scheduler/list-tasks scheduler))))
        (scheduler/stop! scheduler))))
  )
