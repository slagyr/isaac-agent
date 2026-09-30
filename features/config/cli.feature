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
