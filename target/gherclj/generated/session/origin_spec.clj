(ns session.origin-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Session origin"

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

  (it "cron-spawned session carries origin pointing at the cron"
    ;; given default Grover setup
    ;; given config:
    ;; given the following model responses are queued:
    ;; when the scheduler ticks at "2026-04-21T09:00:00-0500"
    ;; then the following sessions match:
    (pending "not yet implemented"))

  (it "CLI-spawned session carries origin :cli"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hello" "echo"]]})
    (isaac.foundation.cli-steps/isaac-run "prompt -m 'Hi'")
    (isaac.session.session-steps/sessions-match {:headers ["id" "origin.kind"], :rows [["prompt-default" "cli"]]})))
