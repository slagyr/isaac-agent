Feature: OpenAI Authentication
  Isaac authenticates with the OpenAI API using an API key.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/gpt.edn" exists with:
      | path | value |
      | model | gpt-5 |
      | provider | openai |
      | context-window | 128000 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | gpt |
      | soul | You are Atticus. |

  @slow
  Scenario: Live OpenAI API call
    Given the provider "openai" is configured with:
      | key     | value                     |
      | api-key | ${OPENAI_API_KEY}         |
      | base-url | https://api.openai.com/v1 |
      | api     | chat-completions        |
    And the following sessions exist:
      | name        |
      | openai-live |
    And session "openai-live" has transcript:
      | type    | message.role | message.content |
      | message | user         | Say "hello"     |
    When the user sends "Say \"hello\"" on session "openai-live"
    Then the live "openai" call succeeds or reports missing auth clearly
