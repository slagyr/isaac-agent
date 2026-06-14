(ns session.context-management-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Context Management"

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

  (it "Token usage is tracked per session"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["context-track"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Here is my response" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "Hello" "context-track")
    (isaac.session.session-steps/sessions-match {:headers ["id" "input-tokens" "output-tokens" "total-tokens"], :rows [["context-track" "#\"\\d+\"" "#\"\\d+\"" "#\"\\d+\""]]}))

  (it "Compaction triggers at 90% context usage"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "last-input-tokens" "#comment"], :rows [["context-compact" "95" "exceeds 90% of 100 window"]]})
    (isaac.session.session-steps/session-has-transcript "context-compact" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Please summarize our work"] ["message" "assistant" "We discussed logging and tools"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary of prior chat" "echo"] ["text" "Here is my answer" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "What was decided?" "context-compact")
    (isaac.session.session-steps/session-transcript-matching "context-compact" {:headers ["type"], :rows [["compaction"]]}))

  (it "Cumulative billing across many small turns does not trigger compaction"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "last-input-tokens" "#comment"], :rows [["context-cheap" "5000" "30" "cheap turns, small prompt"]]})
    (isaac.session.session-steps/session-has-transcript "context-cheap" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "hi"] ["message" "assistant" "hello"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "sure" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "again?" "context-cheap")
    (isaac.session.session-steps/session-transcript-not-matching "context-cheap" {:headers ["type"], :rows [["compaction"]]}))

  (it "Conversation is compacted into a summary"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "last-input-tokens" "#comment"], :rows [["context-summary" "95" "exceeds 90% of 100 window"]]})
    (isaac.session.session-steps/session-has-transcript "context-summary" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "What is Clojure?"] ["message" "assistant" "A functional Lisp on JVM..."] ["message" "user" "What about Babashka?"] ["message" "assistant" "A fast Clojure scripting..."]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary of prior chat" "echo"] ["text" "Here is my answer" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "Continue" "context-summary")
    (isaac.session.session-steps/session-transcript-matching "context-summary" {:headers ["type" "summary"], :rows [["compaction" "#\".{10,}\""]]})
    (isaac.session.session-steps/sessions-match {:headers ["id" "compaction-count"], :rows [["context-summary" "1"]]}))

  (it "Compaction summarizer receives truncated tool results"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "last-input-tokens"], :rows [["context-summarize" "95"]]})
    (isaac.session.session-steps/session-has-transcript "context-summarize" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Read the big file"] ["toolCall" "" ""] ["toolResult" "" "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "brief summary" "echo"] ["text" "follow-up" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "go on" "context-summarize")
    (isaac.session.session-steps/compaction-request-matches {:headers ["key" "value"], :rows [["messages[1].content" "#\"(?s).*AAAA.*truncated.*ZZZZ.*\""]]}))

  (it "last-input-tokens is updated from response usage on every turn"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "last-input-tokens"], :rows [["context-progress" "10"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "usage.input_tokens"], :rows [["text" "ok" "echo" "42"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "context-progress")
    (isaac.session.session-steps/sessions-match {:headers ["id" "last-input-tokens"], :rows [["context-progress" "42"]]}))

  (it "Assistant response persists per-entry token count"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["context-entry"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "usage.input_tokens" "usage.output_tokens"], :rows [["text" "hello" "echo" "30" "5"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "context-entry")
    (isaac.session.session-steps/session-transcript-matching "context-entry" {:headers ["type" "message.role" "message.content" "tokens"], :rows [["message" "user" "hi" ""] ["message" "assistant" "hello" "35"]]}))

  (it "Assistant response persists usage breakdown in transcript entry"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["usage-persist"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "usage.input_tokens" "usage.output_tokens"], :rows [["text" "hello" "echo" "30" "5"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "usage-persist")
    (isaac.session.session-steps/session-transcript-matching "usage-persist" {:headers ["type" "message.role" "message.usage.input-tokens" "message.usage.output-tokens"], :rows [["message" "assistant" "30" "5"]]}))

  (it "Assistant response persists reasoning on transcript entry"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["reasoning-persist"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "reasoning.effort" "reasoning.summary"], :rows [["text" "done" "echo" "high" "Thought about it"]]})
    (isaac.session.session-steps/user-sends-on-session "think" "reasoning-persist")
    (isaac.session.session-steps/session-transcript-matching "reasoning-persist" {:headers ["type" "message.role" "message.reasoning.effort" "message.reasoning.summary"], :rows [["message" "assistant" "high" "Thought about it"]]}))

  (it "Large tool results are truncated in prompts"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["context-truncate"]]})
    (isaac.session.session-steps/session-has-transcript "context-truncate" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Read the big file"] ["toolCall" "" ""] ["toolResult" "" "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ"]]})
    (isaac.session.session-steps/prompt-on-session-matches "What does it say?" "context-truncate" {:headers ["key" "value"], :rows [["messages[1].content" "#\"AAAA.*truncated.*ZZZZ\""]]})))
