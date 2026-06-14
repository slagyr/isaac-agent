Feature: Error Entry Handling
  LLM errors must not poison the session transcript with invalid
  roles. Errors are stored as their own entry type, never as
  messages with role "error". The prompt builder excludes them.

  Background:
    Given default Grover setup

  Scenario: error response is stored as type error, not role error
    Given the following sessions exist:
      | name       |
      | error-test |
    And the following model responses are queued:
      | type  | content              | model |
      | error | something went wrong | echo  |
    When the user sends "hi" on session "error-test"
    Then session "error-test" has transcript matching:
      | type  |
      | error |
    And session "error-test" has no transcript entries with role "error"

  @wip
  Scenario: uncaught exception during a turn lands a closing error entry
    Given the following sessions exist:
      | name   |
      | crashy |
    And session "crashy" has transcript:
      | type    | message.role | message.content |
      | message | user         | earlier prompt  |
      | message | assistant    | earlier reply   |
    And the LLM throws an exception with message "wire format mismatch"
    When the user sends "next thing" on session "crashy"
    Then session "crashy" has transcript matching:
      | type    | message.role | message.content | content              |
      | message | user         | earlier prompt  |                      |
      | message | assistant    | earlier reply   |                      |
      | message | user         | next thing      |                      |
      | error   |              |                 | wire format mismatch |
    And the log has entries matching:
      | level  | event                | session |
      | :error | :session/turn-failed | crashy  |

  Scenario: error entries are excluded from the prompt
    Given the following sessions exist:
      | name       |
      | error-test |
    And session "error-test" has transcript:
      | type    | message.role | message.content |
      | message | user         | hi              |
    And session "error-test" has an error entry "something went wrong"
    And the following model responses are queued:
      | type | content     | model |
      | text | I recovered | echo  |
    When the prompt for session "error-test" is built for provider "openai"
    Then the prompt messages do not contain role "error"
