(ns comm.delivery.queue-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.log-steps :as log-steps]
            [isaac.foundation.root-steps :as root-steps]))

(describe "Delivery queue"

  (around [it]
    (binding [g/*state* (atom {})]
      (lifecycle/run-before-feature-hooks!)
      (try
        (it)
        (finally
          (lifecycle/run-after-feature-hooks!)))))

  (around [it]
    (binding [g/*state* (atom @g/*state*)]
      (lifecycle/run-before-scenario-hooks!)
      (try
        (it)
        (finally
          (lifecycle/run-after-scenario-hooks!)))))

  (it "a successful delivery is removed from the queue"
    ;; given an Isaac root at "target/test-state"
    ;; given the comm "stub" returns:
    ;; given the EDN isaac file "comm/delivery/pending/7f3a.edn" contains:
    ;; when the delivery worker ticks
    ;; then the isaac file "comm/delivery/pending/7f3a.edn" does not exist
    ;; and the comm "stub" was called with:
    (pending "not yet implemented"))

  (it "a transient failure reschedules the delivery with backoff"
    ;; given an Isaac root at "target/test-state"
    ;; given the comm "stub" returns:
    ;; given the EDN isaac file "comm/delivery/pending/7f3a.edn" contains:
    ;; when the delivery worker ticks at "2026-04-21T10:00:00Z"
    ;; given the EDN isaac file "comm/delivery/pending/7f3a.edn" contains:
    (pending "not yet implemented"))

  (it "delivery moves to failed after max attempts"
    ;; given an Isaac root at "target/test-state"
    ;; given the comm "stub" returns:
    ;; given the EDN isaac file "comm/delivery/pending/7f3a.edn" contains:
    ;; when the delivery worker ticks at "2026-04-21T10:00:00Z"
    ;; then the isaac file "comm/delivery/pending/7f3a.edn" does not exist
    ;; given the EDN isaac file "comm/delivery/failed/7f3a.edn" contains:
    ;; then the log has entries matching:
    (pending "not yet implemented"))

  (it "a permanent failure dead-letters immediately without retrying"
    ;; given an Isaac root at "target/test-state"
    ;; given the comm "stub" returns:
    ;; given the EDN isaac file "comm/delivery/pending/7f3a.edn" contains:
    ;; when the delivery worker ticks
    ;; then the isaac file "comm/delivery/pending/7f3a.edn" does not exist
    ;; given the EDN isaac file "comm/delivery/failed/7f3a.edn" contains:
    ;; then the log has entries matching:
    (pending "not yet implemented"))

  (it "delivery worker tick is registered with the shared scheduler"
    ;; given an Isaac root at "target/test-state"
    ;; when the Isaac system is started
    ;; then the scheduled tasks include:
    (pending "not yet implemented")))
