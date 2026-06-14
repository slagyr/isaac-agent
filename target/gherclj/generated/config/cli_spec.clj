(ns config.cli-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.config.config-steps :as config-steps]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.log-steps :as log-steps]
            [isaac.foundation.root-steps :as root-steps]))

(describe "Config Command"

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

  (it "config is registered and has help"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "help config")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["Usage: isaac config \\[subcommand\\] \\[options\\]"] ["Manage Isaac configuration"] ["Subcommands:"] ["validate\\s+Validate config"] ["get \\[config-path\\]\\s+Print the resolved config, or a subtree"] ["sources\\s+List contributing config files"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config validate has its own help page via --help"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config validate --help")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["Usage: isaac config validate \\[options\\] \\[-\\]"] ["Validate the config composition"] ["Options:"] ["--as CONFIG-PATH\\s+Overlay stdin EDN"] ["Arguments:"] ["-\\s+Read EDN to validate from stdin"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config help validate is an alternate way to reach subcommand help"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config help validate")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["Usage: isaac config validate \\[options\\] \\[-\\]"] ["Validate the config composition"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config get redacts resolved ${VAR} values by default"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/environment-variable-is "CONFIG_TEST_API_KEY" "sk-test-123")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :crew      {:main {}}\n :models    {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {:api-key  \"${CONFIG_TEST_API_KEY}\"\n                         :auth-key \"${CONFIG_TEST_UNSET_KEY}\"}}}")
    (isaac.foundation.cli-steps/isaac-run "config get")
    (isaac.foundation.cli-steps/stdout-lines-contain-in-order {:headers ["pattern"], :rows [[":auth-key"] ["\"<CONFIG_TEST_UNSET_KEY:UNRESOLVED>\""] [":api-key"] ["\"<CONFIG_TEST_API_KEY:redacted>\""]]})
    (isaac.foundation.cli-steps/stdout-has-at-least-lines "5")
    (isaac.foundation.cli-steps/stdout-does-not-contain "sk-test-123")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config get --raw prints pre-substitution values"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/environment-variable-is "CONFIG_TEST_API_KEY" "sk-test-123")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :crew      {:main {}}\n :models    {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {:api-key \"${CONFIG_TEST_API_KEY}\"}}}")
    (isaac.foundation.cli-steps/isaac-run "config get --raw")
    (isaac.foundation.cli-steps/stdout-lines-contain-in-order {:headers ["pattern"], :rows [[":api-key"] ["\"${CONFIG_TEST_API_KEY}\""]]})
    (isaac.foundation.cli-steps/stdout-does-not-contain "sk-test-123")
    (isaac.foundation.cli-steps/stdout-does-not-contain "redacted")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config get --reveal shows real values after typed confirmation"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/environment-variable-is "CONFIG_TEST_API_KEY" "sk-test-123")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :crew      {:main {}}\n :models    {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {:api-key \"${CONFIG_TEST_API_KEY}\"}}}")
    (isaac.foundation.cli-steps/stdin-is "REVEAL")
    (isaac.foundation.cli-steps/isaac-run "config get --reveal")
    (isaac.foundation.cli-steps/stderr-contains "type REVEAL to confirm:")
    (isaac.foundation.cli-steps/stdout-lines-contain-in-order {:headers ["pattern"], :rows [[":api-key"] ["\"sk-test-123\""]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config get --reveal refuses without typed confirmation"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/environment-variable-is "CONFIG_TEST_API_KEY" "sk-test-123")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:providers {:anthropic {:api-key \"${CONFIG_TEST_API_KEY}\"}}}")
    (isaac.foundation.cli-steps/stdin-is-empty)
    (isaac.foundation.cli-steps/isaac-run "config get --reveal")
    (isaac.foundation.cli-steps/stderr-contains "type REVEAL to confirm:")
    (isaac.foundation.cli-steps/stderr-contains "Refusing to reveal config.")
    (isaac.foundation.cli-steps/stdout-does-not-contain "sk-test-123")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "config sources lists contributing files"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:main {}}}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:model :llama}")
    (isaac.config.config-steps/config-file-containing "models/grover.edn" "{:model \"claude-opus-4-7\" :provider :grover :context-window 200000}")
    (isaac.foundation.cli-steps/isaac-run "config sources")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["config/isaac\\.edn"] ["config/crew/cordelia\\.edn"] ["config/models/grover\\.edn"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "validate passes for a well-formed config"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:main {:soul \"You are Atticus.\"}}\n :models   {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/isaac-run "config validate")
    (isaac.foundation.cli-steps/stdout-contains "OK")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "validate reports errors with exit code 1"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :ghost :model :llama}}")
    (isaac.foundation.cli-steps/isaac-run "config validate")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["pattern"], :rows [["defaults\\.crew.*references undefined crew"]]})
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "validate reports unknown llm api refs with file and valid set"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "providers/bogus.edn" "{:api \"carrier-pigeon\" :base-url \"https://example.com\" :auth \"api-key\" :api-key \"test\"}")
    (isaac.foundation.cli-steps/isaac-run "config validate")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["pattern"], :rows [["providers\\.bogus\\.api"] ["must be one of"] ["file: config/providers/bogus\\.edn"] ["bad value: carrier-pigeon"] ["valid: .*chat-completions.*"]]})
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "validate reports unknown tool refs with file and valid set"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :local}\n :models    {:local {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.config.config-steps/config-file-containing "crew/main.edn" "{:tools {:allow [:bogus-tool]}}")
    (isaac.foundation.cli-steps/isaac-run "config validate")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["pattern"], :rows [["crew\\.main\\.tools\\.allow"] ["must be a registered contribution to :isaac.server/tools"] ["bad value: bogus-tool"] ["file: config/crew/main\\.edn"] ["valid: .*read.*"] ["valid: .*write.*"] ["valid: .*exec.*"]]})
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "validate reports unknown provider refs with file and valid set"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "models/grover.edn" "{:model \"claude-opus-4-7\" :provider :foo :context-window 200000}")
    (isaac.config.config-steps/config-file-containing "providers/anthropic.edn" "{}")
    (isaac.config.config-steps/config-file-containing "providers/grover.edn" "{}")
    (isaac.foundation.cli-steps/isaac-run "config validate")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["pattern"], :rows [["models\\.grover\\.provider"] ["must be one of"] ["file: config/models/grover\\.edn"] ["bad value: foo"] ["valid: .*anthropic.*grover.*"]]})
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "validate reports unknown comm type refs with file and valid set"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :local}\n :crew      {:main {}}\n :models    {:local {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}\n :modules   {:isaac.comm.telly {:local/root \"modules/isaac.comm.telly\"}}\n :comms     {:relay {:type :smoke-signals :crew :main}}}")
    (isaac.foundation.cli-steps/isaac-run "config validate")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["pattern"], :rows [["comms\\[:relay\\]\\.type"] ["must be one of"] ["file: config/isaac\\.edn"] ["bad value: smoke-signals"] ["valid: .*telly.*"]]})
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "validate reports unknown model refs with file and valid set"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :local}\n :crew      {:main {}}\n :models    {:local {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.config.config-steps/config-file-containing "hooks/webhook.edn" "{:crew :main :model :ghost-model :template \"Hello\"}")
    (isaac.foundation.cli-steps/isaac-run "config validate")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["pattern"], :rows [["hooks\\.webhook\\.model"] ["references undefined model"] ["file: config/hooks/webhook\\.edn"] ["bad value: ghost-model"] ["valid: .*local.*"]]})
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "validate reports unknown crew refs with file and valid set"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :local}\n :crew      {:main {}}\n :models    {:local {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.config.config-steps/config-file-containing "cron/nightly.edn" "{:expr \"0 9 * * *\" :crew :ghost :prompt \"Ping\"}")
    (isaac.foundation.cli-steps/isaac-run "config validate")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["pattern"], :rows [["cron\\.nightly\\.crew"] ["references undefined crew"] ["file: config/cron/nightly\\.edn"] ["bad value: ghost"] ["valid: .*main.*"]]})
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "validate reports warnings but still exits 0"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults     {:crew :main :model :llama}\n :crew         {:main {}}\n :models       {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers    {:anthropic {}}\n :experimental {:feature-flag true}}")
    (isaac.foundation.cli-steps/isaac-run "config validate")
    (isaac.foundation.cli-steps/stderr-matches {:headers ["pattern"], :rows [["warning: :experimental"] ["unknown key"]]})
    (isaac.foundation.cli-steps/stdout-contains "OK")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "validate reads stdin as the full config and ignores on-disk files"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:broken-key-that-should-error true}")
    (isaac.foundation.cli-steps/stdin-is "{:defaults  {:crew :main :model :llama}\n :crew      {:main {}}\n :models    {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/isaac-run "config validate -")
    (isaac.foundation.cli-steps/stdout-contains "valid")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "validate --as overlays stdin at the given config path before validating"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :crew      {}\n :models    {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/stdin-is "{:soul \"A paranoid android.\"}")
    (isaac.foundation.cli-steps/isaac-run "config validate --as crew.main -")
    (isaac.foundation.cli-steps/stdout-contains "valid")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "validate --as rejects file-path style with a hint to use a config path"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/stdin-is "{:soul \"...\"}")
    (isaac.foundation.cli-steps/isaac-run "config validate --as crew/cordelia.edn -")
    (isaac.foundation.cli-steps/stderr-contains "config path")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "get prints a scalar value by dotted keyword path"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:main {}\n             :cordelia {:soul \"You are Cordelia.\"}}\n :models   {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/isaac-run "config get crew.cordelia.soul")
    (isaac.foundation.cli-steps/stdout-contains "You are Cordelia.")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "get prints a scalar value by bracket keyword path"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:main {}\n             :cordelia {:soul \"You are Cordelia.\"}}\n :models   {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/isaac-run "config get crew[:cordelia].soul")
    (isaac.foundation.cli-steps/stdout-contains "You are Cordelia.")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "get prints a nested structure as EDN"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:main {}\n             :cordelia {:model :llama :soul \"You are Cordelia.\"}}\n :models   {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/isaac-run "config get crew.cordelia")
    (isaac.foundation.cli-steps/stdout-lines-contain-in-order {:headers ["pattern"], :rows [[":soul"] ["\"You are Cordelia.\""] [":model :llama"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "get exits non-zero for a missing key"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:main {}\n             :cordelia {:soul \"You are Cordelia.\"}}\n :models   {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/isaac-run "config get crew.cordelia.nope")
    (isaac.foundation.cli-steps/stderr-contains "not found: crew.cordelia.nope")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "get redacts resolved ${VAR} values by default"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/environment-variable-is "CONFIG_TEST_API_KEY" "sk-test-123")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :crew      {:main {}}\n :models    {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {:api-key \"${CONFIG_TEST_API_KEY}\"}}}")
    (isaac.foundation.cli-steps/isaac-run "config get providers.anthropic.api-key")
    (isaac.foundation.cli-steps/stdout-contains "<CONFIG_TEST_API_KEY:redacted>")
    (isaac.foundation.cli-steps/stdout-does-not-contain "sk-test-123")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "get --reveal shows the real value after typed confirmation"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/environment-variable-is "CONFIG_TEST_API_KEY" "sk-test-123")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :crew      {:main {}}\n :models    {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {:api-key \"${CONFIG_TEST_API_KEY}\"}}}")
    (isaac.foundation.cli-steps/stdin-is "REVEAL")
    (isaac.foundation.cli-steps/isaac-run "config get providers.anthropic.api-key --reveal")
    (isaac.foundation.cli-steps/stderr-contains "type REVEAL to confirm:")
    (isaac.foundation.cli-steps/stdout-contains "sk-test-123")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "get --reveal refuses on invalid confirmation"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/environment-variable-is "CONFIG_TEST_API_KEY" "sk-test-123")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :llama}\n :crew      {:main {}}\n :models    {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {:api-key \"${CONFIG_TEST_API_KEY}\"}}}")
    (isaac.foundation.cli-steps/stdin-is "blah")
    (isaac.foundation.cli-steps/isaac-run "config get providers --reveal")
    (isaac.foundation.cli-steps/stderr-contains "type REVEAL to confirm:")
    (isaac.foundation.cli-steps/stderr-contains "Refusing to reveal config.")
    (isaac.foundation.cli-steps/stdout-does-not-contain "sk-test-123")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "config schema prints the root schema with title, fields, and guidance"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config schema")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["\\[isaac\\] isaac schema"] ["crew\\s+.*\\[crew\\]"] ["defaults\\s+.*\\[defaults\\]"] ["models\\s+.*\\[models\\]"] ["providers\\s+.*\\[providers\\]"] ["Try:"] ["isaac config schema crew"] ["isaac config schema providers\\.value"] ["isaac config schema crew\\.value\\.model"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema --tree expands every named sub-schema"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config schema --tree")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["\\[isaac\\] isaac schema"] ["\\[crew\\.value\\] crew schema"] ["Crew member id; must match filename when present"] ["\\[providers\\.value\\] provider schema"] ["base-url"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema crew renders the map wrapper with key/value rows"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config schema crew")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["\\[crew\\] crew table schema"] ["map of"] ["key\\s+string\\s+\\[crew\\.key\\]"] ["value\\s+.*crew\\s+\\[crew\\.value\\]"] ["Crew member configurations"]]})
    (isaac.foundation.cli-steps/stdout-does-not-contain "Model alias")
    (isaac.foundation.cli-steps/stdout-does-not-contain "System prompt")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema crew.value prints the crew entity fields"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config schema crew.value")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["\\[crew\\.value\\] crew schema"] ["model\\s+id\\s+\\[crew\\.value\\.model\\]"] ["soul\\s+string\\s+\\[crew\\.value\\.soul\\]"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema providers.key resolves the map-key spec"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config schema providers.key")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["\\[providers\\.key\\] schema"] ["string\\s+\\[providers\\.key\\]"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema providers.value prints the provider entity template"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config schema providers.value")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["\\[providers\\.value\\] provider schema"] ["api-key\\s+string\\s+\\[providers\\.value\\.api-key\\]"] ["base-url\\s+string\\s+\\[providers\\.value\\.base-url\\]"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema crew.value.id prints the :id field schema"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config schema crew.value.id")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["\\[crew\\.value\\.id\\] schema"] ["id\\s+\\[crew\\.value\\.id\\]"] ["Crew member id; must match filename when present"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema drills into a single field"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config schema providers.value.api-key")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["\\[providers\\.value\\.api-key\\] schema"] ["string\\s+\\[providers\\.value\\.api-key\\]"] ["API key"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema gives a friendly error for an invalid path"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config schema providers.valued")
    (isaac.foundation.cli-steps/stderr-contains "Path not found in config schema: providers.valued")
    (isaac.foundation.cli-steps/stderr-does-not-contain "Exception")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "config help lists the schema subcommand"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "help config")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["schema \\[schema-path\\]\\s+Print the config schema"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config schema --help describes the --tree flag"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config schema --help")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["Usage: isaac config schema"] ["--tree\\s+Expand every named"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "set writes a new crew member to isaac.edn by default"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:main {}}\n :models   {:llama {:model \"llama3.3:1b\" :provider :anthropic}\n            :gpt   {:model \"gpt-5.4\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/isaac-run "config set crew.cordelia.model gpt")
    (isaac.config.config-steps/config-file-matches "isaac.edn" {:headers ["pattern"], :rows [[":cordelia"] [":model\\s+:gpt"]]})
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "value" "file"], :rows [[":info" ":config/set" "crew.cordelia.model" ":gpt" "isaac.edn"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "set writes to the existing entity file when one already defines the key"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:main {}}\n :models   {:llama {:model \"llama3.3:1b\" :provider :anthropic}\n            :gpt   {:model \"gpt-5.4\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:model :llama}")
    (isaac.foundation.cli-steps/isaac-run "config set crew.cordelia.model gpt")
    (isaac.config.config-steps/config-file-matches "crew/cordelia.edn" {:headers ["pattern"], :rows [[":model\\s+:gpt"]]})
    (isaac.config.config-steps/config-file-does-not-contain "isaac.edn" "cordelia")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "value" "file"], :rows [[":info" ":config/set" "crew.cordelia.model" ":gpt" "crew/cordelia.edn"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "set writes to isaac.edn when the entity is already defined there"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:main   {}\n             :cordelia {:model :llama}}\n :models   {:llama {:model \"llama3.3:1b\" :provider :anthropic}\n            :gpt   {:model \"gpt-5.4\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/isaac-run "config set crew.cordelia.model gpt")
    (isaac.config.config-steps/config-file-matches "isaac.edn" {:headers ["pattern"], :rows [[":cordelia"] [":model\\s+:gpt"]]})
    (isaac.config.config-steps/config-file-does-not-exist "crew/cordelia.edn")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "value" "file"], :rows [[":info" ":config/set" "crew.cordelia.model" ":gpt" "isaac.edn"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "set writes new entities to entity files when prefer-entity-files is true"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults            {:crew :main :model :llama}\n :prefer-entity-files true\n :crew                {:main {}}\n :models              {:llama {:model \"llama3.3:1b\" :provider :anthropic}\n                       :gpt   {:model \"gpt-5.4\" :provider :anthropic}}\n :providers           {:anthropic {}}}")
    (isaac.foundation.cli-steps/isaac-run "config set crew.cordelia.model gpt")
    (isaac.config.config-steps/config-file-matches "crew/cordelia.edn" {:headers ["pattern"], :rows [[":model\\s+:gpt"]]})
    (isaac.config.config-steps/config-file-does-not-contain "isaac.edn" "cordelia")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "value" "file"], :rows [[":info" ":config/set" "crew.cordelia.model" ":gpt" "crew/cordelia.edn"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "set writes soul to the companion .md when it already exists"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "models/gpt.edn" "{:model \"gpt-5.4\" :provider :anthropic}")
    (isaac.config.config-steps/config-file-containing "providers/anthropic.edn" "{}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:model :gpt}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.md" "Old soul.")
    (isaac.foundation.cli-steps/isaac-run "config set crew.cordelia.soul \\\"New soul.\\\"")
    (isaac.config.config-steps/config-file-matches "crew/cordelia.md" {:headers ["pattern"], :rows [["New soul."]]})
    (isaac.config.config-steps/config-file-does-not-contain "crew/cordelia.edn" ":soul")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "value" "file"], :rows [[":info" ":config/set" "crew.cordelia.soul" "New soul." "crew/cordelia.md"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "set creates a companion .md when a new soul exceeds 64 characters"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "models/gpt.edn" "{:model \"gpt-5.4\" :provider :anthropic}")
    (isaac.config.config-steps/config-file-containing "providers/anthropic.edn" "{}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:model :gpt}")
    (isaac.foundation.cli-steps/isaac-run "config set crew.cordelia.soul \\\"You are Cordelia, first mate of the Marigold, steady-handed, sharp-eyed, and always three moves ahead of the weather.\\\"")
    (isaac.config.config-steps/config-file-matches "crew/cordelia.md" {:headers ["pattern"], :rows [["You are Cordelia"] ["sharp-eyed"]]})
    (isaac.config.config-steps/config-file-does-not-contain "crew/cordelia.edn" ":soul")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "value" "file"], :rows [[":info" ":config/set" "crew.cordelia.soul" "You are Cordelia, first mate of the Marigold, steady-handed, sharp-eyed, and always three moves ahead of the weather." "crew/cordelia.md"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "set writes short soul inline in the entity file"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "models/gpt.edn" "{:model \"gpt-5.4\" :provider :anthropic}")
    (isaac.config.config-steps/config-file-containing "providers/anthropic.edn" "{}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:model :gpt}")
    (isaac.foundation.cli-steps/isaac-run "config set crew.cordelia.soul \\\"First mate.\\\"")
    (isaac.config.config-steps/config-file-matches "crew/cordelia.edn" {:headers ["pattern"], :rows [[":soul\\s+\"First mate\\.\""]]})
    (isaac.config.config-steps/config-file-does-not-exist "crew/cordelia.md")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "value" "file"], :rows [[":info" ":config/set" "crew.cordelia.soul" "First mate." "crew/cordelia.edn"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "set refuses to write a value that fails type validation"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:main {}}\n :models   {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/isaac-run "config set crew.cordelia.effort not-a-number")
    (isaac.foundation.cli-steps/stderr-contains "effort")
    (isaac.config.config-steps/config-file-does-not-contain "isaac.edn" "not-a-number")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "set errors on a path the schema does not recognize"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:main {}}\n :models   {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/isaac-run "config set crew.main.experimental true")
    (isaac.foundation.cli-steps/stderr-contains "experimental")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "unset removes a key from the file where it lives"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "models/gpt.edn" "{:model \"gpt-5.4\" :provider :anthropic}")
    (isaac.config.config-steps/config-file-containing "providers/anthropic.edn" "{}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:model :gpt :soul \"Paranoid.\"}")
    (isaac.foundation.cli-steps/isaac-run "config unset crew.cordelia.soul")
    (isaac.config.config-steps/config-file-matches "crew/cordelia.edn" {:headers ["pattern"], :rows [[":model\\s+:gpt"]]})
    (isaac.config.config-steps/config-file-does-not-contain "crew/cordelia.edn" ":soul")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "file"], :rows [[":info" ":config/unset" "crew.cordelia.soul" "crew/cordelia.edn"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "unset that empties an entity file deletes it"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "models/gpt.edn" "{:model \"gpt-5.4\" :provider :anthropic}")
    (isaac.config.config-steps/config-file-containing "providers/anthropic.edn" "{}")
    (isaac.config.config-steps/config-file-containing "crew/cordelia.edn" "{:model :gpt}")
    (isaac.foundation.cli-steps/isaac-run "config unset crew.cordelia.model")
    (isaac.config.config-steps/config-file-does-not-exist "crew/cordelia.edn")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "file"], :rows [[":info" ":config/unset" "crew.cordelia.model" "crew/cordelia.edn"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "set writes a whole entity read from stdin"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults {:crew :main :model :llama}\n :crew     {:main {}}\n :models   {:llama {:model \"llama3.3:1b\" :provider :anthropic}}\n :providers {:anthropic {}}}")
    (isaac.foundation.cli-steps/stdin-is "{:base-url \"https://api.x.ai/v1\" :api-key \"${GROK_API_KEY}\" :api \"chat-completions\"}")
    (isaac.foundation.cli-steps/isaac-run "config set providers.grok -")
    (isaac.config.config-steps/config-file-matches "isaac.edn" {:headers ["pattern"], :rows [[":grok"] [":base-url\\s+\"https://api\\.x\\.ai/v1\""] [":api\\s+\"chat-completions\""]]})
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "value" "file"], :rows [[":info" ":config/set" "providers.grok" "#\".*api\\.x\\.ai/v1.*\"" "isaac.edn"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "set replaces an existing entity rather than merging"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "providers/grok.edn" "{:base-url \"https://old.example.com\" :api-key \"${OLD_KEY}\" :api \"chat-completions\"}")
    (isaac.foundation.cli-steps/stdin-is "{:base-url \"https://api.x.ai/v1\" :api-key \"${GROK_API_KEY}\"}")
    (isaac.foundation.cli-steps/isaac-run "config set providers.grok -")
    (isaac.config.config-steps/config-file-matches "providers/grok.edn" {:headers ["pattern"], :rows [[":base-url\\s+\"https://api\\.x\\.ai/v1\""] [":api-key\\s+\"\\$\\{GROK_API_KEY\\}\""]]})
    (isaac.config.config-steps/config-file-does-not-contain "providers/grok.edn" "old.example.com")
    (isaac.config.config-steps/config-file-does-not-contain "providers/grok.edn" ":api ")
    (isaac.foundation.log-steps/log-entries-match {:headers ["level" "event" "path" "value" "file"], :rows [[":info" ":config/set" "providers.grok" "#\".*\\$\\{GROK_API_KEY\\}.*\"" "providers/grok.edn"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config help lists set and unset subcommands"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "help config")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["set <config-path> <value>\\s+Set a value at a config path"] ["unset <config-path>\\s+Remove a value at a config path"]]})
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "config set --help documents stdin form and examples"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.foundation.cli-steps/isaac-run "config set --help")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["Usage: isaac config set <config-path>"] ["-\\s+Read the value as EDN from stdin"]]})
    (isaac.foundation.cli-steps/exit-code-is "0")))
