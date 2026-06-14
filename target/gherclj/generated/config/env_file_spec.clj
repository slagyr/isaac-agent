(ns config.env-file-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.config.config-steps :as config-steps]
            [isaac.foundation.root-steps :as root-steps]))

(describe "Isaac .env file for ${VAR} substitution"

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

  (it "${VAR} resolves from the isaac .env file"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/isaac-env-file-contains "ISAAC_ENV_FILE_TEST_KEY=sk-from-isaac")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :providers {:anthropic {:base-url \"https://api.anthropic.com\"\n                         :api     \"anthropic\"\n                         :api-key \"${ISAAC_ENV_FILE_TEST_KEY}\"}}}")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["providers.anthropic.api-key" "sk-from-isaac"]]}))

  (it "OS environment variables take precedence over the isaac .env file"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/environment-variable-is "ISAAC_ENV_FILE_TEST_KEY" "sk-from-os")
    (isaac.config.config-steps/isaac-env-file-contains "ISAAC_ENV_FILE_TEST_KEY=sk-from-isaac")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:providers {:anthropic {:api-key \"${ISAAC_ENV_FILE_TEST_KEY}\"}}}")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["providers.anthropic.api-key" "sk-from-os"]]}))

  (it "config loads when the isaac .env file is absent"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew {:main {}}\n :models {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["defaults.crew" "main"]]})))
