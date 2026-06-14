(ns session.compaction-memory-flush-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Compaction with memory flush"

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

  (it "compaction-turn memory_write calls persist and the summary is produced"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.tool.tools-steps/current-time-is "2026-04-21T10:00:00Z")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens"], :rows [["mundane-chat" "95"]]})
    (isaac.session.session-steps/session-has-transcript "mundane-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "I take tea with two sugars."] ["message" "assistant" "Noted — tea with two sugars it is."]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool" "arguments" "content" "model"], :rows [["tool_call" "memory_write" "{\"content\": \"User prefers tea with two sugars.\"}" "" "test-model"] ["text" "" "" "Discussion about tea preferences." "test-model"] ["text" "" "" "Here is my response." "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "hello" "mundane-chat")
    (isaac.session.session-steps/then-file-contains "crew/main/memory/2026-04-21.md" "User prefers tea with two sugars.")
    (isaac.session.session-steps/session-transcript-matching "mundane-chat" {:headers ["type" "message.role" "message.content" "summary"], :rows [["compaction" "" "" "Discussion about tea preferences."] ["message" "user" "hello" ""] ["message" "assistant" "Here is my response." ""]]}))

  (it "compaction turn with no memory calls still produces a summary"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.tool.tools-steps/current-time-is "2026-04-21T10:00:00Z")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens"], :rows [["quiet-day" "95"]]})
    (isaac.session.session-steps/session-has-transcript "quiet-day" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "What is the weather doing?"] ["message" "assistant" "Sunny, 72 degrees, light breeze."]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Quick chat about today's weather." "test-model"] ["text" "Here is my response." "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "hello" "quiet-day")
    (isaac.session.session-steps/session-transcript-matching "quiet-day" {:headers ["type" "summary"], :rows [["compaction" "Quick chat about today's weather."]]})))
