Feature: /model Command
  The /model bridge command switches the session's active model.
  Subsequent turns use the new model. The change is stored in
  the session, not the channel.

  Background:
    Given default Grover setup
    And the isaac EDN file "config/models/grover2.edn" exists with:
      | path | value |
      | model | echo-alt |
      | provider | grover |
      | context-window | 16384 |
    And the isaac EDN file "config/models/grok.edn" exists with:
      | path | value |
      | model | grok-4-1-fast |
      | provider | grok |
      | context-window | 32768 |
    And the isaac file "config/providers/grok.edn" exists with:
      """
      {}
      """

  Scenario: /model switches the session's model
    Given the following sessions exist:
      | name       |
      | model-test |
    When the user sends "/model grok" on session "model-test"
    Then the reply contains "switched model to grok (grok/grok-4-1-fast)"
    And the following sessions match:
      | id         | model |
      | model-test | grok  |

  Scenario: /model persists across turns
    Given the following sessions exist:
      | name       |
      | model-test |
    And the following model responses are queued:
      | type | content | model    |
      | text | Hello   | echo-alt |
      | text | World   | echo-alt |
    When the user sends "/model grover2" on session "model-test"
    And the user sends "hi" on session "model-test"
    And the user sends "bye" on session "model-test"
    Then session "model-test" has transcript matching:
      | type    | message.role | message.model |
      | message | assistant    | echo-alt      |
      | message | assistant    | echo-alt      |

  Scenario: /model with no argument shows the current model
    Given the following sessions exist:
      | name       |
      | model-test |
    When the user sends "/model" on session "model-test"
    Then the reply contains "grover (grover/echo) is the current model"

  Scenario: /model with unknown alias shows an error
    Given the following sessions exist:
      | name       |
      | model-test |
    When the user sends "/model nonexistent" on session "model-test"
    Then the reply contains "unknown model: nonexistent"
