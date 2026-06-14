(ns session.context-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.context-steps :as context-steps]))

(describe "Turn context resolution"

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

  (it "soul falls back to default when none is configured"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "32768"]]})
    (isaac.session.context-steps/turn-context-resolved "main")
    (isaac.session.context-steps/resolved-soul-is "You are Isaac, a helpful AI assistant."))

  (it "soul from crew config is used when present"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "32768"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/main.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "Custom soul text"]]})
    (isaac.session.context-steps/turn-context-resolved "main")
    (isaac.session.context-steps/resolved-soul-is "Custom soul text"))

  (it "model and provider resolved from config defaults"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/models/grover.edn" {:headers ["path" "value"], :rows [["model" "echo"] ["provider" "grover"] ["context-window" "32768"]]})
    (isaac.session.context-steps/turn-context-resolved "main")
    (isaac.session.context-steps/resolved-model-not-nil)
    (isaac.session.context-steps/resolved-provider-not-nil)))
