(ns session.storage-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.log-steps :as log-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Session Storage"

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

  (it "Create a new session"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["first-chat"]]})
    (isaac.session.session-steps/session-count-is "1")
    (isaac.session.session-steps/sessions-match {:headers ["id" "file" "compaction-count" "input-tokens" "output-tokens" "total-tokens"], :rows [["first-chat" "#\".+\\.jsonl\"" "0" "0" "0" "0"]]})
    (isaac.session.session-steps/session-transcript-count "first-chat" "1")
    (isaac.session.session-steps/session-transcript-matching "first-chat" {:headers ["type" "id" "timestamp"], :rows [["session" "#\"[a-f0-9]{8}\"" "#\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\""]]})
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "sessionId"], :rows [[":info" ":session/created" "first-chat"]]}))

  (it "List sessions"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["chat-1"] ["chat-2"]]})
    (isaac.session.session-steps/session-count-is "2")
    (isaac.session.session-steps/sessions-match {:headers ["id" "updated-at"], :rows [["chat-1" "#\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\""] ["chat-2" "#\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\""]]}))

  (it "Opening an existing session resumes it"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["first-chat"]]})
    (isaac.session.session-steps/session-has-transcript "first-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Hello"]]})
    (isaac.session.session-steps/session-opened "\"first-chat\"")
    (isaac.session.session-steps/session-count-is "1")
    (isaac.session.session-steps/session-transcript-count "first-chat" "2")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "sessionId"], :rows [[":info" ":session/opened" "first-chat"]]}))

  (it "Resume an existing session"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["first-chat"]]})
    (isaac.session.session-steps/session-has-transcript "first-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Hello"] ["message" "assistant" "Hi there"] ["message" "user" "How are you?"]]})
    (isaac.session.session-steps/session-transcript-count "first-chat" "4")
    (isaac.session.session-steps/session-count-is "1"))

  (it "Append a user message"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["first-chat"]]})
    (isaac.session.session-steps/entries-appended "first-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Hello"]]})
    (isaac.session.session-steps/session-transcript-count "first-chat" "2")
    (isaac.session.session-steps/session-transcript-matching "first-chat" {:headers ["#index" "type" "message.role" "message.content"], :rows [["1" "message" "user" "Hello"]]}))

  (it "Append an assistant message"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["first-chat"]]})
    (isaac.session.session-steps/session-has-transcript "first-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Hello"]]})
    (isaac.session.session-steps/entries-appended "first-chat" {:headers ["type" "message.role" "message.content" "message.model" "message.provider"], :rows [["message" "assistant" "Hi there" "qwen3-coder" "ollama"]]})
    (isaac.session.session-steps/session-transcript-count "first-chat" "3")
    (isaac.session.session-steps/session-transcript-matching "first-chat" {:headers ["#index" "type" "message.role" "message.content" "message.model" "message.provider"], :rows [["2" "message" "assistant" "Hi there" "qwen3-coder" "ollama"]]}))

  (it "Append a tool call and result"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["tool-chat"]]})
    (isaac.session.session-steps/session-has-transcript "tool-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Read the README"]]})
    (isaac.session.session-steps/entries-appended "tool-chat" {:headers ["type" "name" "id" "arguments" "message.content" "isError"], :rows [["toolCall" "read_file" "call_123" "{\"path\": \"README\"}" "" ""] ["toolResult" "" "call_123" "" "# Isaac\\nA CLI..." "false"]]})
    (isaac.session.session-steps/session-transcript-matching "tool-chat" {:headers ["type" "message.role" "message.content[0].type" "message.content[0].name"], :rows [["message" "assistant" "toolCall" "read_file"]]})
    (isaac.session.session-steps/session-transcript-matching "tool-chat" {:headers ["type" "message.role" "message.toolCallId"], :rows [["message" "toolResult" "call_123"]]}))

  (it "Compaction splice keeps tool calls paired by toolCallId"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["tool-chat"]]})
    (isaac.session.session-steps/session-has-transcript "tool-chat" {:headers ["type" "message.role" "message.content" "id" "name" "arguments"], :rows [["message" "user" "Earlier question" "" "" ""] ["toolCall" "" "" "call_123" "read_file" "{\"path\": \"README\"}"] ["toolResult" "" "one sad lemon" "call_123" "" ""] ["message" "assistant" "The fridge has a lemon." "" "" ""]]})
    (isaac.session.session-steps/compaction-spliced-into-session "tool-chat" {:headers ["key" "value"], :rows [["summary" "Summary of earlier work"] ["firstKeptIndex" "2"] ["compactedIndexes" "[1]"] ["tokensBefore" "20"]]})
    (isaac.session.session-steps/session-transcript-matching "tool-chat" {:headers ["type" "summary"], :rows [["compaction" "Summary of earlier work"]]})
    (isaac.session.session-steps/session-transcript-matching "tool-chat" {:headers ["type" "message.role" "message.content[0].type" "message.content[0].id"], :rows [["message" "assistant" "toolCall" "call_123"]]})
    (isaac.session.session-steps/session-transcript-matching "tool-chat" {:headers ["type" "message.role" "message.toolCallId" "message.content"], :rows [["message" "toolResult" "call_123" "one sad lemon"]]}))

  (it "Entries form a linked chain via parentId"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["chain-test"]]})
    (isaac.session.session-steps/entries-appended "chain-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Hello"] ["message" "assistant" "Hi there"] ["message" "user" "How are you?"]]})
    (isaac.session.session-steps/session-transcript-matching "chain-test" {:headers ["#index" "id" "parentId"], :rows [["0" "#\"[a-f0-9]{8}\":header" ""] ["1" "#\"[a-f0-9]{8}\":msg1" "#header"] ["2" "#\"[a-f0-9]{8}\":msg2" "#msg1"] ["3" "#\"[a-f0-9]{8}\":msg3" "#msg2"]]}))

  (it "Index is updated on each append"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["index-test"]]})
    (isaac.session.session-steps/entries-appended "index-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Hello"]]})
    (isaac.session.session-steps/sessions-match {:headers ["id" "updated-at"], :rows [["index-test" "#\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\""]]}))

  (it "Session header includes version and working directory"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["header-test"]]})
    (isaac.session.session-steps/session-transcript-matching "header-test" {:headers ["#index" "type" "version" "cwd"], :rows [["0" "session" "3" "#\".+\""]]}))

  (it "Entry IDs are 8-character hex strings"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["id-test"]]})
    (isaac.session.session-steps/entries-appended "id-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Hello"]]})
    (isaac.session.session-steps/session-transcript-matching "id-test" {:headers ["#index" "id"], :rows [["0" "#\"[a-f0-9]{8}\""] ["1" "#\"[a-f0-9]{8}\""]]}))

  (it "Timestamps use ISO 8601 format"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["ts-test"]]})
    (isaac.session.session-steps/entries-appended "ts-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Hello"]]})
    (isaac.session.session-steps/session-transcript-matching "ts-test" {:headers ["#index" "timestamp"], :rows [["0" "#\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\""] ["1" "#\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\""]]})
    (isaac.session.session-steps/sessions-match {:headers ["id" "updated-at"], :rows [["ts-test" "#\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\""]]}))

  (it "Session sidecars are keyed by session id"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/use-file-session-store)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["chat-1"] ["chat-2"]]})
    (isaac.session.session-steps/session-sidecars-exist-for {:headers ["id"], :rows [["chat-1"] ["chat-2"]]}))

  (it "Message content stored as block arrays"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["block-test"]]})
    (isaac.session.session-steps/entries-appended "block-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Hello"]]})
    (isaac.session.session-steps/session-transcript-matching "block-test" {:headers ["#index" "message.content[0].type" "message.content[0].text"], :rows [["1" "text" "Hello"]]}))

  (it "Assistant messages include per-turn usage metadata"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["usage-test"]]})
    (isaac.session.session-steps/entries-appended "usage-test" {:headers ["type" "message.role" "message.content" "message.model" "message.provider" "message.api" "message.usage.input" "message.usage.output" "message.stopReason"], :rows [["message" "assistant" "Hi there" "qwen3-coder" "ollama" "ollama" "100" "25" "stop"]]})
    (isaac.session.session-steps/session-transcript-matching "usage-test" {:headers ["type" "message.role" "message.usage.input" "message.usage.output" "message.stopReason" "message.api"], :rows [["message" "assistant" "100" "25" "stop" "ollama"]]})))
