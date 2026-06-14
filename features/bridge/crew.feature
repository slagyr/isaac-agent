Feature: /crew Command
  The /crew bridge command switches the session's active crew
  member. Subsequent turns use the new crew member's soul, model,
  and provider. The change is stored in the session.

  Background:
    Given default Grover setup
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |

  Scenario: /crew switches the session's active crew member
    Given the following sessions exist:
      | name      |
      | crew-test |
    When the user sends "/crew ketch" on session "crew-test"
    Then the reply contains "switched crew to ketch"
    And the following sessions match:
      | id        | crew  |
      | crew-test | ketch |

  Scenario: /crew persists across turns
    Given the following sessions exist:
      | name      |
      | crew-test |
    And the following model responses are queued:
      | type | content | model |
      | text | Ahoy    | echo  |
      | text | Arr     | echo  |
    When the user sends "/crew ketch" on session "crew-test"
    And the user sends "hi" on session "crew-test"
    And the user sends "bye" on session "crew-test"
    Then session "crew-test" has transcript matching:
      | type    | message.role | message.crew |
      | message | assistant    | ketch        |
      | message | assistant    | ketch        |

  Scenario: /crew with no argument shows the current crew member
    Given the following sessions exist:
      | name      |
      | crew-test |
    When the user sends "/crew" on session "crew-test"
    Then the reply contains "main is the current crew member"

  Scenario: /crew with unknown name shows an error
    Given the following sessions exist:
      | name      |
      | crew-test |
    When the user sends "/crew nonexistent" on session "crew-test"
    Then the reply contains "unknown crew: nonexistent"

  Scenario: /crew clears the session's pinned :model
    # Tracked by isaac-ujp1. A pinned model survives turns for outage
    # continuity within a crew, but switching crews resets it so the
    # new crew uses its own preferred model.
    Given the following sessions exist:
      | name      | crew | model  |
      | crew-test | main | parrot |
    And the following model responses are queued:
      | type | content | model  |
      | text | Ahoy    | grover |
    When the user sends "/crew ketch" on session "crew-test"
    And the user sends "hi" on session "crew-test"
    Then session "crew-test" has transcript matching:
      | type    | message.role | message.model |
      | message | assistant    | grover        |

  Scenario: /crew does not clear locked session fields like :cwd
    # Tracked by isaac-ujp1. The clear-on-switch is selective — only
    # behavioral overrides (e.g. :model) are reset. State-defining
    # locked fields (:cwd, :history-retention) represent the session's
    # identity and survive crew switches.
    Given the following sessions exist:
      | name     | crew | cwd       |
      | cwd-test | main | /tmp/work |
    When the user sends "/crew ketch" on session "cwd-test"
    Then session "cwd-test" matches:
      | key  | value     |
      | crew | ketch     |
      | cwd  | /tmp/work |
