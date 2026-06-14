Feature: Session origin
  Every session records where it was spawned from in a structural
  :origin field. This enables history queries like "find all
  transcripts from cron X" and answering "what produced this session?"
  without parsing free text.

  Shape:
    :origin {:kind :cli}                          — isaac prompt
    :origin {:kind :cron :name "health-check"}    — scheduler-spawned
    :origin {:kind :discord :guild ... :channel ...}  — adapter (future)
    :origin {:kind :acp}                          — set by the ACP module

  Background:
    Given default Grover setup

  Scenario: cron-spawned session carries origin pointing at the cron
    Given config:
      | tz                       | America/Chicago         |
      | sessions.naming-strategy | sequential              |
      | cron.health-check.expr   | 0 9 * * *               |
      | cron.health-check.crew   | main                    |
      | cron.health-check.prompt | Run the health checkin. |
    And the following model responses are queued:
      | type | content         | model |
      | text | Health is good. | echo  |
    When the scheduler ticks at "2026-04-21T09:00:00-0500"
    Then the following sessions match:
      | id        | origin.kind | origin.name  |
      | session-1 | cron        | health-check |

  Scenario: CLI-spawned session carries origin :cli
    Given the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When isaac is run with "prompt -m 'Hi'"
    Then the following sessions match:
      | id              | origin.kind |
      | prompt-default  | cli         |

