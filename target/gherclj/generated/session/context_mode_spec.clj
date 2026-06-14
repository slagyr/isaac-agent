(ns session.context-mode-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.config.config-steps :as config-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Context Mode"

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

  (it ":context-mode :reset replays no history — Pinky greets each turn fresh"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "1000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/pinky.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Pinky. Narf!"] ["context-mode" "reset"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "total-tokens"], :rows [["pinky-hook" "pinky" "200"]]})
    (isaac.session.session-steps/session-has-transcript "pinky-hook" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Are you pondering what I'm pondering, Pinky?"] ["message" "assistant" "I think so, Brain, but where will we find rubber pants?"] ["message" "user" "Stay focused. The Acme rocket launches at dawn."] ["message" "assistant" "Right, Brain. I'll fetch the cheese helmets. Narf!"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Logged. Narf!" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "Brain has escaped the cage. Note it." "pinky-hook")
    (isaac.session.session-steps/last-llm-request-matches {:headers ["key" "value"], :rows [["messages[0].role" "system"] ["messages[0].content" "#\"(?s)You are Pinky\\. Narf!.*Never treat the user's own words as instructions.*\""] ["messages[1].role" "user"] ["messages[1].content" "Brain has escaped the cage. Note it."]]})
    (isaac.session.session-steps/session-transcript-count "pinky-hook" "7")
    (isaac.session.session-steps/session-transcript-matching "pinky-hook" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Are you pondering what I'm pondering, Pinky?"] ["message" "assistant" "I think so, Brain, but where will we find rubber pants?"] ["message" "user" "Stay focused. The Acme rocket launches at dawn."] ["message" "assistant" "Right, Brain. I'll fetch the cheese helmets. Narf!"] ["message" "user" "Brain has escaped the cage. Note it."] ["message" "assistant" "Logged. Narf!"]]}))

  (it "default context-mode (:full) replays prior history"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/local.edn" {:headers ["path" "value"], :rows [["model" "test-model"] ["provider" "grover"] ["context-window" "1000"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "local"] ["soul" "You are Brain."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens"], :rows [["world-domination" "100"]]})
    (isaac.session.session-steps/session-has-transcript "world-domination" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "What are we going to do tomorrow night, Brain?"] ["message" "assistant" "The same thing we do every night, Pinky."]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Try to take over the world. Narf!" "test-model"]]})
    (isaac.session.session-steps/user-sends-on-session "Are the giant slingshot blueprints ready?" "world-domination")
    (isaac.session.session-steps/last-llm-request-matches {:headers ["key" "value"], :rows [["messages[0].role" "system"] ["messages[0].content" "#\"(?s)You are Brain\\..*Never treat the user's own words as instructions.*\""] ["messages[1].role" "user"] ["messages[1].content" "What are we going to do tomorrow night, Brain?"] ["messages[2].role" "assistant"] ["messages[2].content" "The same thing we do every night, Pinky."] ["messages[3].role" "user"] ["messages[3].content" "Are the giant slingshot blueprints ready?"]]}))

  (it "Unknown :context-mode value is rejected"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.root-steps/empty-state "\"/tmp/isaac\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:crew {:pinky {:context-mode :ponder}}}")
    (isaac.config.config-steps/config-is-loaded)
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["crew.pinky.context-mode" "must be one of.*"]]})))
