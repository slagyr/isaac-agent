Feature: Config Command
  `isaac config` inspects and validates configuration at ~/.isaac/config/.
  Operators can print the resolved (merged) config, look up a single value,
  list contributing files, or validate a staged change before writing it.
  Sensitive values sourced from ${VAR} substitution are redacted by default;
  --reveal requires typing "REVEAL" on stdin to surface real values.

  Background:
    Given an Isaac root at "isaac-state"

  # ----- Help -----

  Scenario: config is registered and has help
    When isaac is run with "help config"
    Then the stdout matches:
      | pattern                                                         |
      | Usage: isaac config \[subcommand\] \[options\]                  |
      | Manage Isaac configuration                                      |
      | Subcommands:                                                    |
      | validate\s+Validate config                                      |
      | get \[config-path\]\s+Print the resolved config, or a subtree   |
      | sources\s+List contributing config files                        |
    And the exit code is 0

  Scenario: config validate has its own help page via --help
    When isaac is run with "config validate --help"
    Then the stdout matches:
      | pattern                                                  |
      | Usage: isaac config validate \[options\] \[-\]           |
      | Validate the config composition                          |
      | Options:                                                 |
      | --as CONFIG-PATH\s+Overlay stdin EDN                     |
      | Arguments:                                               |
      | -\s+Read EDN to validate from stdin                      |
    And the exit code is 0

  Scenario: config help validate is an alternate way to reach subcommand help
    When isaac is run with "config help validate"
    Then the stdout matches:
      | pattern                                                  |
      | Usage: isaac config validate \[options\] \[-\]           |
      | Validate the config composition                          |
    And the exit code is 0

  # ----- Get (whole config / --raw / --reveal) -----

  Scenario: config get redacts resolved ${VAR} values by default
    Given environment variable "CONFIG_TEST_API_KEY" is "sk-test-123"
    And config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :llama}
       :crew      {:main {}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {:api-key  "${CONFIG_TEST_API_KEY}"
                               :auth-key "${CONFIG_TEST_UNSET_KEY}"}}}
      """
    When isaac is run with "config get"
    Then the stdout lines contain in order:
      | pattern                              |
      | :auth-key                            |
      | "<CONFIG_TEST_UNSET_KEY:UNRESOLVED>" |
      | :api-key                             |
      | "<CONFIG_TEST_API_KEY:redacted>"    |
    And the stdout has at least 5 lines
    And the stdout does not contain "sk-test-123"
    And the exit code is 0

  Scenario: config get --raw prints pre-substitution values
    Given environment variable "CONFIG_TEST_API_KEY" is "sk-test-123"
    And config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :llama}
       :crew      {:main {}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {:api-key "${CONFIG_TEST_API_KEY}"}}}
      """
    When isaac is run with "config get --raw"
    Then the stdout lines contain in order:
      | pattern                    |
      | :api-key                   |
      | "${CONFIG_TEST_API_KEY}"  |
    And the stdout does not contain "sk-test-123"
    And the stdout does not contain "redacted"
    And the exit code is 0

  Scenario: config get --reveal shows real values after typed confirmation
    Given environment variable "CONFIG_TEST_API_KEY" is "sk-test-123"
    And config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :llama}
       :crew      {:main {}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {:api-key "${CONFIG_TEST_API_KEY}"}}}
      """
    And stdin is:
      """
      REVEAL
      """
    When isaac is run with "config get --reveal"
    Then the stderr contains "type REVEAL to confirm:"
    And the stdout lines contain in order:
      | pattern         |
      | :api-key        |
      | "sk-test-123"  |
    And the exit code is 0

  Scenario: config get --reveal refuses without typed confirmation
    Given environment variable "CONFIG_TEST_API_KEY" is "sk-test-123"
    And config file "isaac.edn" containing:
      """
      {:providers {:anthropic {:api-key "${CONFIG_TEST_API_KEY}"}}}
      """
    And stdin is empty
    When isaac is run with "config get --reveal"
    Then the stderr contains "type REVEAL to confirm:"
    And the stderr contains "Refusing to reveal config."
    And the stdout does not contain "sk-test-123"
    And the exit code is 1

  # ----- Sources -----

  Scenario: config sources lists contributing files
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:main {}}}
      """
    And config file "crew/cordelia.edn" containing:
      """
      {:model :llama}
      """
    And config file "models/grover.edn" containing:
      """
      {:model "claude-opus-4-7" :provider :grover :context-window 200000}
      """
    When isaac is run with "config sources"
    Then the stdout matches:
      | pattern                    |
      | config/isaac\.edn          |
      | config/crew/cordelia\.edn    |
      | config/models/grover\.edn  |
    And the exit code is 0

  # ----- Validate -----

  Scenario: validate passes for a well-formed config
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:main {:soul "You are Atticus."}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config validate"
    Then the stdout contains "OK"
    And the exit code is 0

  Scenario: validate reports errors with exit code 1
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :ghost :model :llama}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                    |
      | defaults\.crew.*references undefined crew  |
    And the exit code is 1

  Scenario: validate reports unknown llm api refs with file and valid set
    # Phase 7 of brth (isaac-ho18): :llm-api-exists? was replaced by
    # [:registered-in? :isaac.server/llm-api]. The validator's failure
    # message changed from "unknown api" to the berth-namespaced form,
    # but bad-value / file / valid-set rendering is preserved.
    Given config file "providers/bogus.edn" containing:
      """
      {:api "carrier-pigeon" :base-url "https://example.com" :auth "api-key" :api-key "test"}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                                              |
      | providers\.bogus\.api                                                |
      | must be a registered contribution to :isaac\.agent/llm-api           |
      | file: config/providers/bogus\.edn                                    |
      | bad value: carrier-pigeon                                            |
      | valid: .*chat-completions.*                                          |
    And the exit code is 1

  Scenario: validate reports unknown tool refs with file and valid set
    # Phase 6 of the berth epic (isaac-w7o5) replaced the legacy
    # :tool-exists? validator with [:registered-in? :isaac.agent/tools].
    # The new validator says "must be a registered contribution to
    # :isaac.agent/tools" rather than "references undefined tool", but
    # still reports the bad value, source file, and known-set.
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :local}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And config file "crew/main.edn" containing:
      """
      {:tools {:allow [:bogus-tool]}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                                  |
      | crew\.main\.tools\.allow                                |
      | must be a registered contribution to :isaac.agent/tools |
      | bad value: bogus-tool                                    |
      | file: config/crew/main\.edn                              |
      | valid: .*read.*                                          |
      | valid: .*write.*                                         |
      | valid: .*exec.*                                          |
    And the exit code is 1

  Scenario: validate reports unknown provider refs with file and valid set
    # Phase 7 of brth (isaac-ho18): :provider-exists? was replaced by
    # [:registered-in? :isaac.server/provider [:providers]]. Wording
    # shifted from "references undefined provider" to the validator's
    # "must be one of …" (small accepted set), with valid-set rendering
    # preserved.
    Given config file "models/grover.edn" containing:
      """
      {:model "claude-opus-4-7" :provider :foo :context-window 200000}
      """
    And config file "providers/anthropic.edn" containing:
      """
      {}
      """
    And config file "providers/grover.edn" containing:
      """
      {}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                               |
      | models\.grover\.provider            |
      | must be one of                        |
      | file: config/models/grover\.edn      |
      | bad value: foo                       |
      | valid: .*anthropic.*grover.*         |
    And the exit code is 1

  Scenario: validate reports unknown comm type refs with file and valid set
    # Phase 8 of brth (isaac-qqgv) replaced :comm-exists? with
    # [:registered-in? :isaac.server/comm [:comms]]. The validator's
    # identity message shifted accordingly; bad-value / file / valid-set
    # rendering is preserved.
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :local}
       :crew      {:main {}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}
       :modules   {:isaac.comm.telly {:local/root "modules/isaac.comm.telly"}}
       :comms     {:relay {:type :smoke-signals :crew :main}}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                   |
      | comms\[:relay\]\.type                     |
      | must be one of                            |
      | file: config/isaac\.edn                   |
      | bad value: smoke-signals                  |
      | valid: .*telly.*                          |
    And the exit code is 1

  Scenario: validate reports warnings but still exits 0
    Given config file "isaac.edn" containing:
      """
      {:defaults     {:crew :main :model :llama}
       :crew         {:main {}}
       :models       {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers    {:anthropic {}}
       :experimental {:feature-flag true}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                      |
      | warning: :experimental       |
      | unknown key                  |
    And the stdout contains "OK"
    And the exit code is 0

  Scenario: validate warns when a crew directory includes the Isaac state root (isaac-dwjy)
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :scrapper :model :llama}
       :crew      {:scrapper {:tools {:directories ["/isaac-state"]}}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                      |
      | warning: :crew\.scrapper\.tools\.directories |
      | Isaac state directory                        |
      | :role                                        |
    And the stdout contains "OK"
    And the exit code is 0

  # ----- Validate overlay (--as) -----

  Scenario: validate reads stdin as the full config and ignores on-disk files
    Given config file "isaac.edn" containing:
      """
      {:broken-key-that-should-error true}
      """
    And stdin is:
      """
      {:defaults  {:crew :main :model :llama}
       :crew      {:main {}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config validate -"
    Then the stdout contains "valid"
    And the exit code is 0

  Scenario: validate --as overlays stdin at the given config path before validating
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :llama}
       :crew      {}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And stdin is:
      """
      {:soul "A paranoid android."}
      """
    When isaac is run with "config validate --as crew.main -"
    Then the stdout contains "valid"
    And the exit code is 0

  Scenario: validate --as rejects file-path style with a hint to use a config path
    Given stdin is:
      """
      {:soul "..."}
      """
    When isaac is run with "config validate --as crew/cordelia.edn -"
    Then the stderr contains "config path"
    And the exit code is 1

  # ----- Get -----

  Scenario: get prints a scalar value by dotted keyword path
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:main {}
                   :cordelia {:soul "You are Cordelia."}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config get crew.cordelia.soul"
    Then the stdout contains "You are Cordelia."
    And the exit code is 0

  Scenario: get prints a scalar value by bracket keyword path
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:main {}
                   :cordelia {:soul "You are Cordelia."}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config get crew[:cordelia].soul"
    Then the stdout contains "You are Cordelia."
    And the exit code is 0

  Scenario: get prints a nested structure as EDN
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:main {}
                   :cordelia {:model :llama :soul "You are Cordelia."}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config get crew.cordelia"
    Then the stdout lines contain in order:
      | pattern             |
      | :soul               |
      | "You are Cordelia."  |
      | :model :llama       |
    And the exit code is 0

  Scenario: get exits non-zero for a missing key
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:main {}
                   :cordelia {:soul "You are Cordelia."}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config get crew.cordelia.nope"
    Then the stderr contains "not found: crew.cordelia.nope"
    And the exit code is 1

  Scenario: get redacts resolved ${VAR} values by default
    Given environment variable "CONFIG_TEST_API_KEY" is "sk-test-123"
    And config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :llama}
       :crew      {:main {}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {:api-key "${CONFIG_TEST_API_KEY}"}}}
      """
    When isaac is run with "config get providers.anthropic.api-key"
    Then the stdout contains "<CONFIG_TEST_API_KEY:redacted>"
    And the stdout does not contain "sk-test-123"
    And the exit code is 0

  Scenario: get --reveal shows the real value after typed confirmation
    Given environment variable "CONFIG_TEST_API_KEY" is "sk-test-123"
    And config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :llama}
       :crew      {:main {}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {:api-key "${CONFIG_TEST_API_KEY}"}}}
      """
    And stdin is:
      """
      REVEAL
      """
    When isaac is run with "config get providers.anthropic.api-key --reveal"
    Then the stderr contains "type REVEAL to confirm:"
    And the stdout contains "sk-test-123"
    And the exit code is 0

  Scenario: get --reveal refuses on invalid confirmation
    Given environment variable "CONFIG_TEST_API_KEY" is "sk-test-123"
    And config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :llama}
       :crew      {:main {}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {:api-key "${CONFIG_TEST_API_KEY}"}}}
      """
    And stdin is:
      """
      blah
      """
    When isaac is run with "config get providers --reveal"
    Then the stderr contains "type REVEAL to confirm:"
    And the stderr contains "Refusing to reveal config."
    And the stdout does not contain "sk-test-123"
    And the exit code is 1

  # ----- Schema -----

  Scenario: config schema prints the root schema with title, fields, and guidance
    When isaac is run with "config schema"
    Then the stdout matches:
      | pattern                                 |
      | \[isaac\] isaac schema                  |
      | crew\s+.*\[crew\]                       |
      | defaults\s+.*\[defaults\]               |
      | models\s+.*\[models\]                   |
      | providers\s+.*\[providers\]             |
      | Try:                                    |
      | isaac config schema crew                |
      | isaac config schema providers\.value    |
      | isaac config schema crew\.value\.model  |
    And the exit code is 0

  Scenario: config schema --tree expands every named sub-schema
    When isaac is run with "config schema --tree"
    Then the stdout matches:
      | pattern                                           |
      | \[isaac\] isaac schema                            |
      | \[crew\.value\] crew schema                       |
      | Crew member id; must match filename when present  |
      | \[providers\.value\] provider schema              |
      | base-url                                          |
    And the exit code is 0

  Scenario: config schema crew renders the map wrapper with key/value rows
    When isaac is run with "config schema crew"
    Then the stdout matches:
      | pattern                                       |
      | \[crew\] crew table schema                    |
      | map of                                        |
      | key\s+string\s+\[crew\.key\]               |
      | value\s+.*crew\s+\[crew\.value\]                |
      | Crew member configurations                    |
    And the stdout does not contain "Model alias"
    And the stdout does not contain "System prompt"
    And the exit code is 0

  Scenario: config schema crew.value prints the crew entity fields
    When isaac is run with "config schema crew.value"
    Then the stdout matches:
      | pattern                                           |
      | \[crew\.value\] crew schema                          |
      | model\s+id\s+\[crew\.value\.model\]                   |
      | soul\s+string\s+\[crew\.value\.soul\]                 |
    And the exit code is 0

  Scenario: config schema providers.key resolves the map-key spec
    When isaac is run with "config schema providers.key"
    Then the stdout matches:
      | pattern                          |
      | \[providers\.key\] schema       |
      | string\s+\[providers\.key\]     |
    And the exit code is 0

  Scenario: config schema providers.value prints the provider entity template
    When isaac is run with "config schema providers.value"
    Then the stdout matches:
      | pattern                                           |
      | \[providers\.value\] provider schema                  |
      | api-key\s+string\s+\[providers\.value\.api-key\]      |
      | base-url\s+string\s+\[providers\.value\.base-url\]    |
    And the exit code is 0

  Scenario: config schema crew.value.id prints the :id field schema
    When isaac is run with "config schema crew.value.id"
    Then the stdout matches:
      | pattern                                           |
      | \[crew\.value\.id\] schema                            |
      | id\s+\[crew\.value\.id\]                              |
      | Crew member id; must match filename when present  |
    And the exit code is 0

  Scenario: config schema drills into a single field
    When isaac is run with "config schema providers.value.api-key"
    Then the stdout matches:
      | pattern                                       |
      | \[providers\.value\.api-key\] schema              |
      | string\s+\[providers\.value\.api-key\]            |
      | API key                                       |
    And the exit code is 0

  Scenario: config schema gives a friendly error for an invalid path
    When isaac is run with "config schema providers.valued"
    Then the stderr contains "Path not found in config schema: providers.valued"
    And the stderr does not contain "Exception"
    And the exit code is 1

  Scenario: config help lists the schema subcommand
    When isaac is run with "help config"
    Then the stdout matches:
      | pattern                                               |
      | schema \[schema-path\]\s+Print the config schema      |
    And the exit code is 0

  Scenario: config schema --help describes the --tree flag
    When isaac is run with "config schema --help"
    Then the stdout matches:
      | pattern                              |
      | Usage: isaac config schema           |
      | --tree\s+Expand every named           |
    And the exit code is 0

  # ----- Set -----

  Scenario: set writes a new crew member to isaac.edn by default
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:main {}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}
                  :gpt   {:model "gpt-5.4" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config set crew.cordelia.model gpt"
    Then the config file "isaac.edn" matches:
      | pattern       |
      | :cordelia       |
      | :model\s+:gpt |
    And the log has entries matching:
      | level | event       | path              | value | file       |
      | :info | :config/set | crew.cordelia.model | :gpt  | isaac.edn  |
    And the exit code is 0

  Scenario: set writes to the existing entity file when one already defines the key
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:main {}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}
                  :gpt   {:model "gpt-5.4" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And config file "crew/cordelia.edn" containing:
      """
      {:model :llama}
      """
    When isaac is run with "config set crew.cordelia.model gpt"
    Then the config file "crew/cordelia.edn" matches:
      | pattern       |
      | :model\s+:gpt |
    And the config file "isaac.edn" does not contain "cordelia"
    And the log has entries matching:
      | level | event       | path              | value | file            |
      | :info | :config/set | crew.cordelia.model | :gpt  | crew/cordelia.edn |
    And the exit code is 0

  Scenario: set writes to isaac.edn when the entity is already defined there
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:main   {}
                   :cordelia {:model :llama}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}
                  :gpt   {:model "gpt-5.4" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config set crew.cordelia.model gpt"
    Then the config file "isaac.edn" matches:
      | pattern       |
      | :cordelia       |
      | :model\s+:gpt |
    And the config file "crew/cordelia.edn" does not exist
    And the log has entries matching:
      | level | event       | path              | value | file      |
      | :info | :config/set | crew.cordelia.model | :gpt  | isaac.edn |
    And the exit code is 0

  Scenario: set writes new entities to entity files when prefer-entity-files is true
    Given config file "isaac.edn" containing:
      """
      {:defaults            {:crew :main :model :llama}
       :prefer-entity-files true
       :crew                {:main {}}
       :models              {:llama {:model "llama3.3:1b" :provider :anthropic}
                             :gpt   {:model "gpt-5.4" :provider :anthropic}}
       :providers           {:anthropic {}}}
      """
    When isaac is run with "config set crew.cordelia.model gpt"
    Then the config file "crew/cordelia.edn" matches:
      | pattern       |
      | :model\s+:gpt |
    And the config file "isaac.edn" does not contain "cordelia"
    And the log has entries matching:
      | level | event       | path              | value | file            |
      | :info | :config/set | crew.cordelia.model | :gpt  | crew/cordelia.edn |
    And the exit code is 0

  Scenario: set writes soul to the companion .md when it already exists
    Given config file "models/gpt.edn" containing:
      """
      {:model "gpt-5.4" :provider :anthropic}
      """
    And config file "providers/anthropic.edn" containing:
      """
      {}
      """
    Given config file "crew/cordelia.edn" containing:
      """
      {:model :gpt}
      """
    And config file "crew/cordelia.md" containing:
      """
      Old soul.
      """
    When isaac is run with "config set crew.cordelia.soul \"New soul.\""
    Then the config file "crew/cordelia.md" matches:
      | pattern   |
      | New soul. |
    And the config file "crew/cordelia.edn" does not contain ":soul"
    And the log has entries matching:
      | level | event       | path             | value     | file           |
      | :info | :config/set | crew.cordelia.soul | New soul. | crew/cordelia.md |
    And the exit code is 0

  Scenario: set creates a companion .md when a new soul exceeds 64 characters
    Given config file "models/gpt.edn" containing:
      """
      {:model "gpt-5.4" :provider :anthropic}
      """
    And config file "providers/anthropic.edn" containing:
      """
      {}
      """
    Given config file "crew/cordelia.edn" containing:
      """
      {:model :gpt}
      """
    When isaac is run with "config set crew.cordelia.soul \"You are Cordelia, first mate of the Marigold, steady-handed, sharp-eyed, and always three moves ahead of the weather.\""
    Then the config file "crew/cordelia.md" matches:
      | pattern            |
      | You are Cordelia   |
      | sharp-eyed         |
    And the config file "crew/cordelia.edn" does not contain ":soul"
    And the log has entries matching:
      | level | event       | path             | value                                                                                                            | file           |
      | :info | :config/set | crew.cordelia.soul | You are Cordelia, first mate of the Marigold, steady-handed, sharp-eyed, and always three moves ahead of the weather. | crew/cordelia.md |
    And the exit code is 0

  Scenario: set writes short soul inline in the entity file
    Given config file "models/gpt.edn" containing:
      """
      {:model "gpt-5.4" :provider :anthropic}
      """
    And config file "providers/anthropic.edn" containing:
      """
      {}
      """
    Given config file "crew/cordelia.edn" containing:
      """
      {:model :gpt}
      """
    When isaac is run with "config set crew.cordelia.soul \"First mate.\""
    Then the config file "crew/cordelia.edn" matches:
      | pattern                      |
      | :soul\s+"First mate\." |
    And the config file "crew/cordelia.md" does not exist
    And the log has entries matching:
      | level | event       | path             | value              | file            |
      | :info | :config/set | crew.cordelia.soul | First mate. | crew/cordelia.edn |
    And the exit code is 0

  Scenario: set refuses to write a value that fails type validation
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:main {}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config set crew.cordelia.effort not-a-number"
    Then the stderr contains "effort"
    And the config file "isaac.edn" does not contain "not-a-number"
    And the exit code is 1

  Scenario: set errors on a path the schema does not recognize
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:main {}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config set crew.main.experimental true"
    Then the stderr contains "experimental"
    And the exit code is 1

  @wip
  Scenario: set on a module-provided comm field does not warn unknown key
    Given config file "isaac.edn" containing:
      """
      {:modules {:isaac.comm.telly {:local/root "modules/isaac.comm.telly"}}
       :comms   {:bert {:type :telly :crew :main}}
       :crew    {:main {}}}
      """
    When isaac is run with "config set comms.bert.loft rooftop"
    Then the stderr does not contain "comms.bert.loft"
    And the config file "isaac.edn" matches:
      | pattern           |
      | :loft\s+"rooftop" |
    And the exit code is 0

  @wip
  Scenario: set on an unknown comm field still warns via the loader
    Given config file "isaac.edn" containing:
      """
      {:modules {:isaac.comm.telly {:local/root "modules/isaac.comm.telly"}}
       :comms   {:bert {:type :telly :crew :main}}
       :crew    {:main {}}}
      """
    When isaac is run with "config set comms.bert.bogus 42"
    Then the stderr matches:
      | pattern            |
      | warning            |
      | comms\.bert\.bogus |
      | unknown key        |
    And the exit code is 0

  @wip
  Scenario: set warns when the comm's module is not declared
    Given config file "isaac.edn" containing:
      """
      {:comms {:bert {:type :telly :crew :main}}
       :crew  {:main {}}}
      """
    When isaac is run with "config set comms.bert.loft rooftop"
    Then the stderr matches:
      | pattern           |
      | warning           |
      | comms\.bert\.loft |
      | unknown key       |
    And the exit code is 0

  @wip
  Scenario: set warns when the comm has no :type yet
    Given config file "isaac.edn" containing:
      """
      {:modules {:isaac.comm.telly {:local/root "modules/isaac.comm.telly"}}
       :comms   {:bert {:crew :main}}
       :crew    {:main {}}}
      """
    When isaac is run with "config set comms.bert.loft rooftop"
    Then the stderr matches:
      | pattern           |
      | warning           |
      | comms\.bert\.loft |
      | unknown key       |
    And the exit code is 0

  # ----- Unset -----

  Scenario: unset removes a key from the file where it lives
    Given config file "models/gpt.edn" containing:
      """
      {:model "gpt-5.4" :provider :anthropic}
      """
    And config file "providers/anthropic.edn" containing:
      """
      {}
      """
    Given config file "crew/cordelia.edn" containing:
      """
      {:model :gpt :soul "Paranoid."}
      """
    When isaac is run with "config unset crew.cordelia.soul"
    Then the config file "crew/cordelia.edn" matches:
      | pattern       |
      | :model\s+:gpt |
    And the config file "crew/cordelia.edn" does not contain ":soul"
    And the log has entries matching:
      | level | event         | path             | file            |
      | :info | :config/unset | crew.cordelia.soul | crew/cordelia.edn |
    And the exit code is 0

  Scenario: unset that empties an entity file deletes it
    Given config file "models/gpt.edn" containing:
      """
      {:model "gpt-5.4" :provider :anthropic}
      """
    And config file "providers/anthropic.edn" containing:
      """
      {}
      """
    Given config file "crew/cordelia.edn" containing:
      """
      {:model :gpt}
      """
    When isaac is run with "config unset crew.cordelia.model"
    Then the config file "crew/cordelia.edn" does not exist
    And the log has entries matching:
      | level | event         | path              | file            |
      | :info | :config/unset | crew.cordelia.model | crew/cordelia.edn |
    And the exit code is 0

  Scenario: set writes a whole entity read from stdin
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:main {}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And stdin is:
      """
      {:base-url "https://api.x.ai/v1" :api-key "${GROK_API_KEY}" :api "chat-completions"}
      """
    When isaac is run with "config set providers.grok -"
    Then the config file "isaac.edn" matches:
      | pattern                             |
      | :grok                               |
      | :base-url\s+"https://api\.x\.ai/v1" |
      | :api\s+"chat-completions"         |
    And the log has entries matching:
      | level | event       | path           | value              | file      |
      | :info | :config/set | providers.grok | #".*api\.x\.ai/v1.*" | isaac.edn |
    And the exit code is 0

  Scenario: set replaces an existing entity rather than merging
    Given config file "providers/grok.edn" containing:
      """
      {:base-url "https://old.example.com" :api-key "${OLD_KEY}" :api "chat-completions"}
      """
    And stdin is:
      """
      {:base-url "https://api.x.ai/v1" :api-key "${GROK_API_KEY}"}
      """
    When isaac is run with "config set providers.grok -"
    Then the config file "providers/grok.edn" matches:
      | pattern                             |
      | :base-url\s+"https://api\.x\.ai/v1" |
      | :api-key\s+"\$\{GROK_API_KEY\}"     |
    And the config file "providers/grok.edn" does not contain "old.example.com"
    And the config file "providers/grok.edn" does not contain ":api "
    And the log has entries matching:
      | level | event       | path           | value                  | file               |
      | :info | :config/set | providers.grok | #".*\$\{GROK_API_KEY\}.*" | providers/grok.edn |
    And the exit code is 0

  Scenario: config help lists set and unset subcommands
    When isaac is run with "help config"
    Then the stdout matches:
      | pattern                                                 |
      | set <config-path> <value>\s+Set a value at a config path |
      | unset <config-path>\s+Remove a value at a config path    |
    And the exit code is 0

  Scenario: config set --help documents stdin form and examples
    When isaac is run with "config set --help"
    Then the stdout matches:
      | pattern                                       |
      | Usage: isaac config set <config-path>         |
      | -\s+Read the value as EDN from stdin          |
    And the exit code is 0
