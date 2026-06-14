(ns session.compaction-logging-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.comm.comm-steps :as comm-steps]
            [isaac.config.config-steps :as config-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.llm.providers-steps :as providers-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Context Compaction Logging"

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

  (it "Chat logs the compaction trigger with provider and model context"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "#comment"], :rows [["compaction-chat" "95" "exceeds 90% of 100 window"]]})
    (isaac.session.session-steps/session-has-transcript "compaction-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Please summarize our work"] ["message" "assistant" "We discussed logging and tools"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary of prior chat" "test-model"] ["text" "README summary" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "Can you summarize README.md?" "compaction-chat")
    (isaac.comm.comm-steps/memory-channel-events-match {:headers ["event" "provider" "model" "total-tokens" "context-window"], :rows [["compaction-start" "grover" "test-model" "95" "100"]]}))

  (it "The new user message is preserved after compaction"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "#comment"], :rows [["compaction-chat" "95" "exceeds 90% of 100 window"]]})
    (isaac.session.session-steps/session-has-transcript "compaction-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Please summarize our work"] ["message" "assistant" "We discussed logging and tools"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary of prior chat" "test-model"] ["text" "README summary" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "Can you summarize README.md?" "compaction-chat")
    (isaac.session.session-steps/session-transcript-matching "compaction-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Please summarize our work"] ["message" "assistant" "We discussed logging and tools"]]})
    (isaac.session.session-steps/session-active-transcript-matching "compaction-chat" {:headers ["#index" "type" "summary" "message.role" "message.content"], :rows [["0" "compaction" "Summary of prior chat" "" ""] ["1" "message" "" "user" "Can you summarize README.md?"]]}))

  (it "Chat completes after compaction"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "#comment"], :rows [["compaction-chat" "95" "exceeds 90% of 100 window"]]})
    (isaac.session.session-steps/session-has-transcript "compaction-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Please summarize our work"] ["message" "assistant" "We discussed logging and tools"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary of prior chat" "test-model"] ["text" "README summary" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "Can you summarize README.md?" "compaction-chat")
    (isaac.session.session-steps/session-transcript-matching "compaction-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" "README summary"]]}))

  (it "Compaction failure is logged and chat proceeds without looping"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "#comment"], :rows [["failure-chat" "95" "exceeds 90% of 100 window"]]})
    (isaac.session.session-steps/session-has-transcript "failure-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Please summarize our work"] ["message" "assistant" "We discussed logging and tools"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["error" "context length exceeded" "test-model"] ["text" "Here is my answer" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "What was decided?" "failure-chat")
    (isaac.comm.comm-steps/memory-channel-events-match {:headers ["event" "error" "consecutive-failures"], :rows [["compaction-start" "" ""] ["compaction-failure" ":llm-error" "1"]]})
    (isaac.session.session-steps/session-transcript-matching "failure-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" "Here is my answer"]]}))

  (it "Compaction targets only the oldest messages when history exceeds the model context window"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "compaction.strategy" "compaction.threshold" "compaction.head" "#comment"], :rows [["partial-compact" "95" "slinky" "0.8" "0.5" "exceeds threshold"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "60"]]})
    (isaac.session.session-steps/session-has-transcript "partial-compact" {:headers ["type" "message.role" "message.content" "tokens"], :rows [["message" "user" "First question about the project status" "20"] ["message" "assistant" "The project status is healthy and on track" "20"] ["message" "user" "Second question about the upcoming release" "20"] ["message" "assistant" "The release is scheduled for the end of month" "20"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary of first exchange" "test-model"] ["text" "Third answer" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "Third question" "partial-compact")
    (isaac.session.session-steps/session-transcript-matching "partial-compact" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "First question about the project status"] ["message" "assistant" "The project status is healthy and on track"]]})
    (isaac.session.session-steps/session-active-transcript-matching "partial-compact" {:headers ["#index" "type" "message.role" "message.content" "summary"], :rows [["0" "compaction" "" "" "Summary of first exchange"] ["1" "message" "user" "Second question about the upcoming release" ""] ["2" "message" "assistant" "The release is scheduled for the end of month" ""] ["3" "message" "user" "Third question" ""] ["4" "message" "assistant" "Third answer" ""]]}))

  (it "Switching to a smaller-context model runs compaction repeatedly until chat can continue"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "#comment"], :rows [["model-switch" "200" "accumulated under large-window model"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/claude-long.edn" {:headers ["path" "value"], :rows [["model" "claude-opus-4-6"] ["provider" "grover"] ["context-window" "96"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3-coder.edn" {:headers ["path" "value"], :rows [["model" "qwen3-coder:30b"] ["provider" "grover"] ["context-window" "20"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "qwen3-coder"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/session-has-transcript "model-switch" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Earlier planning notes from the large-window model."] ["message" "assistant" "Earlier planning summary from the large-window model."] ["message" "user" "Recent facts: release tracking, migration status, provider changes, logging updates, tool execution details, deployment concerns, rollback notes, and monitoring issues remain active after the downgrade."] ["message" "assistant" "Recent answer: release tracking, migration status, provider changes, logging updates, tool execution details, deployment concerns, rollback notes, and monitoring issues remain important after the downgrade."]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary from first compact" "qwen3-coder:30b"] ["text" "Summary from second compact" "qwen3-coder:30b"] ["text" "Final response after shrink" "qwen3-coder:30b"]]})
    (isaac.session.session-steps/user-sends-on-session "Continue after model switch" "model-switch")
    (isaac.session.session-steps/sessions-match {:headers ["id" "compaction-count"], :rows [["model-switch" "2"]]})
    (isaac.session.session-steps/session-transcript-matching "model-switch" {:headers ["type" "summary"], :rows [["compaction" "Summary from first compact"] ["compaction" "Summary from second compact"]]})
    (isaac.session.session-steps/session-transcript-matching "model-switch" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" "Final response after shrink"]]}))

  (it "Successful compaction does not immediately re-trigger on the next user turn"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "input-tokens" "output-tokens" "total-tokens" "#comment"], :rows [["rebound-test" "120" "30" "150" "stale accumulators cause rebound"]]})
    (isaac.session.session-steps/session-has-transcript "rebound-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Please summarize our previous context before we continue."] ["message" "assistant" "We covered tools, logs, and pending compaction fixes."]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary after compaction" "test-model"] ["text" "First reply after compaction" "test-model"] ["text" "Second reply without re-compacts" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "Hello?" "rebound-test")
    (isaac.session.session-steps/user-sends-on-session "You there?" "rebound-test")
    (isaac.session.session-steps/sessions-match {:headers ["id" "compaction-count"], :rows [["rebound-test" "1"]]})
    (isaac.session.session-steps/session-transcript-matching "rebound-test" {:headers ["type" "message.role" "message.content" "summary"], :rows [["compaction" "" "" "Summary after compaction"] ["message" "user" "Hello?" ""] ["message" "assistant" "First reply after compaction" ""] ["message" "user" "You there?" ""] ["message" "assistant" "Second reply without re-compacts" ""]]}))

  (it "compaction succeeds and chat continues when the head exceeds the context window"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["enforce-context-window" "true"] ["context-window" "600"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens"], :rows [["huge-head" "620"]]})
    (isaac.session.session-steps/session-has-transcript "huge-head" {:headers ["type" "message.role" "message.content" "tokens"], :rows [["message" "user" "block A (oldest)" "60"] ["message" "assistant" "reply A" "60"] ["message" "user" "block B" "60"] ["message" "assistant" "reply B" "60"] ["message" "user" "latest question" "61"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "summary of A" "test-model"] ["text" "here is my answer" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "go" "huge-head")
    (isaac.comm.comm-steps/memory-channel-events-match {:headers ["event" "summary"], :rows [["compaction-start" ""] ["compaction-success" "summary of A"]]})
    (isaac.session.session-steps/session-transcript-matching "huge-head" {:headers ["type" "message.role" "message.content" "summary"], :rows [["compaction" "" "" "summary of A"] ["message" "assistant" "here is my answer" ""]]}))

  (it "compaction stops retrying after max-compaction-attempts consecutive cross-turn failures"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "60"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "compaction.consecutive-failures"], :rows [["giving-up" "95" "5"]]})
    (isaac.session.session-steps/session-has-transcript "giving-up" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "earlier prompt"] ["message" "assistant" "earlier reply"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["error" "context length exceeded" "test-model"] ["text" "here is my answer" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "next thing" "giving-up")
    (isaac.comm.comm-steps/memory-channel-events-match {:headers ["event" "error" "consecutive-failures" "reason"], :rows [["compaction-failure" ":llm-error" "6" ""] ["compaction-disabled" "" "" ":too-many-failures"]]})
    (isaac.session.session-steps/session-matches "giving-up" {:headers ["key" "value"], :rows [["compaction-disabled" "true"]]})
    (isaac.session.session-steps/session-transcript-matching "giving-up" {:headers ["type" "message.role" "message.content"], :rows [["message" "assistant" "here is my answer"]]}))

  (it "switching model clears compaction-disabled and lets the next turn retry"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/bigger-model.edn" {:headers ["path" "value"], :rows [["model" "bigger-model-upstream"] ["provider" "grover"] ["context-window" "200"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "model" "compaction-disabled" "compaction.consecutive-failures"], :rows [["recovered" "test-model" "true" "5"]]})
    (isaac.tool.tools-steps/current-session-is "recovered")
    (isaac.tool.tools-steps/tool-called "session_model" {:headers ["model" "bigger-model"], :rows []})
    (isaac.session.session-steps/session-matches "recovered" {:headers ["key" "value"], :rows [["compaction-disabled" "false"] ["compaction.consecutive-failures" "0"]]}))

  (it "compaction passes the session's provider through so tool format matches"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/codex.edn" {:headers ["path" "value"], :rows [["model" "gpt-5.4"] ["provider" "grover:chatgpt"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "codex"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "model"], :rows [["codex-compact" "95" "codex"]]})
    (isaac.session.session-steps/session-has-transcript "codex-compact" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "older prompt"] ["message" "assistant" "older reply"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary of prior" "gpt-5.4"] ["text" "here is my answer" "gpt-5.4"]]})
    (isaac.session.session-steps/user-sends-on-session "next" "codex-compact")
    (isaac.llm.providers-steps/outbound-http-request-matches {:headers ["key" "value"], :rows [["#index" "0"] ["body.tools[0].type" "function"] ["body.tools[0].name" "memory_get"] ["body.tools[2].name" "memory_write"]]})
    (isaac.llm.providers-steps/provider-request-lacks-path "body.tools[0].function"))

  (it "Compaction keeps toolCall and toolResult together"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "compaction.strategy" "compaction.threshold" "compaction.head" "#comment"], :rows [["tool-orphan" "95" "slinky" "0.9" "0.15" "tail=15 splits between toolResult & last asst"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/session-has-transcript "tool-orphan" {:headers ["type" "message.role" "message.id" "message.content" "tokens" "#comment"], :rows [["message" "user" "" "What's in fridge.txt?" "20" "head of compactables"] ["message" "assistant" "" "[{\"type\":\"toolCall\",\"id\":\"call_old\",\"name\":\"read\",\"arguments\":{\"filePath\":\"fridge.txt\"}}]" "5" "bug: ->compact-message returns nil here"] ["message" "toolResult" "call_old" "one sad lemon" "15" "enters compactables; will be in compacted-ids"] ["message" "assistant" "" "The fridge has a lemon." "20" "tail=15: this 20-token reply is firstKept"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "#comment"], :rows [["text" "Summary of fridge" "test-model" "compaction LLM response"] ["text" "next answer" "test-model" "reply to the new user message"]]})
    (isaac.session.session-steps/user-sends-on-session "And the freezer?" "tool-orphan")
    (isaac.session.session-steps/last-compaction-request-input-contains "call_old")
    (isaac.session.session-steps/session-transcript-matching "tool-orphan" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "What's in fridge.txt?"] ["message" "toolResult" "one sad lemon"]]})
    (isaac.session.session-steps/session-active-transcript-matching "tool-orphan" {:headers ["#index" "type" "summary" "message.role" "message.content" "#comment"], :rows [["0" "compaction" "Summary of fridge" "" "" "both tc & tr summarized away as a pair"] ["1" "message" "" "assistant" "The fridge has a lemon." "firstKept survives"] ["2" "message" "" "user" "And the freezer?" "new turn input"] ["3" "message" "" "assistant" "next answer" "new turn reply"]]}))

  (it "Crew compaction config with unknown :strategy is rejected"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac\"")
    (isaac.foundation.fs-steps/isaac-file-exists-with-content "isaac.edn" "{:crew {:main {:compaction {:strategy :rainbow :threshold 100000}}}}")
    (isaac.config.config-steps/config-is-loaded)
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["crew.main.compaction.strategy" "must be one of.*"]]})))
