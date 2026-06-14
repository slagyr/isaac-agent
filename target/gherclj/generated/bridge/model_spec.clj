(ns bridge.model-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "/model Command"

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

  (it "/model switches the session's model"
    ;; given default Grover setup
    ;; given the isaac EDN file "config/models/grover2.edn" exists with:
    ;; given the isaac EDN file "config/models/grok.edn" exists with:
    ;; given the isaac file "config/providers/grok.edn" exists with:
    ;; given the following sessions exist:
    ;; when the user sends "/model grok" on session "model-test"
    ;; then the reply contains "switched model to grok (grok/grok-4-1-fast)"
    ;; then the following sessions match:
    (pending "not yet implemented"))

  (it "/model persists across turns"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover2.edn" {:headers ["path" "value"], :rows [["model" "echo-alt"] ["provider" "grover"] ["context-window" "16384"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grok.edn" {:headers ["path" "value"], :rows [["model" "grok-4-1-fast"] ["provider" "grok"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "config/providers/grok.edn" "{}")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["model-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hello" "echo-alt"] ["text" "World" "echo-alt"]]})
    (isaac.session.session-steps/user-sends-on-session "/model grover2" "model-test")
    (isaac.session.session-steps/user-sends-on-session "hi" "model-test")
    (isaac.session.session-steps/user-sends-on-session "bye" "model-test")
    (isaac.session.session-steps/session-transcript-matching "model-test" {:headers ["type" "message.role" "message.model"], :rows [["message" "assistant" "echo-alt"] ["message" "assistant" "echo-alt"]]}))

  (it "/model with no argument shows the current model"
    ;; given default Grover setup
    ;; given the isaac EDN file "config/models/grover2.edn" exists with:
    ;; given the isaac EDN file "config/models/grok.edn" exists with:
    ;; given the isaac file "config/providers/grok.edn" exists with:
    ;; given the following sessions exist:
    ;; when the user sends "/model" on session "model-test"
    ;; then the reply contains "grover (grover/echo) is the current model"
    (pending "not yet implemented"))

  (it "/model with unknown alias shows an error"
    ;; given default Grover setup
    ;; given the isaac EDN file "config/models/grover2.edn" exists with:
    ;; given the isaac EDN file "config/models/grok.edn" exists with:
    ;; given the isaac file "config/providers/grok.edn" exists with:
    ;; given the following sessions exist:
    ;; when the user sends "/model nonexistent" on session "model-test"
    ;; then the reply contains "unknown model: nonexistent"
    (pending "not yet implemented")))
