(ns session.compaction-strategies-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Compaction Strategies"

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

  (it "default compaction parameters are fixed percentages of context window"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/compaction-defaults {:headers ["context-window" "threshold" "head"], :rows [["100" "0.8" "0.3"] ["8192" "0.8" "0.3"] ["32768" "0.8" "0.3"] ["65536" "0.8" "0.3"] ["128000" "0.8" "0.3"] ["200000" "0.8" "0.3"] ["272000" "0.8" "0.3"] ["1048576" "0.8" "0.3"]]}))

  (it "rubberband compacts entire transcript when threshold exceeded"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "100"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens"], :rows [["rb-test" "95"]]})
    (isaac.session.session-steps/session-has-transcript "rb-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Tell me about compaction"] ["message" "assistant" "It summarizes old messages"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Summary of prior chat" "test-model"] ["text" "Here is my response" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "hello" "rb-test")
    (isaac.session.session-steps/session-transcript-count "rb-test" "6")
    (isaac.session.session-steps/session-active-transcript-count "rb-test" "3")
    (isaac.session.session-steps/session-transcript-matching "rb-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Tell me about compaction"] ["message" "assistant" "It summarizes old messages"]]})
    (isaac.session.session-steps/session-active-transcript-matching "rb-test" {:headers ["type" "message.role" "message.content" "summary"], :rows [["compaction" "" "" "Summary of prior chat"] ["message" "user" "hello" ""] ["message" "assistant" "Here is my response" ""]]}))

  (it "slinky folds the tail and preserves the head"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "200"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "compaction.strategy" "compaction.threshold" "compaction.head"], :rows [["slinky-test" "170" "slinky" "0.8" "0.4"]]})
    (isaac.session.session-steps/session-has-transcript "slinky-test" {:headers ["type" "message.role" "message.content" "tokens"], :rows [["message" "user" "old topic" "40"] ["message" "assistant" "old reply" "40"] ["message" "user" "recent topic" "40"] ["message" "assistant" "recent reply" "50"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Tail summary" "test-model"] ["text" "Fresh response" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "hello" "slinky-test")
    (isaac.session.session-steps/session-transcript-count "slinky-test" "8")
    (isaac.session.session-steps/session-active-transcript-count "slinky-test" "5")
    (isaac.session.session-steps/session-transcript-matching "slinky-test" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "old topic"] ["message" "assistant" "old reply"]]})
    (isaac.session.session-steps/session-active-transcript-matching "slinky-test" {:headers ["type" "message.role" "message.content" "summary"], :rows [["compaction" "" "" "Tail summary"] ["message" "user" "recent topic" ""] ["message" "assistant" "recent reply" ""] ["message" "user" "hello" ""] ["message" "assistant" "Fresh response" ""]]})))
