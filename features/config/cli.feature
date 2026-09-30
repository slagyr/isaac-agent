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
      | pattern                                                       |
      | Usage: isaac config \[subcommand\] \[options\]                |
      | Manage Isaac configuration                                    |
      | Subcommands:                                                  |
      | validate\s+Validate config                                    |
      | get \[config-path\]\s+Print the resolved config, or a subtree |
      | sources\s+List contributing config files                      |
    And the exit code is 0

  Scenario: config validate has its own help page via --help
    When isaac is run with "config validate --help"
    Then the stdout matches:
      | pattern                                        |
      | Usage: isaac config validate \[options\] \[-\] |
      | Validate the config composition                |
      | Options:                                       |
      | --as CONFIG-PATH\s+Overlay stdin EDN           |
      | Arguments:                                     |
      | -\s+Read EDN to validate from stdin            |
    And the exit code is 0

  Scenario: config help validate is an alternate way to reach subcommand help
    When isaac is run with "config help validate"
    Then the stdout matches:
      | pattern                                        |
      | Usage: isaac config validate \[options\] \[-\] |
      | Validate the config composition                |
    And the exit code is 0

  # ----- Validate -----

  Scenario: validate reports unknown llm api refs with file and valid set
    # Phase 7 of brth (isaac-ho18): :llm-api-exists? was replaced by
    # [:registered-in? :isaac.http/llm-api]. The validator's failure
    # message changed from "unknown api" to the berth-namespaced form,
    # but bad-value / file / valid-set rendering is preserved.
    Given config file "providers/bogus.edn" containing:
      """
      {:api "carrier-pigeon" :base-url "https://example.com" :auth "api-key" :api-key "test"}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                                                |
      | providers\.bogus\.api                                                  |
      | must be (a registered contribution to :isaac\.agent/llm-api)?(one of)? |
      | file: config/providers/bogus\.edn                                      |
      | bad value: carrier-pigeon                                              |
      | valid: .*chat-completions.*                                            |
    And the exit code is 1

  Scenario: validate requires defaults.frequencies.crew
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew {:model :llama}}
       :crew      {:yopp {}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                    |
      | defaults\.frequencies\.crew |
    And the exit code is 1

  Scenario: validate reports unknown tool refs with file and valid set
    # Phase 6 of the berth epic (isaac-w7o5) replaced the legacy
    # :tool-exists? validator with [:registered-in? :isaac.agent/tools].
    # The new validator says "must be a registered contribution to
    # :isaac.agent/tools" rather than "references undefined tool", but
    # still reports the bad value, source file, and known-set.
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :main} :crew {:model :local}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And config file "crew/main.edn" containing:
      """
      {:tools {:allow [:bogus-tool]}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                  |
      | crew\.main\.tools\.allow |
      | namespace                |
    And the exit code is 1

  Scenario: validate reports unknown provider refs with file and valid set
    # Phase 7 of brth (isaac-ho18): :provider-exists? was replaced by
    # [:registered-in? :isaac.http/provider [:providers]]. Wording
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
      | pattern                         |
      | models\.grover\.provider        |
      | must be one of                  |
      | file: config/models/grover\.edn |
      | bad value: foo                  |
      | valid: .*anthropic.*grover.*    |
    And the exit code is 1

  Scenario: validate reports unknown comm type refs with file and valid set
    # Phase 8 of brth (isaac-qqgv) replaced :comm-exists? with
    # [:registered-in? :isaac.agent/comm [:comms]]. The validator's
    # identity message shifted accordingly; bad-value / file / valid-set
    # rendering is preserved.
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :main} :crew {:model :local}}
       :crew      {:main {}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}
       :modules   {:isaac.comm.telly {:local/root "modules/isaac.comm.telly"}}
       :comms     {:relay {:type :smoke-signals :crew :main}}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                  |
      | comms\[:relay\]\.type    |
      | must be one of           |
      | file: config/isaac\.edn  |
      | bad value: smoke-signals |
      | valid: .*telly.*         |
    And the exit code is 1

  Scenario: validate warns when a crew directory includes the Isaac state root (isaac-dwjy)
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :scrapper} :crew {:model :llama}}
       :crew      {:scrapper {:tools {:directories {:allow ["/isaac-state"]}}}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                      |
      | warning: :crew\.scrapper\.tools\.directories |
      | Isaac state directory                        |
      | :cwd                                         |
    And the stdout contains "OK"
    And the exit code is 0

  Scenario: validate rejects the retired :role directory token
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :main} :crew {:model :llama :tools {:directories {:allow [:role]}}}}
       :crew      {:main {}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                   |
      | tools\.directories\.allow |
      | must be :cwd              |
    And the exit code is 1

  # ----- Set -----

  Scenario: set writes a new crew member to isaac.edn by default
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew :main} :crew {:model :llama}}
       :crew     {:main {}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}
                  :gpt   {:model "gpt-5.4" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config set crew.cordelia.model gpt"
    Then the config file "isaac.edn" matches:
      | pattern       |
      | :cordelia     |
      | :model\s+:gpt |
    And the log has entries matching:
      | level | event       | path                | value | file      |
      | :info | :config/set | crew.cordelia.model | :gpt  | isaac.edn |
    And the exit code is 0

  Scenario: set writes to the existing entity file when one already defines the key
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew :main} :crew {:model :llama}}
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
      | level | event       | path                | value | file              |
      | :info | :config/set | crew.cordelia.model | :gpt  | crew/cordelia.edn |
    And the exit code is 0

  Scenario: set edits the frontmatter of an entity that lives in <id>.md
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew :main} :crew {:model :llama}}
       :crew     {:main {}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}
                  :gpt   {:model "gpt-5.4" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And config file "crew/cordelia.md" containing:
      """
      ---
      model: llama
      ---

      You are Cordelia.
      """
    When isaac is run with "config set crew.cordelia.model gpt"
    Then the config file "crew/cordelia.md" matches:
      | pattern           |
      | model:\s+:?gpt    |
      | You are Cordelia\. |
    And the config file "crew/cordelia.edn" does not exist
    And the log has entries matching:
      | level | event       | path                | value | file             |
      | :info | :config/set | crew.cordelia.model | :gpt  | crew/cordelia.md |
    And the exit code is 0
    When isaac is run with "config get crew.cordelia.model"
    Then the stdout contains "gpt"
    And the exit code is 0

  Scenario: unset removes a frontmatter field from an entity that lives in <id>.md
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew :main} :crew {:model :llama}}
       :crew     {:main {}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And config file "crew/cordelia.md" containing:
      """
      ---
      model: llama
      effort: 3
      ---

      You are Cordelia.
      """
    When isaac is run with "config unset crew.cordelia.effort"
    Then the config file "crew/cordelia.md" does not contain "effort"
    And the config file "crew/cordelia.md" matches:
      | pattern           |
      | model:\s+:?llama  |
      | You are Cordelia\. |
    And the config file "crew/cordelia.edn" does not exist
    And the exit code is 0
    When isaac is run with "config get crew.cordelia"
    Then the stdout does not contain "effort"
    And the exit code is 0

  Scenario: set writes to isaac.edn when the entity is already defined there
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew :main} :crew {:model :llama}}
       :crew     {:main   {}
                   :cordelia {:model :llama}}
       :models   {:llama {:model "llama3.3:1b" :provider :anthropic}
                  :gpt   {:model "gpt-5.4" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config set crew.cordelia.model gpt"
    Then the config file "isaac.edn" matches:
      | pattern       |
      | :cordelia     |
      | :model\s+:gpt |
    And the config file "crew/cordelia.edn" does not exist
    And the log has entries matching:
      | level | event       | path                | value | file      |
      | :info | :config/set | crew.cordelia.model | :gpt  | isaac.edn |
    And the exit code is 0

  Scenario: set writes new entities to entity files when prefer-entity-files is true
    Given config file "isaac.edn" containing:
      """
      {:defaults            {:frequencies {:crew :main} :crew {:model :llama}}
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
      | level | event       | path                | value | file              |
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
      | level | event       | path               | value     | file             |
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
      | pattern          |
      | You are Cordelia |
      | sharp-eyed       |
    And the config file "crew/cordelia.edn" does not contain ":soul"
    And the log has entries matching:
      | level | event       | path               | value                                                                                                                 | file             |
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
      | pattern                |
      | :soul\s+"First mate\." |
    And the config file "crew/cordelia.md" does not exist
    And the log has entries matching:
      | level | event       | path               | value       | file              |
      | :info | :config/set | crew.cordelia.soul | First mate. | crew/cordelia.edn |
    And the exit code is 0

  Scenario: set refuses to write a value that fails type validation
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew :main} :crew {:model :llama}}
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
      {:defaults {:frequencies {:crew :main} :crew {:model :llama}}
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
      | level | event         | path               | file              |
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
      | level | event         | path                | file              |
      | :info | :config/unset | crew.cordelia.model | crew/cordelia.edn |
    And the exit code is 0

  Scenario: set writes a whole entity read from stdin
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew :main} :crew {:model :llama}}
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
      | :api\s+"chat-completions"           |
    And the log has entries matching:
      | level | event       | path           | value                | file      |
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
      | level | event       | path           | value                     | file               |
      | :info | :config/set | providers.grok | #".*\$\{GROK_API_KEY\}.*" | providers/grok.edn |
    And the exit code is 0

  Scenario: config help lists set and unset subcommands
    When isaac is run with "help config"
    Then the stdout matches:
      | pattern                                                  |
      | set <config-path> <value>\s+Set a value at a config path |
      | unset <config-path>\s+Remove a value at a config path    |
    And the exit code is 0

  Scenario: config set --help documents stdin form and examples
    When isaac is run with "config set --help"
    Then the stdout matches:
      | pattern                               |
      | Usage: isaac config set <config-path> |
      | -\s+Read the value as EDN from stdin  |
    And the exit code is 0

  # ----- Keys / list (isaac-grof) -----

  Scenario: config keys prints bare key names at a path
    Given config file "providers/xai.edn" containing:
      """
      {:api "responses" :base-url "https://api.x.ai/v1" :auth "api-key" :api-key "sk-real-secret-value"}
      """
    And config file "providers/grok.edn" containing:
      """
      {:type :grok}
      """
    When isaac is run with "config keys providers"
    Then the stdout contains "xai"
    And the stdout contains "grok"
    And the stdout does not contain "config/providers"
    And the stdout does not contain "https://api.x.ai/v1"
    And the exit code is 0

  Scenario: config keys with no path lists root keys
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :main} :crew {:model :llama}}
       :crew      {:main {}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config keys"
    Then the stdout contains "defaults"
    And the stdout contains "crew"
    And the stdout contains "models"
    And the stdout contains "providers"
    And the stdout does not contain "module-registry"
    And the stdout does not contain "llama3.3"
    And the exit code is 0

  Scenario: config list prints keys with their config source
    Given config file "providers/xai.edn" containing:
      """
      {:api "responses" :base-url "https://api.x.ai/v1" :auth "api-key" :api-key "sk-real-secret-value"}
      """
    And config file "providers/grok.edn" containing:
      """
      {:type :grok}
      """
    When isaac is run with "config list providers"
    Then the stdout contains "xai"
    And the stdout contains "config/providers/xai.edn"
    And the stdout does not contain "sk-real-secret-value"
    And the exit code is 0

  Scenario: config list with no path lists root keys and sources
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :main} :crew {:model :llama}}
       :crew      {:main {}}
       :models    {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config list"
    Then the stdout contains "defaults"
    And the stdout contains "config/isaac.edn"
    And the stdout does not contain "llama3.3"
    And the exit code is 0

  Scenario: a leaf path prints nothing
    Given config file "providers/xai.edn" containing:
      """
      {:api "responses" :base-url "https://api.x.ai/v1" :auth "api-key" :api-key "sk-real-secret-value"}
      """
    And config file "providers/grok.edn" containing:
      """
      {:type :grok}
      """
    When isaac is run with "config keys providers.xai.base-url"
    Then the stdout is empty
    And the exit code is 0

  Scenario: keys and list emit structured output under --json
    Given config file "providers/xai.edn" containing:
      """
      {:api "responses" :base-url "https://api.x.ai/v1" :auth "api-key" :api-key "sk-real-secret-value"}
      """
    And config file "providers/grok.edn" containing:
      """
      {:type :grok}
      """
    When isaac is run with "config keys providers --json"
    Then the stdout JSON contains:
      | path | expected |
      | 0    | "grok"   |
      | 1    | "ollama" |
      | 2    | "xai"    |
    When isaac is run with "config list providers --json"
    Then the stdout contains "\"source\""
    And the stdout does not contain "sk-real-secret-value"
    And the exit code is 0

  Scenario: config validate --json emits structured warnings
    Given config file "isaac.edn" containing:
      """
      {:defaults     {:frequencies {:crew :main} :crew {:model :llama}}
       :crew         {:main {}}
       :models       {:llama {:model "llama3.3:1b" :provider :anthropic}}
       :providers    {:anthropic {}}
       :experimental {:feature-flag true}}
      """
    When isaac is run with "config validate --json"
    Then the stdout parses as JSON with a warnings array naming the offending path
    And the exit code is 0

  Scenario: validate accepts a crew on a session policy contributed by an installed module
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :cordelia} :crew {:model :local}}
       :crew      {:cordelia {:session-policy :lantern}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}
       :modules   {:isaac.session.lantern {:local/root "modules/isaac.session.lantern"}}}
      """
    When isaac is run with "config validate"
    Then the stdout contains "OK"
    And the stderr does not contain "undefined session policy"
    And the exit code is 0

  Scenario: validate still rejects an unknown session policy and names the module's known set
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :cordelia} :crew {:model :local}}
       :crew      {:cordelia {:session-policy :ledger}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}
       :modules   {:isaac.session.lantern {:local/root "modules/isaac.session.lantern"}}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                                                         |
      | crew\.cordelia\.session-policy                                                  |
      | references undefined session policy \(got "ledger"\); known: chronicle, lantern |
    And the exit code is 1
