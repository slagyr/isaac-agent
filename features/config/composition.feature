Feature: Config Composition
  Isaac loads configuration from ~/.isaac/config/. The root isaac.edn and
  per-entity files (crew/<id>.edn, models/<id>.edn, providers/<id>.edn)
  compose additively. Filenames define entity ids. Duplicate ids across
  sources are rejected. ${VAR} substitutions resolve through c3kit's env
  precedence (overrides, system props, OS env, .env file).

  Background:
    Given an Isaac root at "isaac-state"

  # ----- Soul -----

  Scenario: soul loads from a companion .md file when :soul is absent
    Given config file "crew/cordelia.edn" containing:
      """
      {:model :llama}
      """
    And config file "crew/cordelia.md" containing:
      """
      You are Cordelia, first mate.
      """
    Then the loaded config has:
      | key                | value                         |
      | crew.cordelia.soul | You are Cordelia, first mate. |

  Scenario: defining soul in both :soul and <id>.md is an error
    Given config file "crew/cordelia.edn" containing:
      """
      {:soul "Inline soul."}
      """
    And config file "crew/cordelia.md" containing:
      """
      File soul.
      """
    Then the config has validation errors matching:
      | key                | value                      |
      | crew.cordelia.soul | must be set in .edn OR .md |

  # ----- Semantic validation -----

  Scenario: defaults.frequencies.crew must reference an existing crew
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew :ghost} :crew {:model :llama}}}
      """
    Then the config has validation errors matching:
      | key                       | value                     |
      | defaults.frequencies.crew | references undefined crew |

  Scenario: defaults.crew.model must reference an existing model
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew :main} :crew {:model :nonexistent}}}
      """
    Then the config has validation errors matching:
      | key                 | value                      |
      | defaults.crew.model | references undefined model |

  Scenario: crew.model must reference an existing model
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew :main} :crew {:model :llama}}
       :crew     {:cordelia {:model :gpt}}}
      """
    Then the config has validation errors matching:
      | key                 | value                      |
      | crew.cordelia.model | references undefined model |

  Scenario: model.provider must reference an existing provider
    # Phase 7 of brth (isaac-ho18): :provider-exists? was replaced by
    # [:registered-in? :isaac.http/provider [:providers]]. The
    # validator picks the small-set form when ≤5 ids are accepted.
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:frequencies {:crew :main} :crew {:model :llama}}
       :providers {:ollama {:base-url "http://localhost:11434" :api "ollama"}}
       :models    {:grover {:model "claude-opus-4-7" :provider :foo :context-window 200000}}}
      """
    Then the config has validation errors matching:
      | key                    | value          |
      | models.grover.provider | must be one of |

  # ----- Happy path across sources -----

  Scenario: crew references a model defined in models/<id>.edn
    Given config file "isaac.edn" containing:
      """
      {:defaults {:frequencies {:crew :main} :crew {:model :llama}}
       :crew     {:cordelia {:model :grover}}}
      """
    And config file "models/grover.edn" containing:
      """
      {:model "claude-opus-4-7" :provider :grover :context-window 200000}
      """
    Then the loaded config has:
      | key                    | value           |
      | crew.cordelia.model    | grover          |
      | models.grover.model    | claude-opus-4-7 |
      | models.grover.provider | grover          |

  # ----- Defaults / empty -----

  Scenario: no config files yields the built-in default config
    Then the loaded config has:
      | key                       | value       |
      | defaults.frequencies.crew | main        |
      | defaults.crew.model       | llama       |
      | models.llama.model        | llama3.3:1b |
      | models.llama.provider     | ollama      |
