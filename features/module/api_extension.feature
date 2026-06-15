Feature: Api extension
  Modules can ship new wire-format Api implementations by declaring
  :extends {:llm/api {<name> {:isaac/factory ...}}} in their manifest.
  Isaac resolves the factory lazily when the api is first used.

  An Api is the protocol-implementing code (chat, chat-stream,
  followup-messages, config, display-name) that adapts an upstream
  service's wire format. The Provider concept (separate, configured via
  :providers) points at one of these Apis with base-url, auth, and model
  list.

  Most third-party additions are Providers (data, no code) — see
  features/modules/provider_extension.feature. This feature covers the
  rarer case: shipping a brand-new wire format that isn't one of Isaac's
  built-in five (messages, chat-completions, responses,
  ollama, grover).

  Scenario: A module-shipped Api can serve a provider
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:modules   {:isaac.api.tin-can {:local/root "modules/isaac.api.tin-can"}}
       :providers {:tin-test {:api    "tin-can"
                              :auth   "none"
                              :models ["echo-1"]}}
       :crew      {:main {:provider :tin-test :model "echo-1"}}}
      """
    When the user sends "what is your purpose" on session "main" via memory comm
    Then the reply contains "tin-can heard: what is your purpose"

  Scenario: Module-shipped Api activation is logged
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:log       {:output :memory}
       :modules   {:isaac.api.tin-can {:local/root "modules/isaac.api.tin-can"}}
       :providers {:tin-test {:api    "tin-can"
                              :auth   "none"
                              :models ["echo-1"]}}
       :crew      {:main {:provider :tin-test :model "echo-1"}}}
      """
    When the user sends "ping" on session "main" via memory comm
    Then the log has entries matching:
      | level | event             | module            |
      | :info | :module/activated | isaac.api.tin-can |
    And the log has entries matching:
      | level | event           | api     |
      | :info | :api/registered | tin-can |

  Scenario: Provider validation fails when the api's module is not declared
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:providers {:tin-test {:api    "tin-can"
                              :auth   "none"
                              :models ["echo-1"]}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                    | value       |
      | providers.tin-test.api | must be one of |

  Scenario: Config validation fails for an unregistered api
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:providers {:bogus {:api    "carrier-pigeon"
                           :auth   "none"
                            :models ["mythical"]}}
       :crew      {:main {:provider :bogus :model "mythical"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                 | value       |
      | providers.bogus.api | must be one of |
