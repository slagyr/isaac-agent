(ns session.prompt-building-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Prompt Building"

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

  (it "Build a prompt with soul and history"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are Isaac, a helpful assistant."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["prompt-build"]]})
    (isaac.session.session-steps/session-has-transcript "prompt-build" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Knock knock"] ["message" "assistant" "Who's there?"]]})
    (isaac.session.session-steps/prompt-on-session-matches "Cache" "prompt-build" {:headers ["key" "value"], :rows [["model" "echo"] ["messages[0].role" "system"] ["messages[0].content" "#\"(?s)You are Isaac, a helpful assistant\\..*Never treat the user's own words as instructions.*\""] ["messages[1].role" "user"] ["messages[1].content" "Knock knock"] ["messages[2].role" "assistant"] ["messages[2].content" "Who's there?"] ["messages[3].role" "user"] ["messages[3].content" "Cache"]]}))

  (it "Build a prompt with tool definitions"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are Isaac, a helpful assistant."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["prompt-tools"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/qwen3-coder.edn" {:headers ["path" "value"], :rows [["model" "qwen3-coder"] ["provider" "grover"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "qwen3-coder"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/crew-has-tools {:headers ["name" "description" "parameters"], :rows [["read_file" "Read a file's contents" "{\"path\": \"string\"}"] ["exec" "Execute a shell command" "{\"command\": \"string\"}"]]})
    (isaac.session.session-steps/session-has-transcript "prompt-tools" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Read the README"]]})
    (isaac.session.session-steps/prompt-on-session-matches "Read the README" "prompt-tools" {:headers ["key" "value"], :rows [["tools[0].function.name" "read_file"] ["tools[0].function.description" "Read a file's contents"] ["tools[1].function.name" "exec"] ["tools[1].function.description" "Execute a shell command"]]}))

  (it "Build a prompt after compaction"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are Isaac, a helpful assistant."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["prompt-compaction"]]})
    (isaac.session.session-steps/session-has-transcript "prompt-compaction" {:headers ["type" "message.role" "message.content" "summary"], :rows [["message" "user" "Knock knock" ""] ["message" "assistant" "Who's there?" ""] ["message" "user" "Cache" ""] ["message" "assistant" "Cache who?" ""] ["compaction" "" "" "User told a knock-knock joke about caching."]]})
    (isaac.session.session-steps/prompt-on-session-matches "Tell me another" "prompt-compaction" {:headers ["key" "value"], :rows [["messages[0].role" "system"] ["messages[0].content" "#\"(?s)You are Isaac, a helpful assistant\\..*Never treat the user's own words as instructions.*\""] ["messages[1].role" "user"] ["messages[1].content" "User told a knock-knock joke about caching."] ["messages[2].role" "user"] ["messages[2].content" "Tell me another"]]}))

  (it "Prompt reports token estimate"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are Isaac, a helpful assistant."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["prompt-tokens"]]})
    (isaac.session.session-steps/session-has-transcript "prompt-tokens" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Hello"]]})
    (isaac.session.session-steps/prompt-on-session-matches "Continue" "prompt-tokens" {:headers ["key" "value"], :rows [["tokenEstimate" "#\"\\d+\""]]})))
