Feature: A resumed turn keeps the observers of the turn it replaces
  A restart interrupts a turn submitted with observers (Foreman's among them).
  The restart marker is resume routing only — it never names observers — so
  the boot resume takes them from the interrupted queue record. Whoever was
  waiting on the first turn hears how the resumed turn ends, under the first
  turn's request id, or a Foreman instance waits forever on a verdict that
  already came back.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name      |
      | bean-demo |
    And session "bean-demo" has transcript:
      | type    | message.role | message.content |
      | message | user         | Work the bean   |
    And the isaac EDN file "turns/t-orig.edn" exists with:
      | path       | value                 |
      | id         | t-orig                |
      | session    | bean-demo             |
      | input      | Work the bean         |
      | origin     | {:kind :hail}         |
      | observers  | [:lookout]            |
      | state      | :running              |
      | created-at | 2026-04-21T09:59:00Z  |
    And the isaac EDN file "sessions/turns/bean-demo.edn" exists with:
      | path           | value                    |
      | source         | :hail                    |
      | started-at     | 2026-04-21T09:59:30Z     |
      | suspended      | true                     |
      | interrupted-at | 2026-04-21T09:59:50Z     |

  Scenario: the resumed turn's observers hear how it ends
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    And isaac is run with "turns show t-orig"
    Then the stdout matches:
      | pattern                     |
      | (?m)^resumed-by: [a-z0-9-]+$ |
    When isaac is run with "turns drop #held-id"
    Then the stdout contains "dropped"
    And the stdout contains "turn ended (error dropped)"
    And the exit code is 0
