(ns isaac.comm.delivery.worker-spec
  (:require
    [isaac.comm.delivery.queue :as queue]
    [isaac.comm.delivery.worker :as sut]
    [isaac.comm.protocol :as comm]
    [isaac.comm.registry :as comm-registry]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.spec-helper :as helper]
    [isaac.turnstile :as turnstile]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(deftype StubComm [result])

(extend StubComm
  comm/Comm
  (merge comm/defaults
         {:send! (fn [this _] (.-result this))}))

(deftype RecordingComm [sent])

(extend RecordingComm
  comm/Comm
  (merge comm/defaults
         {:send! (fn [this record] (reset! (.-sent this) record) {:ok true})}))

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

    (it "hands the record's :attachments to the comm unchanged"
      (let [sent (atom nil)]
        (comm-registry/register-instance! "stub" (->RecordingComm sent))
        (sut/send! {:comm :stub :content "Report attached." :attachments ["/crew/cordelia/report.pdf"]})
        (should= ["/crew/cordelia/report.pdf"] (:attachments @sent))))

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

  (it "logs the :error the comm returned on a transient attempt-failed"
    (queue/enqueue! {:id      "7f3a"
                     :comm    :stub
                     :target  "C999"
                     :content "Hello"})
    (comm-registry/register-instance! "stub" (->StubComm {:ok         false
                                                          :transient? true
                                                          :error      "Delivery outcome unknown"}))
    (sut/tick! {:now (Instant/parse "2026-04-21T10:00:00Z")})
    (should= {:event :comm.delivery/attempt-failed :id "7f3a" :error "Delivery outcome unknown"}
             (select-keys (last @log/captured-logs) [:event :id :error])))

  (it "logs the :error the comm returned on the terminal dead-letter"
    (queue/enqueue! {:id       "7f3a"
                     :comm     :stub
                     :target   "C999"
                     :content  "Hello"
                     :attempts 4})
    (comm-registry/register-instance! "stub" (->StubComm {:ok         false
                                                          :transient? true
                                                          :error      "Delivery outcome unknown"}))
    (sut/tick! {:now (Instant/parse "2026-04-21T10:00:00Z")})
    (should= {:event :comm.delivery/dead-lettered :reason :exhausted :error "Delivery outcome unknown"}
             (select-keys (last @log/captured-logs) [:event :reason :error])))

  (it "logs a keyword :error such as :timeout on a permanent failure"
    (queue/enqueue! {:id      "7f3a"
                     :comm    :stub
                     :target  "C999"
                     :content "Hello"})
    (comm-registry/register-instance! "stub" (->StubComm {:ok false :transient? false :error :timeout}))
    (sut/tick! {:now (Instant/parse "2026-04-21T10:00:00Z")})
    (should= {:event :comm.delivery/dead-lettered :reason :permanent :error :timeout}
             (select-keys (last @log/captured-logs) [:event :reason :error])))

  (it "logs the :error the comm returned on a deferred send"
    (queue/enqueue! {:id      "7f3a"
                     :comm    :stub
                     :target  "C999"
                     :content "Hold the lantern."})
    (comm-registry/register-instance! "stub" (->StubComm {:ok         false
                                                          :transient? true
                                                          :defer?     true
                                                          :error      "gateway not READY"}))
    (sut/tick! {:now (Instant/parse "2026-04-21T10:00:00Z")})
    (should= {:event :comm.delivery/deferred :id "7f3a" :error "gateway not READY"}
             (select-keys (last @log/captured-logs) [:event :id :error])))

  (it "logs the recipient the record actually carries, not the generic :target"
    (queue/enqueue! {:id              "c972"
                     :comm            :stub
                     :imessage/target "friend@icloud.com"
                     :content         "Hello"})
    (comm-registry/register-instance! "stub" (->StubComm {:ok false :transient? false :error "nope"}))
    (sut/tick! {:now (Instant/parse "2026-04-21T10:00:00Z")})
    (should= "friend@icloud.com" (:target (last @log/captured-logs))))

  (it "omits :target when the record carries no recipient at all"
    (queue/enqueue! {:id      "c972"
                     :comm    :stub
                     :content "Hello"})
    (comm-registry/register-instance! "stub" (->StubComm {:ok false :transient? false :error "nope"}))
    (sut/tick! {:now (Instant/parse "2026-04-21T10:00:00Z")})
    (should= false (contains? (last @log/captured-logs) :target)))

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
        (try
          (nexus/register! [:scheduler] scheduler)
          (let [handle (sut/start! {:tick-ms 10000})]
            (should= [{:id :delivery/tick :trigger {:kind :interval :ms 10000}}]
                     (mapv #(select-keys % [:id :trigger]) (scheduler/list-tasks scheduler)))
            (sut/stop! handle))
          (finally
            (scheduler/stop! scheduler)
            (turnstile/set-wake-hook! nil))))))

  (it "stop! cancels only the delivery tick"
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (let [scheduler (-> (scheduler/create {:clock (fn [] (Instant/parse "2026-04-21T10:00:00Z"))})
                          scheduler/start!)]
        (try
          (nexus/register! [:scheduler] scheduler)
          (let [handle (sut/start! {:tick-ms 10000})]
            (sut/stop! handle)
            (should= [] (scheduler/list-tasks scheduler)))
          (finally
            (scheduler/stop! scheduler)
            (turnstile/set-wake-hook! nil))))))
  )
