(ns session.async-compaction-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Async Compaction"

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

  (it "async slinky compaction does not block the turn"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "200"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "compaction.strategy" "compaction.threshold" "compaction.head" "compaction.async"], :rows [["async-test" "170" "slinky" "0.8" "0.4" "true"]]})
    (isaac.session.session-steps/session-has-transcript "async-test" {:headers ["type" "message.role" "message.content" "tokens"], :rows [["message" "user" "old topic" "40"] ["message" "assistant" "old reply" "40"] ["message" "user" "recent topic" "40"] ["message" "assistant" "recent reply" "50"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Tail summary" "test-model"] ["text" "Fresh response" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "hello" "async-test")
    (isaac.session.session-steps/async-compaction-in-flight "async-test")
    (isaac.session.session-steps/session-transcript-matching "async-test" {:headers ["type" "message.role" "message.content" "#comment"], :rows [["message" "user" "old topic" "still here — compaction hasn't spliced yet"] ["message" "assistant" "old reply" ""] ["message" "user" "recent topic" ""] ["message" "assistant" "recent reply" ""] ["message" "user" "hello" "turn completed without waiting for compaction"] ["message" "assistant" "Fresh response" ""]]})
    (isaac.session.session-steps/async-compaction-completes "async-test")
    (isaac.session.session-steps/session-transcript-matching "async-test" {:headers ["type" "message.role" "message.content" "#comment"], :rows [["compaction" "" "Tail summary" "old tail replaced with summary"] ["message" "user" "recent topic" "head preserved"] ["message" "assistant" "recent reply" ""] ["message" "user" "hello" "turn entries survived splice"] ["message" "assistant" "Fresh response" ""]]}))

  (it "second turn skips compaction when one is already in-flight"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "200"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Atticus."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "compaction.strategy" "compaction.threshold" "compaction.head" "compaction.async"], :rows [["busy-test" "170" "slinky" "0.8" "0.4" "true"]]})
    (isaac.session.session-steps/session-has-transcript "busy-test" {:headers ["type" "message.role" "message.content" "tokens"], :rows [["message" "user" "old topic" "40"] ["message" "assistant" "old reply" "40"] ["message" "user" "recent topic" "40"] ["message" "assistant" "recent reply" "50"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Tail summary" "test-model"] ["text" "First reply" "test-model"] ["text" "Second reply" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "first" "busy-test")
    (isaac.session.session-steps/async-compaction-in-flight "busy-test")
    (isaac.session.session-steps/user-sends-on-session "second" "busy-test")
    (isaac.session.session-steps/async-compaction-in-flight "busy-test")
    (isaac.session.session-steps/async-compaction-completes "busy-test")
    (isaac.session.session-steps/session-transcript-matching "busy-test" {:headers ["type" "message.role" "message.content" "#comment"], :rows [["compaction" "" "Tail summary" "only one compaction ran"] ["message" "user" "recent topic" ""] ["message" "assistant" "recent reply" ""] ["message" "user" "first" ""] ["message" "assistant" "First reply" ""] ["message" "user" "second" "second turn didn't trigger another"] ["message" "assistant" "Second reply" ""]]})))
