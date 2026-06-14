(ns bridge.cancel-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Turn Cancellation"

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

  (it "cancel interrupts a running exec tool call"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/crew-tool-allow "main" "exec")
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["cancel-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["tool_call" "arguments"], :rows [["exec" "{\"command\": \"sleep 30\"}"]]})
    (isaac.session.session-steps/user-sends-on-session "run it" "cancel-test")
    (isaac.session.session-steps/turn-cancelled "cancel-test")
    (isaac.session.session-steps/turn-result-is "\"cancelled\""))

  (it "cancel interrupts a running LLM request"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/crew-tool-allow "main" "exec")
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["cancel-test"]]})
    (isaac.session.session-steps/llm-response-delayed "30")
    (isaac.session.session-steps/user-sends-on-session "think hard" "cancel-test")
    (isaac.session.session-steps/turn-cancelled "cancel-test")
    (isaac.session.session-steps/turn-result-is "\"cancelled\""))

  (it "session remains usable after cancel"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/crew-tool-allow "main" "exec")
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["cancel-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["tool_call" "arguments"], :rows [["exec" "{\"command\": \"sleep 30\"}"]]})
    (isaac.session.session-steps/user-sends-on-session "run it" "cancel-test")
    (isaac.session.session-steps/turn-cancelled "cancel-test")
    (isaac.session.session-steps/turn-result-is "\"cancelled\"")
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Still here!" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "you ok?" "cancel-test")
    (isaac.session.session-steps/session-transcript-matching "cancel-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" "Still here!"]]})))
