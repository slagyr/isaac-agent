Feature: Sessions Command
  `isaac sessions` lists stored conversation sessions.

  Background:
    Given default Grover setup
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |

  Scenario: sessions is registered and has help
    When isaac is run with "help sessions"
    Then the stdout contains "Usage: isaac sessions"
    And the exit code is 0

  Scenario: sessions --help lists its subcommands
    When isaac is run with "sessions --help"
    Then the stdout matches:
      | pattern                                          |
      | Usage: isaac sessions                            |
      | Subcommands:                                     |
      | show\s+Show one session                          |
      | set\s+Set a mutable field.*<id>\.<path> <value>  |
      | unset\s+Clear a mutable field.*<id>\.<path>      |
      | delete\s+Delete a session                        |
    And the exit code is 0

  Scenario: sessions defaults to one flat table sorted alphabetically with a CREW column
    Given the following sessions exist:
      | name         | crew  | total-tokens | last-input-tokens | updated-at          |
      | charlie-chat | main  | 778          | 778               | 2026-04-12T10:00:00 |
      | bravo-chat   | ketch | 12000        | 12000             | 2026-04-11T10:00:00 |
      | alpha-chat   | main  | 5000         | 5000              | 2026-04-12T15:00:00 |
    When isaac is run with "sessions"
    Then the stdout matches:
      | pattern                                           |
      | SESSION       AGE    USED  WINDOW  PCT  CREW      |
      | alpha-chat    \S+   5,000  32,768  \d+%  main     |
      | bravo-chat    \S+  12,000  32,768  \d+%  ketch    |
      | charlie-chat  \S+     778  32,768 \s+\d+%  main   |
    And the stdout does not contain "crew: main"
    And the stdout does not contain "crew: ketch"
    And the exit code is 0

  Scenario: sessions --crew filters by current crew member
    Given the following sessions exist:
      | name         | crew  | total-tokens | updated-at           |
      | design-chat  | main  | 5000        | 2026-04-12T15:00:00 |
      | pirate-chat  | ketch | 12000       | 2026-04-11T10:00:00 |
    When isaac is run with "sessions --crew ketch"
    Then the stdout matches:
      | pattern      |
      | pirate-chat  |
    And the stdout does not contain "design-chat"
    And the exit code is 0

  Scenario: sessions with no sessions prints a message
    When isaac is run with "sessions"
    Then the stdout contains "no sessions"
    And the exit code is 0

  Scenario: sessions --crew with unknown crew member prints an error
    When isaac is run with "sessions --crew nonexistent"
    Then the stderr contains "unknown crew"
    And the stderr contains "nonexistent"
    And the exit code is 1

  Scenario: sessions output has aligned columns with a header row
    Given the following sessions exist:
      | name         | total-tokens | last-input-tokens | updated-at           |
      | design-chat  | 5000         | 5000              | 2026-04-12T15:00:00  |
      | review-chat  | 778          | 778               | 2026-04-12T10:00:00  |
      | pirate-chat  | 12000        | 12000             | 2026-04-11T10:00:00  |
    When isaac is run with "sessions --crew main"
    Then the stdout matches:
      | pattern                                              |
      | SESSION      AGE    USED  WINDOW  PCT                |
      | design-chat  \S+   5,000  32,768  \d+%               |
      | review-chat  \S+     778  32,768   \d+%              |
      | pirate-chat  \S+  12,000  32,768  \d+%               |

  Scenario: sessions show prints metadata for one session
    Given the following sessions exist:
      | name        | total-tokens | last-input-tokens | updated-at          |
      | design-chat | 5000         | 5000              | 2026-04-12T15:00:00 |
    And session "design-chat" has transcript:
      | type    | message.role | message.content     |
      | message | user         | Hello there         |
      | message | assistant    | Hi, how can I help? |
    When isaac is run with "sessions show design-chat"
    Then the exit code is 0
    And the stdout matches:
      | pattern                   |
      | Session Status            |
      | Crew .* main              |
      | Model .* echo \(grover\)  |
      | Session .* design-chat    |
      | Turns .* 2                |
      | Context .* 5,000 / 32,768 |
    And the stdout does not contain "Hello there"

  Scenario: sessions delete removes a session and its transcript
    Given the following sessions exist:
      | name        |
      | design-chat |
    And session "design-chat" has transcript:
      | type    | message.role | message.content |
      | message | user         | hi              |
    When isaac is run with "sessions delete design-chat"
    Then the exit code is 0
    And session "design-chat" does not exist
    And the isaac file "sessions/design-chat.jsonl" does not exist

  Scenario: sessions output is colorized when --color always is set
    Given the following sessions exist:
      | name        | total-tokens | updated-at          |
      | design-chat | 28000        | 2026-04-12T15:00:00 |
    When isaac is run with "sessions --color always"
    Then the stdout matches:
      | pattern               |
      | \x1b\[1m.*SESSION     |
      | design-chat.*\x1b\[   |

  Scenario: sessions --no-color suppresses ANSI escapes
    Given the following sessions exist:
      | name        | total-tokens | updated-at          |
      | design-chat | 5000         | 2026-04-12T15:00:00 |
    When isaac is run with "sessions --no-color"
    Then the stdout matches:
      | pattern    |
      | ^[^\x1b]*$ |
    And the stdout contains "design-chat"

  Scenario: USED shows last-turn context size, not cumulative billing
    Given the following sessions exist:
      | name   | total-tokens | last-input-tokens | updated-at          |
      | chatty | 1000000      | 5000              | 2026-04-12T15:00:00 |
    When isaac is run with "sessions"
    Then the stdout matches:
      | pattern                              |
      | chatty\s+\S+\s+5,000\s+32,768\s+\d+% |
    And the stdout does not contain "1,000,000"

  @wip
  Scenario: sessions output marks in-flight sessions with ✈️
    Given the following sessions exist:
      | name        | total-tokens | updated-at          |
      | design-chat | 5000         | 2026-04-12T15:00:00 |
      | pirate-chat | 12000        | 2026-04-11T10:00:00 |
    And the following model responses are queued:
      | type | content | model | wait |
      | text | working | echo  | true |
    When the user sends "more" on session "design-chat"
    And isaac is run with "sessions"
    Then the stdout matches:
      | pattern          |
      | design-chat ✈️   |
    And the stdout does not contain "pirate-chat ✈️"
    When the turn ends on session "design-chat"

  @wip
  Scenario: sessions --in-flight filters to in-flight sessions only
    Given the following sessions exist:
      | name        | total-tokens | updated-at          |
      | design-chat | 5000         | 2026-04-12T15:00:00 |
      | pirate-chat | 12000        | 2026-04-11T10:00:00 |
    And the following model responses are queued:
      | type | content | model | wait |
      | text | working | echo  | true |
    When the user sends "more" on session "design-chat"
    And isaac is run with "sessions --in-flight"
    Then the stdout contains "design-chat"
    And the stdout does not contain "pirate-chat"
    When the turn ends on session "design-chat"

  @wip
  Scenario: sessions --not-in-flight filters to idle sessions only
    Given the following sessions exist:
      | name        | total-tokens | updated-at          |
      | design-chat | 5000         | 2026-04-12T15:00:00 |
      | pirate-chat | 12000        | 2026-04-11T10:00:00 |
    And the following model responses are queued:
      | type | content | model | wait |
      | text | working | echo  | true |
    When the user sends "more" on session "design-chat"
    And isaac is run with "sessions --not-in-flight"
    Then the stdout contains "pirate-chat"
    And the stdout does not contain "design-chat"
    When the turn ends on session "design-chat"

  @wip
  Scenario: sessions --in-flight with --not-in-flight is an error
    When isaac is run with "sessions --in-flight --not-in-flight"
    Then the exit code is 1
    And the stderr contains "mutually exclusive"
