(ns config.dangling-md-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.config.config-steps :as config-steps]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.root-steps :as root-steps]))

(describe "Config validation — dangling .md warnings"

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

  (it "dangling crew/<id>.md with no matching entity warns"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :crew      {:main {:soul \"Hello\"}}\n :models    {:llama {:model \"llama\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.config.config-steps/config-file-containing "crew/ghost.md" "I have no matching entity.")
    (isaac.foundation.cli-steps/isaac-run "config validate")
    (isaac.foundation.cli-steps/stderr-contains "dangling")
    (isaac.foundation.cli-steps/stderr-contains "crew/ghost.md")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "a single-file crew/<id>.md entity is not dangling"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "crew/main.md" "---\nmodel: llama\n---\n\nYou are Atticus.")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :models    {:llama {:model \"llama\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/isaac-run "config validate")
    (isaac.foundation.cli-steps/stderr-does-not-contain "dangling")
    (isaac.foundation.cli-steps/exit-code-is "0")))
