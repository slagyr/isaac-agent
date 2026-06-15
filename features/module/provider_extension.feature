Feature: Provider extension
  Providers are first-class extensions distinct from Apis. An Api is a
  wire-format adapter (messages, chat-completions, etc.) that
  ships as Clojure code. A Provider is configuration data pointing at one
  Api with a base-url, auth mode, and model list. xAI is a Provider that
  uses the chat-completions Api; Anthropic-via-corp-gateway is a
  Provider that inherits from the built-in :anthropic with an overridden
  base-url.

  Three contribution paths converge in the provider registry:
  - Built-in providers registered at startup (anthropic, chatgpt, ...)
  - Module-declared providers (manifest-only, no Clojure code required)
  - User-declared providers inline in isaac.edn

  All three are uniform sources for :type inheritance.

  Scenario: A user-declared provider is usable for a turn
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:providers {:xai {:api      "chat-completions"
                         :base-url "https://api.x.ai/v1"
                         :auth     "api-key"
                         :api-key  "xoxo-test-key"
                         :models   ["grok-2"]}}
       :crew      {:main {:provider :xai :model "grok-2"}}}
      """
    And provider transport succeeds immediately
    When the user sends "Hello, Grok" on session "main" via memory comm
    Then the last outbound HTTP request matches:
      | key                   | value                                |
      | url                   | https://api.x.ai/v1/chat/completions |
      | headers.Authorization | Bearer xoxo-test-key                 |
      | body.model            | grok-2                               |

  Scenario: A provider inherits defaults from another via :type
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:providers {:corp-anthropic {:type     :anthropic
                                    :base-url "https://anthropic.internal.corp"
                                    :api-key  "corp-secret-99"}}
       :crew      {:main {:provider :corp-anthropic :model "claude-sonnet-4-6"}}}
      """
    And provider transport succeeds immediately
    When the user sends "ping" on session "main" via memory comm
    Then the last outbound HTTP request matches:
      | key               | value                                       |
      | url               | https://anthropic.internal.corp/v1/messages |
      | headers.x-api-key | corp-secret-99                              |
      | body.model        | claude-sonnet-4-6                           |

  Scenario: A module-declared provider is usable without any module code
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules   {:isaac.providers.kombucha {:local/root "modules/isaac.providers.kombucha"}}
       :providers {:kombucha {:api-key "fizzy-secret"}}
       :crew      {:main {:provider :kombucha :model "kombucha-large"}}}
      """
    And provider transport succeeds immediately
    When the user sends "what flavor today" on session "main" via memory comm
    Then the last outbound HTTP request matches:
      | key                   | value                                         |
      | url                   | https://api.kombucha.test/v1/chat/completions |
      | headers.Authorization | Bearer fizzy-secret                           |
      | body.model            | kombucha-large                                |

  Scenario: A user-defined provider can inherit from a module-declared provider
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules   {:isaac.providers.kombucha {:local/root "modules/isaac.providers.kombucha"}}
       :providers {:fizzy-staging {:type    :kombucha
                                   :api-key "staging-key"}}
       :crew      {:main {:provider :fizzy-staging :model "kombucha-small"}}}
      """
    And provider transport succeeds immediately
    When the user sends "ping" on session "main" via memory comm
    Then the last outbound HTTP request matches:
      | key                   | value                                         |
      | url                   | https://api.kombucha.test/v1/chat/completions |
      | headers.Authorization | Bearer staging-key                            |
      | body.model            | kombucha-small                                |

  Scenario: A provider with an unknown :api is rejected at config-load
    # Phase 7 of brth (isaac-ho18) replaced :llm-api-exists? with
    # [:registered-in? :isaac.server/llm-api]. The error name shifted
    # accordingly.
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:providers {:bogus {:api      "carrier-pigeon"
                           :base-url "https://example.com"
                           :auth     "api-key"
                           :api-key  "test"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                 | value       |
      | providers.bogus.api | must be one of |

  Scenario: A provider with an unknown :type target is rejected
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:providers {:dreamy {:type    :ghost-provider
                            :api-key "test"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                   | value                                                      |
      | providers.dreamy.type | must be a registered contribution to :isaac.agent/provider-template |

  Scenario: User-supplied extra field is rejected when it violates the manifest :schema
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules   {:isaac.providers.kombucha {:local/root "modules/isaac.providers.kombucha"}}
       :providers {:my-kombucha {:type       :kombucha
                                 :api-key    "fizzy-secret"
                                 :fizz-level "seven"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                              | value                |
      | providers.my-kombucha.fizz-level | can't coerce .* to int |

  Scenario: :type referencing a user-only provider is rejected
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:providers {:home-anthropic {:api      "messages"
                                    :base-url "https://anthropic.home"
                                    :auth     "api-key"
                                    :api-key  "home-key"}
                   :work-anthropic {:type    :home-anthropic
                                    :api-key "work-key"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                           | value                                                      |
      | providers.work-anthropic.type | must be a registered contribution to :isaac.agent/provider-template |

  Scenario: Self-defined provider with auth api-key but no api key is rejected
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:providers {:my-thing {:api      "messages"
                              :base-url "https://example.test"
                              :auth     "api-key"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                       | value                  |
      | providers.my-thing.api-key | is required when .*api-key.* |
