(ns bridge.effort-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.session.session-steps :as session-steps]))

(describe "/effort Command"

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

  (it "/effort N sets session-level effort"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; when the user sends "/effort 5" on session "effort-test"
    ;; then the reply contains "effort set to 5"
    ;; then the following sessions match:
    (pending "not yet implemented"))

  (it "/effort shows the current effective effort"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; when the user sends "/effort" on session "effort-test"
    ;; then the reply contains "current effort: 7"
    (pending "not yet implemented"))

  (it "/effort clear removes the session-level override"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; given the session "effort-test" has effort 5
    ;; when the user sends "/effort clear" on session "effort-test"
    ;; then the reply contains "effort cleared"
    ;; then the following sessions match:
    (pending "not yet implemented"))

  (it "/effort with out-of-range value is rejected"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; when the user sends "/effort 11" on session "effort-test"
    ;; then the reply contains "effort must be between 0 and 10"
    ;; then the following sessions match:
    (pending "not yet implemented"))

  (it "/effort with non-numeric value is rejected"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; when the user sends "/effort high" on session "effort-test"
    ;; then the reply contains "effort must be between 0 and 10"
    ;; then the following sessions match:
    (pending "not yet implemented")))
