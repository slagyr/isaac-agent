Feature: Cancel-path observability
  When something asks the bridge to cancel a session, Isaac emits
  info-level log events so an operator can confirm the cancel was
  applied and how many on-cancel hooks fired. The ACP-receive side
  is covered in features/acp/cancellation.feature.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name        |
      | cancel-test |

  Scenario: cancel applied to a known session logs at info with a hook count
    Given the LLM response is delayed by 30 seconds
    When the user sends "think hard" on session "cancel-test"
    And the turn is cancelled on session "cancel-test"
    Then the log has entries matching:
      | level | event                  | session     | hooks  |
      | :info | :bridge/cancel-applied | cancel-test | #"\d+" |

  Scenario: cancel with no in-flight turn emits :bridge/cancel-noop
    When the turn is cancelled on session "cancel-test"
    Then the log has entries matching:
      | level | event               | session     |
      | :info | :bridge/cancel-noop | cancel-test |
