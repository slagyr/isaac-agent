Feature: Anthropic API Key Authentication
  Isaac authenticates with the Anthropic Messages API using
  an API key from environment variables.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/claude.edn" exists with:
      | path | value |
      | model | claude-sonnet-4-6 |
      | provider | anthropic |
      | context-window | 200000 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | claude |
      | soul | You are Atticus. |
    And the isaac file "config/providers/anthropic.edn" exists with:
      """
      {}
      """

  @slow
  Scenario: Invalid API key returns auth error
    Given the provider "anthropic" is configured with:
      | key    | value          |
      | auth   | api-key        |
      | api-key | sk-ant-invalid |
    And the following sessions exist:
      | name              |
      | anthropic-invalid |
    And session "anthropic-invalid" has transcript:
      | type    | message.role | message.content |
      | message | user         | Hello           |
    When the user sends "Hello" on session "anthropic-invalid"
    Then an error is reported indicating authentication failed

  @slow
  Scenario: Live API key authentication
    Given the provider "anthropic" is configured with:
      | key     | value                     |
      | auth    | api-key                   |
      | api-key | ${ANTHROPIC_API_KEY}      |
      | base-url | https://api.anthropic.com |
    And the following sessions exist:
      | name           |
      | anthropic-live |
    And session "anthropic-live" has transcript:
      | type    | message.role | message.content |
      | message | user         | Say "hello"     |
    When the user sends "Say \"hello\"" on session "anthropic-live"
    Then the live "anthropic" call succeeds or reports missing auth clearly
