Feature: Ollama Live Integration
  Isaac can talk to a real local Ollama server.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/local.edn" exists with:
      | path | value |
      | model | llama3.2:1b |
      | provider | ollama |
      | context-window | 32000 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | local |
      | soul | You are Atticus. |
    And the isaac file "config/providers/ollama.edn" exists with:
      """
      {}
      """

  @slow
  Scenario: Live Ollama chat
    Given the Ollama server is running
    And model "llama3.2:1b" is available in Ollama
    And the following sessions exist:
      | name        |
      | ollama-live |
    And session "ollama-live" has transcript:
      | type    | message.role | message.content |
      | message | user         | Say "hello"     |
    When the user sends "Say \"hello\"" on session "ollama-live"
    Then session "ollama-live" has transcript matching:
      | type    | message.role | message.provider |
      | message | assistant    | ollama           |

  @slow
  Scenario: Missing Ollama server reports a clear error
    Given the Ollama server is not running
    And the following sessions exist:
      | name         |
      | ollama-error |
    And session "ollama-error" has transcript:
      | type    | message.role | message.content |
      | message | user         | Hello           |
    When the user sends "Hello" on session "ollama-error"
    Then the log has entries matching:
      | level  | event                  |
      | :error | :chat/response-failed  |
