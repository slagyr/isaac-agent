(ns config.component-hot-reload-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.session.session-steps :as session-steps]))

(describe "Hot reload of config-driven components"

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

  (it "Hook template content change is picked up at runtime"
    ;; given default Grover setup
    ;; given config:
    ;; and the Isaac server is started
    ;; and the hook "cage-check" registry entry has:
    ;; when config is updated:
    ;; then the hook "cage-check" registry entry has:
    (pending "not yet implemented"))

  (it "Cron prompt content change is picked up at runtime"
    ;; given default Grover setup
    ;; given config:
    ;; and the Isaac server is started
    ;; and the cron job "evening-plan" has:
    ;; when config is updated:
    ;; then the cron job "evening-plan" has:
    (pending "not yet implemented")))
