Feature: A resumed turn closes the record it replaces (isaac-ziqg)
  After a restart, the boot resume scan re-queues an interrupted turn as a new
  turn record. The record it replaces is closed — finished, outcome
  :interrupted, :resumed-by naming the new record — and the new record carries
  :resumes naming the old one, so `isaac turns` never shows a ghost running turn.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name   |
      | harbor |
    And session "harbor" has transcript:
      | type    | message.role | message.content |
      | message | user         | Daily check     |
    And the isaac EDN file "turns/t-orig.edn" exists with:
      | path       | value                          |
      | id         | t-orig                         |
      | session    | harbor                         |
      | input      | Daily check                    |
      | origin     | {:kind :cron :name "heartbeat"} |
      | state      | :running                       |
      | created-at | 2026-04-21T09:59:00Z           |
    And the isaac EDN file "sessions/turns/harbor.edn" exists with:
      | path       | value                |
      | source     | :cron                |
      | started-at | 2026-04-21T09:59:30Z |
    And the following model responses are queued:
      | type | content   | model |
      | text | All clear | echo  |

  @wip
  Scenario: the interrupted record is closed and points at the turn that resumed it
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    And the turn queue ticks at "2026-04-21T10:00:01Z"
    And isaac is run with "turns show t-orig"
    Then the stdout matches:
      | pattern                  |
      | (?m)^state: finished$    |
      | (?m)^outcome: interrupted$ |
      | (?m)^resumed-by: \S+$    |

  @wip
  Scenario: the resuming record names the record it resumed
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    And the turn queue ticks at "2026-04-21T10:00:01Z"
    And isaac is run with "turns list"
    Then the stdout matches:
      | pattern               |
      | (?m)resumes:? t-orig  |
