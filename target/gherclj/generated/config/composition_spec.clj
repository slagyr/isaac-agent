(ns config.composition-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.config.config-steps :as config-steps]
            [isaac.foundation.root-steps :as root-steps]))

(describe "Config Composition"

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

  (it "crew members are keyed by id"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:crew {:main {:soul \"You are Atticus.\"}}}")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["crew.main.soul" "You are Atticus."]]}))

  (it "loads a crew member from crew/<id>.edn"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:model :llama :soul \"You are Cordelia.\"}")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["crew.cordelia.model" "llama"] ["crew.cordelia.soul" "You are Cordelia."]]}))

  (it "loads a crew member from crew/<id>.md frontmatter"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:models    {:llama {:model \"llama3.2\" :provider :ollama}}\n :providers {:ollama {:api \"ollama\"}}}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.md" "---\nmodel: llama\n---\n\nYou are Cordelia.")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["crew.cordelia.model" "llama"] ["crew.cordelia.soul" "You are Cordelia."]]}))

  (it "soul loads from a companion .md file when :soul is absent"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:model :llama}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.md" "You are Cordelia, first mate.")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["crew.cordelia.soul" "You are Cordelia, first mate."]]}))

  (it "defining soul in both :soul and <id>.md is an error"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:soul \"Inline soul.\"}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.md" "File soul.")
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["crew.cordelia.soul" "must be set in .edn OR .md"]]}))

  (it "derives crew id from filename when :id is not specified"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "crew/ketch.edn" "{:model :llama}")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["crew.ketch.model" "llama"]]}))

  (it "explicit :id must match filename"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:id \"ketch\" :model :llama}")
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["crew.cordelia.id" "must match filename \\(got \"ketch\"\\)"]]}))

  (it "unknown keys in entity files produce warnings but still load"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:crew {:cordelia {:model :llama}}}")
    (isaac.config.config-steps/config-has-validation-warnings {:headers ["key" "value"], :rows [["crew.cordelia.crew" "unknown key"]]}))

  (it "composes crew from isaac.edn and crew/*.edn additively"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:crew {:main {:soul \"Atticus\"}}}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:model :llama :soul \"Cordelia\"}")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["crew.main.soul" "Atticus"] ["crew.cordelia.soul" "Cordelia"] ["crew.cordelia.model" "llama"]]}))

  (it "composes models from isaac.edn and models/*.edn additively"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:models {:ollama-local {:model \"qwen3-coder:30b\" :provider :ollama :context-window 32768}}}")
    (isaac.config.config-steps/config-file-containing "models/grover.edn" "{:model \"claude-opus-4-7\" :provider :grover :context-window 200000}")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["models.ollama-local.model" "qwen3-coder:30b"] ["models.ollama-local.provider" "ollama"] ["models.grover.model" "claude-opus-4-7"] ["models.grover.provider" "grover"]]}))

  (it "composes providers from isaac.edn and providers/*.edn additively"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :providers {:ollama {:base-url \"http://localhost:11434\" :api \"ollama\"}}}")
    (isaac.config.config-steps/config-file-containing "providers/anthropic.edn" "{:base-url \"https://api.anthropic.com\" :api \"anthropic\" :api-key \"${CONFIG_TEST_ANTHROPIC_API_KEY}\"}")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["providers.ollama.base-url" "http://localhost:11434"] ["providers.anthropic.api" "anthropic"] ["providers.anthropic.api-key" "${CONFIG_TEST_ANTHROPIC_API_KEY}"]]}))

  (it "duplicate crew id across isaac.edn and crew/*.edn is a hard error"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:crew {:cordelia {:soul \"First\"}}}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:soul \"Second\"}")
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["crew.cordelia" "defined in both isaac\\.edn and crew/cordelia\\.edn"]]}))

  (it "duplicate model id across isaac.edn and models/*.edn is a hard error"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :models   {:grover {:model \"claude-opus-4-6\" :provider :grover :context-window 200000}}}")
    (isaac.config.config-steps/config-file-containing "models/grover.edn" "{:model \"claude-opus-4-7\" :provider :grover :context-window 200000}")
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["models.grover" "defined in both isaac\\.edn and models/grover\\.edn"]]}))

  (it "defaults.crew must reference an existing crew"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :ghost :model :llama}}")
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["defaults.crew" "references undefined crew"]]}))

  (it "defaults.model must reference an existing model"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :nonexistent}}")
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["defaults.model" "references undefined model"]]}))

  (it "crew.model must reference an existing model"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:cordelia {:model :gpt}}}")
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["crew.cordelia.model" "references undefined model"]]}))

  (it "model.provider must reference an existing provider"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :providers {:ollama {:base-url \"http://localhost:11434\" :api \"ollama\"}}\n :models    {:grover {:model \"claude-opus-4-7\" :provider :foo :context-window 200000}}}")
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["models.grover.provider" "must be one of"]]}))

  (it "crew references a model defined in models/<id>.edn"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:cordelia {:model :grover}}}")
    (isaac.config.config-steps/config-file-containing "models/grover.edn" "{:model \"claude-opus-4-7\" :provider :grover :context-window 200000}")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["crew.cordelia.model" "grover"] ["models.grover.model" "claude-opus-4-7"] ["models.grover.provider" "grover"]]}))

  (it "no config files yields the built-in default config"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["defaults.crew" "main"] ["defaults.model" "llama"] ["models.llama.model" "llama3.3:1b"] ["models.llama.provider" "ollama"]]}))

  (it "malformed EDN in a config file is reported with the file path"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:model :llama")
    (isaac.config.config-steps/config-has-validation-errors {:headers ["key" "value"], :rows [["crew/cordelia.edn" "EDN syntax error"]]}))

  (it "${VAR} references are substituted from the environment"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/environment-variable-is "ANTHROPIC_API_KEY" "sk-test-123")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :providers {:anthropic {:base-url \"https://api.anthropic.com\"\n                         :api     \"anthropic\"\n                         :api-key  \"${ANTHROPIC_API_KEY}\"}}}")
    (isaac.config.config-steps/loaded-config-has {:headers ["key" "value"], :rows [["providers.anthropic.api-key" "sk-test-123"]]})))
