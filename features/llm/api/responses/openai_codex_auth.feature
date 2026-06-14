Feature: OpenAI Codex Authentication
  Isaac authenticates with ChatGPT-backed Codex models using
  device-code login state.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/codex.edn" exists with:
      | path | value |
      | model | gpt-5.4 |
      | provider | chatgpt |
      | context-window | 128000 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | codex |
      | soul | You are Atticus. |

  @slow
  Scenario: Live OpenAI Codex API call
    Given the provider "chatgpt" is configured with:
      | key      | value                                       |
      | auth     | oauth-device                                |
      | base-url | https://chatgpt.com/backend-api/codex       |
      | api      | responses                            |
    And the following sessions exist:
      | name       |
      | codex-live |
    And session "codex-live" has transcript:
      | type    | message.role | message.content |
      | message | user         | Say "hello"     |
    When the user sends "Say \"hello\"" on session "codex-live"
    Then the live "chatgpt" call succeeds or reports missing auth clearly
