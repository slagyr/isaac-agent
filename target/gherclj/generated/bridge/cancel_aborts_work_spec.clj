(ns bridge.cancel-aborts-work-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Cancel Aborts In-Flight Turn Work"

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

  (it "cancel between tool-loop iterations skips the next chat call"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/crew-tool-allow "main" "exec")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["cancel-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool_call" "arguments" "content"], :rows [["tool_call" "exec" "{\"command\": \"sleep 0.05\"}" ""] ["text" "" "" "Should never appear"]]})
    (isaac.session.session-steps/user-sends-on-session "do stuff" "cancel-test")
    (isaac.session.session-steps/turn-cancelled-after-n-tool-calls "cancel-test" 1)
    (isaac.session.session-steps/turn-result-is "\"cancelled\"")
    (isaac.session.session-steps/session-transcript-not-matching "cancel-test" {:headers ["type" "message.content"], :rows [["message" "Should never appear"]]}))

  (it "session remains usable after a cancel mid-loop"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/crew-tool-allow "main" "exec")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["cancel-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool_call" "arguments" "content"], :rows [["tool_call" "exec" "{\"command\": \"sleep 0.05\"}" ""] ["text" "" "" "Should never appear"]]})
    (isaac.session.session-steps/user-sends-on-session "do stuff" "cancel-test")
    (isaac.session.session-steps/turn-cancelled-after-n-tool-calls "cancel-test" 1)
    (isaac.session.session-steps/turn-result-is "\"cancelled\"")
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Still here!" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "you ok?" "cancel-test")
    (isaac.session.session-steps/session-transcript-matching "cancel-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" "Still here!"]]})))
