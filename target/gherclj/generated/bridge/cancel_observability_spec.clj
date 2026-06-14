(ns bridge.cancel-observability-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.log-steps :as log-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Cancel-path observability"

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

  (it "cancel applied to a known session logs at info with a hook count"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["cancel-test"]]})
    (isaac.session.session-steps/llm-response-delayed "30")
    (isaac.session.session-steps/user-sends-on-session "think hard" "cancel-test")
    (isaac.session.session-steps/turn-cancelled "cancel-test")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "session" "hooks"], :rows [[":info" ":bridge/cancel-applied" "cancel-test" "#\"\\d+\""]]}))

  (it "cancel with no in-flight turn emits :bridge/cancel-noop"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["cancel-test"]]})
    (isaac.session.session-steps/turn-cancelled "cancel-test")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "session"], :rows [[":info" ":bridge/cancel-noop" "cancel-test"]]})))
