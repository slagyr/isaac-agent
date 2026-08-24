Feature: Turnstiles — tide
  A turnstile admits turns one at a time (or not at all, yet): the
  submitter passes turnstile refs (name, or name:params) with the turn
  request; the null turnstile runs now. Refs resolve against the named
  registry — unknown names refuse before dispatch. :tide is the
  built-in turnstile: it admits turns only inside a clock window and
  holds outside it. Until the turn-request queue lands, a hold at the
  CLI surfaces as a refusal that names the turnstile and its reason.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name   |
      | harbor |

  Scenario: tide holds a turn outside its window
    Given the current time is "2026-03-01T14:00:00"
    And the following model responses are queued:
      | type | content      | model |
      | text | Setting sail | echo  |
    When isaac is run with "prompt -m 'Leave harbor' --session harbor --turnstile tide:22:00-06:00"
    Then the stderr contains "tide"
    And the stderr contains "22:00-06:00"
    And the stderr contains "held"
    And the exit code is 1
    Given the current time is "2026-03-01T23:30:00"
    When isaac is run with "prompt -m 'Leave harbor' --session harbor --turnstile tide:22:00-06:00"
    Then the stdout contains "Setting sail"
    And the exit code is 0

  Scenario: unknown turnstile names refuse loudly, before dispatch
    Given the following model responses are queued:
      | type | content    | model |
      | text | Never seen | echo  |
    When isaac is run with "prompt -m 'Leave harbor' --session harbor --turnstile drydock:7"
    Then the stderr contains "drydock"
    And the stderr contains "unknown turnstile"
    And the exit code is 1
    When isaac is run with "prompt -m 'Leave harbor' --session harbor"
    Then the stdout contains "Never seen"
    And the exit code is 0
