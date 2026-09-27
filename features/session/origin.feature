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

  @wip
  Scenario: CLI-spawned session carries origin :cli
    Given the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When isaac is run with "prompt -m 'Hi'"
    Then the exit code is 0
    And the session count is 1
    And session "prompt-default" does not exist
    And the following sessions match:
      | origin.kind |
      | cli         |
