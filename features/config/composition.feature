Feature: Config Composition
  Isaac loads configuration from ~/.isaac/config/. The root isaac.edn and
  per-entity files (crew/<id>.edn, models/<id>.edn, providers/<id>.edn)
  compose additively. Filenames define entity ids. Duplicate ids across
  sources are rejected. ${VAR} substitutions resolve through c3kit's env
  precedence (overrides, system props, OS env, .env file).

  Background:
    Given an Isaac root at "isaac-state"

  # ----- Shape: map-by-id -----

  Scenario: crew members are keyed by id
    Given config file "isaac.edn" containing:
      """
      {:crew {:main {:soul "You are Atticus."}}}
      """
    Then the loaded config has:
      | key            | value          |
      | crew.main.soul | You are Atticus. |

  Scenario: loads a crew member from crew/<id>.edn
    Given config file "crew/cordelia.edn" containing:
      """
      {:model :llama :soul "You are Cordelia."}
      """
    Then the loaded config has:
      | key               | value           |
      | crew.cordelia.model | llama           |
      | crew.cordelia.soul  | You are Cordelia. |

  Scenario: loads a crew member from crew/<id>.md frontmatter
    Given config file "isaac.edn" containing:
      """
      {:models    {:llama {:model "llama3.2" :provider :ollama}}
       :providers {:ollama {:api "ollama"}}}
      """
    And config file "crew/cordelia.md" containing:
      """
      ---
      model: llama
      ---

      You are Cordelia.
      """
    Then the loaded config has:
      | key               | value           |
      | crew.cordelia.model | llama           |
      | crew.cordelia.soul  | You are Cordelia. |

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
      | key              | value                             |
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
      | key              | value                      |
      | crew.cordelia.soul | must be set in .edn OR .md |

  # ----- Filename / id -----

  Scenario: derives crew id from filename when :id is not specified
    Given config file "crew/ketch.edn" containing:
      """
      {:model :llama}
      """
    Then the loaded config has:
      | key              | value |
      | crew.ketch.model | llama |

  Scenario: explicit :id must match filename
    Given config file "crew/cordelia.edn" containing:
      """
      {:id "ketch" :model :llama}
      """
    Then the config has validation errors matching:
      | key            | value                               |
      | crew.cordelia.id | must match filename \(got "ketch"\) |

  # ----- Unknown keys warn but do not fail -----

  Scenario: unknown keys in entity files produce warnings but still load
    Given config file "crew/cordelia.edn" containing:
      """
      {:crew {:cordelia {:model :llama}}}
      """
    Then the config has validation warnings matching:
      | key              | value       |
      | crew.cordelia.crew | unknown key |

  # ----- Composition (additive) -----

  Scenario: composes crew from isaac.edn and crew/*.edn additively
    Given config file "isaac.edn" containing:
      """
      {:crew {:main {:soul "Atticus"}}}
      """
    And config file "crew/cordelia.edn" containing:
      """
      {:model :llama :soul "Cordelia"}
      """
    Then the loaded config has:
      | key               | value  |
      | crew.main.soul    | Atticus  |
      | crew.cordelia.soul  | Cordelia |
      | crew.cordelia.model | llama  |

  Scenario: composes models from isaac.edn and models/*.edn additively
    Given config file "isaac.edn" containing:
      """
      {:models {:ollama-local {:model "qwen3-coder:30b" :provider :ollama :context-window 32768}}}
      """
    And config file "models/grover.edn" containing:
      """
      {:model "claude-opus-4-7" :provider :grover :context-window 200000}
      """
    Then the loaded config has:
      | key                          | value           |
      | models.ollama-local.model    | qwen3-coder:30b |
      | models.ollama-local.provider | ollama          |
      | models.grover.model          | claude-opus-4-7 |
      | models.grover.provider       | grover          |

  Scenario: composes providers from isaac.edn and providers/*.edn additively
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :llama}
       :providers {:ollama {:base-url "http://localhost:11434" :api "ollama"}}}
      """
    And config file "providers/anthropic.edn" containing:
      """
      {:base-url "https://api.anthropic.com" :api "anthropic" :api-key "${CONFIG_TEST_ANTHROPIC_API_KEY}"}
      """
    Then the loaded config has:
      | key                        | value                  |
      | providers.ollama.base-url   | http://localhost:11434 |
      | providers.anthropic.api    | anthropic              |
      | providers.anthropic.api-key | ${CONFIG_TEST_ANTHROPIC_API_KEY}   |

  # ----- Duplicate ids across sources are hard errors -----

  Scenario: duplicate crew id across isaac.edn and crew/*.edn is a hard error
    Given config file "isaac.edn" containing:
      """
      {:crew {:cordelia {:soul "First"}}}
      """
    And config file "crew/cordelia.edn" containing:
      """
      {:soul "Second"}
      """
    Then the config has validation errors matching:
      | key         | value                                           |
      | crew.cordelia | defined in both isaac\.edn and crew/cordelia\.edn |

  Scenario: duplicate model id across isaac.edn and models/*.edn is a hard error
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :models   {:grover {:model "claude-opus-4-6" :provider :grover :context-window 200000}}}
      """
    And config file "models/grover.edn" containing:
      """
      {:model "claude-opus-4-7" :provider :grover :context-window 200000}
      """
    Then the config has validation errors matching:
      | key           | value                                             |
      | models.grover | defined in both isaac\.edn and models/grover\.edn |

  # ----- Semantic validation -----

  Scenario: defaults.crew must reference an existing crew
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :ghost :model :llama}}
      """
    Then the config has validation errors matching:
      | key           | value                     |
      | defaults.crew | references undefined crew |

  Scenario: defaults.model must reference an existing model
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :nonexistent}}
      """
    Then the config has validation errors matching:
      | key            | value                      |
      | defaults.model | references undefined model |

  Scenario: crew.model must reference an existing model
    Given config file "isaac.edn" containing:
      """
      {:defaults {:crew :main :model :llama}
       :crew     {:cordelia {:model :gpt}}}
      """
    Then the config has validation errors matching:
      | key               | value                      |
      | crew.cordelia.model | references undefined model |

  Scenario: model.provider must reference an existing provider
    # Phase 7 of brth (isaac-ho18): :provider-exists? was replaced by
    # [:registered-in? :isaac.server/provider [:providers]]. The
    # validator picks the small-set form when ≤5 ids are accepted.
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :llama}
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
      {:defaults {:crew :main :model :llama}
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
      | key                   | value       |
      | defaults.crew         | main        |
      | defaults.model        | llama       |
      | models.llama.model    | llama3.3:1b |
      | models.llama.provider | ollama      |

  # ----- Syntax -----

  Scenario: malformed EDN in a config file is reported with the file path
    Given config file "crew/cordelia.edn" containing:
      """
      {:model :llama
      """
    Then the config has validation errors matching:
      | key             | value            |
      | crew/cordelia.edn | EDN syntax error |

  # ----- Env substitution -----

  Scenario: ${VAR} references are substituted from the environment
    Given environment variable "ANTHROPIC_API_KEY" is "sk-test-123"
    And config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :llama}
       :providers {:anthropic {:base-url "https://api.anthropic.com"
                               :api     "anthropic"
                               :api-key  "${ANTHROPIC_API_KEY}"}}}
      """
    Then the loaded config has:
      | key                        | value       |
      | providers.anthropic.api-key | sk-test-123 |
