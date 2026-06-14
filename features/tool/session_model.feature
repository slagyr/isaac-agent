Feature: session_model tool
  The session_model tool changes the calling session's model. Pass
  `model` to switch to a configured alias, or `reset: true` to
  revert to the crew's currently configured model. The two args
  are mutually exclusive. Returns the same JSON shape as
  session_info so the LLM sees the new state.

  Crew is intentionally not switchable — that would let the agent
  escalate its own tool permissions.

  Background:
    Given default Grover setup

  Scenario: session_model switches the session's model when model arg is provided
    Given the isaac EDN file "config/models/parrot.edn" exists with:
      | path            | value   |
      | model           | squawk  |
      | provider        | :grover |
      | context_window  | 16384   |
    And the following sessions exist:
      | name        | crew |
      | status-test | main |
    And the current session is "status-test"
    When the tool "session_model" is called with:
      | model | parrot |
    Then the tool result is not an error
    And the tool result JSON has:
      | path           | value  |
      | model.alias    | parrot |
      | model.upstream | squawk |
    And session "status-test" matches:
      | key   | value  |
      | model | parrot |

  Scenario: session_model reverts to the crew's default model when reset is true
    Given the isaac EDN file "config/models/parrot.edn" exists with:
      | path            | value   |
      | model           | squawk  |
      | provider        | :grover |
      | context_window  | 16384   |
    And the following sessions exist:
      | name        | crew | model  |
      | status-test | main | parrot |
    And the current session is "status-test"
    When the tool "session_model" is called with:
      | reset | true |
    Then the tool result is not an error
    And the tool result JSON has:
      | path           | value  |
      | model.alias    | grover |
      | model.upstream | echo   |
    And session "status-test" matches:
      | key   | value  |
      | model | grover |

  Scenario: session_model errors when both model and reset are provided
    Given the following sessions exist:
      | name        | crew |
      | status-test | main |
    And the current session is "status-test"
    When the tool "session_model" is called with:
      | model | grover |
      | reset | true   |
    Then the tool result is an error
    And the tool result contains "model and reset are mutually exclusive"

  Scenario: session_model errors when given an unknown model alias
    Given the following sessions exist:
      | name        | crew |
      | status-test | main |
    And the current session is "status-test"
    When the tool "session_model" is called with:
      | model | nonexistent |
    Then the tool result is an error
    And the tool result contains "unknown model: nonexistent"
