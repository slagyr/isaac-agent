@wip
Feature: Resource pools
  A resource pool admits a turn when it can lease the turn what it needs.
  Modules contribute pool TYPES through :isaac.agent/resource-pool-types;
  config creates named INSTANCES under :resource-pools (entity dir
  config/resource-pools/<name>.edn), each with a :type. A turn names the
  instances it needs (CLI --pool, repeatable). Unknown names refuse before
  dispatch. Busy means wait: acquisition runs in the order the turn lists
  its pools; when one is busy the pools already taken are given back and
  the turn waits in the queue. :tide is the built-in type — available only
  inside its clock window. (isaac-ey7a)

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name   |
      | harbor |

  Scenario: pool instances validate against their types
    Given the isaac EDN file "config/resource-pools/night-watch.edn" exists with:
      | path   | value         |
      | type   | :tide         |
      | window | "22:00-06:00" |
    When isaac is run with "config validate"
    Then the exit code is 0
    Given the isaac EDN file "config/resource-pools/drydock.edn" exists with:
      | path | value    |
      | type | :drydock |
    And the isaac EDN file "config/resource-pools/dogwatch.edn" exists with:
      | path | value |
      | type | :tide |
    When isaac is run with "config validate"
    Then the stderr contains "drydock"
    And the stderr contains "unknown resource pool type"
    And the stderr contains "dogwatch"
    And the stderr contains "window"
    And the exit code is 1

  Scenario: a turn naming an unknown pool refuses loudly, before dispatch
    Given the following model responses are queued:
      | type | content    | model |
      | text | Never seen | echo  |
    When isaac is run with "prompt -m 'Leave harbor' --session harbor --pool drydock"
    Then the stderr contains "drydock"
    And the stderr contains "unknown resource pool"
    And the exit code is 1
    When isaac is run with "prompt -m 'Leave harbor' --session harbor"
    Then the stdout contains "Never seen"
    And the exit code is 0

  Scenario: a busy second pool gives back the first and the turn waits
    Given a scripted resource pool "dock" admits 1 turn at a time
    And a scripted resource pool "crane" admits 1 turn at a time
    And resource pool "crane" is closed
    And the following sessions exist:
      | name  |
      | jetty |
    And the following model responses are queued:
      | type | content      | model |
      | text | Moored       | echo  |
      | text | Cargo aboard | echo  |
    When isaac is run with "prompt -m 'Load cargo' --session harbor --pool dock --pool crane"
    Then the stdout contains "held"
    And the stdout contains "crane"
    And the exit code is 0
    When isaac is run with "prompt -m 'Tie up' --session jetty --pool dock"
    Then the stdout contains "Moored"
    And the exit code is 0
    When resource pool "crane" is opened
    Then session "harbor" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Load cargo      |
      | message | assistant    | Cargo aboard    |

  Scenario: a tide pool holds a turn outside its window and runs it inside
    Given the isaac EDN file "config/resource-pools/night-watch.edn" exists with:
      | path   | value         |
      | type   | :tide         |
      | window | "22:00-06:00" |
    And the current time is "2026-03-01T14:00:00"
    And the following model responses are queued:
      | type | content      | model |
      | text | Setting sail | echo  |
    When isaac is run with "prompt -m 'Leave harbor' --session harbor --pool night-watch"
    Then the stdout contains "held"
    And the stdout contains "night-watch"
    And the exit code is 0
    Given the current time is "2026-03-01T23:30:00"
    When isaac is run with "prompt -m 'Leave harbor' --session harbor --pool night-watch"
    Then the stdout contains "Setting sail"
    And the exit code is 0
