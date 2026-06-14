(ns comm.memory-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.comm.comm-steps :as comm-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Memory Comm"

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

  (it "Text response is recorded as a single chunk"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["memory-chat"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Four, I think" "echo"]]})
    (isaac.comm.comm-steps/user-sends-via-memory-channel "What is 2+2?" "memory-chat")
    (isaac.comm.comm-steps/memory-channel-events-match {:headers ["event" "text"], :rows [["turn-start" ""] ["text-chunk" "Four, I think"] ["turn-end" ""]]}))

  (it "Streaming chunks are recorded into the memory-channel turn result"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["memory-chat"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "[\"chunkA\" \"chunkB\" \"chunkC\"]" "echo"]]})
    (isaac.comm.comm-steps/user-sends-via-memory-channel "Tell me a story" "memory-chat")
    (isaac.session.session-steps/session-transcript-matching "memory-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" "chunkAchunkBchunkC"]]}))

  (it "Tool calls are recorded as lifecycle events"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["memory-chat"]]})
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/responses-queued {:headers ["tool_call" "arguments"], :rows [["exec" "{\"command\": \"echo hi\"}"]]})
    (isaac.comm.comm-steps/user-sends-via-memory-channel "Run echo" "memory-chat")
    (isaac.comm.comm-steps/memory-channel-events-match {:headers ["event" "tool-name"], :rows [["turn-start" ""] ["tool-call" "exec"] ["tool-result" "exec"] ["turn-end" ""]]}))

  (it "Compaction triggers during a memory comm turn"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["memory-chat"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "#comment"], :rows [["memory-chat" "30000" "exceeds 90% of 32768"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary of prior chat" "echo"] ["text" "Here is my answer" "echo"]]})
    (isaac.comm.comm-steps/user-sends-via-memory-channel "Continue" "memory-chat")
    (isaac.session.session-steps/session-transcript-matching "memory-chat" {:headers ["type"], :rows [["compaction"]]})))
