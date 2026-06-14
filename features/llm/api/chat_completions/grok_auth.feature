Feature: Grok Authentication
  Isaac authenticates with xAI's Grok API using an API key.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/grok.edn" exists with:
      | path | value |
      | model | grok-4-1-fast |
      | provider | grok |
      | context-window | 131072 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | grok |
      | soul | You are Atticus. |
    And the isaac file "config/providers/grok.edn" exists with:
      """
      {}
      """

  @slow
  Scenario: Invalid API key returns auth error
    Given the provider "grok" is configured with:
      | key     | value                |
      | api-key | invalid-key          |
      | base-url | https://api.x.ai/v1 |
      | api     | chat-completions   |
    And the following sessions exist:
      | name         |
      | grok-invalid |
    And session "grok-invalid" has transcript:
      | type    | message.role | message.content |
      | message | user         | Hello           |
    When the user sends "Hello" on session "grok-invalid"
    Then an error is reported indicating authentication failed

  @slow
  Scenario: Live Grok API call
    Given the provider "grok" is configured with:
      | key     | value                |
      | api-key | ${GROK_API_KEY}      |
      | base-url | https://api.x.ai/v1 |
      | api     | chat-completions   |
    And the following sessions exist:
      | name      |
      | grok-live |
    And session "grok-live" has transcript:
      | type    | message.role | message.content |
      | message | user         | Say "hello"     |
    When the user sends "Say \"hello\"" on session "grok-live"
    Then the live "grok" call succeeds or reports missing auth clearly
