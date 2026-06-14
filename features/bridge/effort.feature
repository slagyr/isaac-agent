Feature: /effort Command
  The /effort bridge command sets and shows the session-level effort knob.
  Effort is an integer 0-10. The change is stored in the session, not the channel.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name         |
      | effort-test  |

  Scenario: /effort N sets session-level effort
    When the user sends "/effort 5" on session "effort-test"
    Then the reply contains "effort set to 5"
    And the following sessions match:
      | id          | effort |
      | effort-test | 5      |

  Scenario: /effort shows the current effective effort
    When the user sends "/effort" on session "effort-test"
    Then the reply contains "current effort: 7"

  Scenario: /effort clear removes the session-level override
    Given the session "effort-test" has effort 5
    When the user sends "/effort clear" on session "effort-test"
    Then the reply contains "effort cleared"
    And the following sessions match:
      | id          | effort |
      | effort-test |        |

  Scenario: /effort with out-of-range value is rejected
    When the user sends "/effort 11" on session "effort-test"
    Then the reply contains "effort must be between 0 and 10"
    And the following sessions match:
      | id          | effort |
      | effort-test |        |

  Scenario: /effort with non-numeric value is rejected
    When the user sends "/effort high" on session "effort-test"
    Then the reply contains "effort must be between 0 and 10"
    And the following sessions match:
      | id          | effort |
      | effort-test |        |
