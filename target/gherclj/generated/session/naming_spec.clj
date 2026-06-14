(ns session.naming-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Session naming strategy"

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

  (it "sequential strategy names unnamed sessions session-1, session-2, ..."
    ;; given an Isaac root at "isaac-state"
    ;; given config:
    ;; when a session is created without a name
    ;; when a session is created without a name
    ;; then session "session-1" exists
    ;; then session "session-2" exists
    (pending "not yet implemented"))

  (it "an explicit name wins over the configured strategy"
    ;; given an Isaac root at "isaac-state"
    ;; given config:
    ;; when a session is created named "friday-debug"
    ;; then session "friday-debug" exists
    ;; then session "session-1" does not exist
    (pending "not yet implemented")))
