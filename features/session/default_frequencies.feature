@wip
Feature: Default frequencies select the session
  Every turn merges :defaults :frequencies underneath the frequencies the
  consumer supplied. The consumer wins. prompt-default is not a session id
  the resolver invents. A merged map that names no session, crew, or tags
  fails the turn. Decision (2026-09-27, Micah), isaac-vp7h.

  Background:
    Given default Grover setup

  Scenario: a bare prompt with no existing session creates one for the default crew
    Given the isaac config path "defaults.frequencies.crew" is "cordelia"
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value            |
      | model | echo             |
      | soul  | You are Cordelia |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When isaac is run with "prompt -m 'Hi'"
    Then the exit code is 0
    And the stdout contains "Hello"
    And the session count is 1
    And session "prompt-default" does not exist
    And the following sessions match:
      | crew     | origin.kind |
      | cordelia | cli         |

  Scenario: an explicit session wins over the default crew
    Given the isaac config path "defaults.frequencies.crew" is "cordelia"
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path  | value              |
      | model | echo               |
      | soul  | You are a pirate.  |
    And the following sessions exist:
      | name    | crew  |
      | mooring | ketch |
    And the following model responses are queued:
      | type | content | model |
      | text | Ahoy    | echo  |
    When isaac is run with "prompt --session mooring -m 'Status?'"
    Then the exit code is 0
    And session "mooring" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Status?         |
      | message | assistant    | Ahoy            |
    And session "prompt-default" does not exist

  Scenario: a command crew wins over the default crew
    Given the isaac config path "defaults.frequencies.crew" is "cordelia"
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path  | value             |
      | model | echo              |
      | soul  | You are a pirate. |
    And the following sessions exist:
      | name    | crew  |
      | mooring | ketch |
    And the following model responses are queued:
      | type | content  | model |
      | text | On deck. | echo  |
    When isaac is run with "prompt --crew ketch -m 'Status?'"
    Then the exit code is 0
    And session "mooring" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Status?         |
      | message | assistant    | On deck.        |
    And session "prompt-default" does not exist

  Scenario: a turn with no session, crew, or tags fails
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                      | value   |
      | defaults.frequencies.crew | #delete |
    When isaac is run with "prompt -m 'Hi'"
    Then the exit code is 1
    And the stderr contains "no session"
    And the session count is 0
    And session "prompt-default" does not exist
