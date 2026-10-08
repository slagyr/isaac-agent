@wip
Feature: Turn attribution — who started a turn, and on whose behalf
  Every turn record carries :from, who started it, and optionally :for,
  the outside party it is done for. Each is a map with a :kind:

    {:kind :handle :comm … :id … :name … :email … :authenticated …}
        an outside sender, exactly as the comm knows them
    {:kind :crew :id …}   another crew member
    {:kind :cron :id …}   a scheduled job
    {:kind :cli}          a command on the host
    {:kind :http :id …}   an API caller, by principal

  Agent stores, shows and copies these references and does not interpret
  them. They sit beside :origin, which stays opaque. A turn started by an
  outside handle is for that same party unless the submitter says
  otherwise. `isaac turns show` prints them as from.<key> and for.<key>.

  Tracked by isaac-v403.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name   |
      | harbor |

  Scenario: a turn from an outside handle records who started it and whom it is for
    When a turn is submitted with:
      | key                | value                     |
      | id                 | tide-9                    |
      | input              | Leave harbor              |
      | session            | harbor                    |
      | from.kind          | handle                    |
      | from.comm          | logbook                   |
      | from.id            | cordelia-7                |
      | from.name          | Cordelia                  |
      | from.email         | cordelia@marigold.example |
      | from.authenticated | true                      |
    And isaac is run with "turns show tide-9"
    Then the stdout matches:
      | from.kind: handle                     |
      | from.comm: logbook                    |
      | from.id: cordelia-7                   |
      | from.name: Cordelia                   |
      | from.email: cordelia@marigold.example |
      | from.authenticated: true              |
      | for.kind: handle                      |
      | for.id: cordelia-7                    |
    And the exit code is 0

  Scenario: a turn started from the CLI is from the CLI and for nobody
    When isaac is run with "prompt --queue --session harbor -m 'Leave harbor'"
    Then the stdout matches:
      | queued: #"[a-z0-9]+":turn-id |
    When isaac is run with "turns show #turn-id"
    Then the stdout matches:
      | from.kind: cli |
    And the stdout does not contain "for."
