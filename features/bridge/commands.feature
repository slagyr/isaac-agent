Feature: Bridge Commands
  The bridge intercepts input starting with / and handles it
  locally without sending it to the LLM. Unrecognized commands
  produce a helpful error.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name           | total-tokens | last-input-tokens | compaction-count |
      | bridge-status  | 5000        | 5000              | 2               |
    And session "bridge-status" has transcript:
      | type    | message.role | message.content |
      | message | user         | hello           |
      | message | assistant    | hi              |
      | message | user         | how are you     |
      | message | assistant    | fine            |
    And the built-in tools are registered

  Scenario: /status prints session information as plain text
    When the user sends "/status" on session "bridge-status"
    Then the reply matches:
      | pattern                                      |
      | Session Status                              |
      | Crew .* main                                 |
      | ─+                                           |
      | Model .* echo \(grover\)                     |
      | Session .* bridge-status                     |
      | File .* bridge-status\.jsonl                 |
      | Turns .* 4                                   |
      | Compactions .* 2                             |
      | Context .* 5,000 / 32,768 .*15%             |
      | Soul .*                                        |
      | Tools .* \d+                                 |
      | CWD                                          |
    And the reply does not contain "SOUL.md"

  Scenario: /status Context shows last-turn size, not cumulative billing
    Given the following sessions exist:
      | name        | total-tokens | last-input-tokens |
      | size-status | 1000000      | 5000              |
    When the user sends "/status" on session "size-status"
    Then the reply matches:
      | pattern                       |
      | Context .* 5,000 / 32,768.*\d+% |
    And the reply does not contain "1,000,000"

  Scenario: /status shows the session's cwd, not the process working directory
    Given the following sessions exist:
      | name       | crew | cwd                    |
      | cwd-status | main | /tmp/isaac-cwd-fixture |
    When the user sends "/status" on session "cwd-status"
    Then the reply matches:
      | pattern                       |
      | CWD .* /tmp/isaac-cwd-fixture |

  Scenario: /status is not sent to the LLM
    When the user sends "/status" on session "bridge-status"
    Then session "bridge-status" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | hello           |
      | message | assistant    | hi              |
      | message | user         | how are you     |
      | message | assistant    | fine            |

  Scenario: unrecognized command produces an error
    When the user sends "/bogus" on session "bridge-status"
    Then the reply contains "unknown command: /bogus"

  Scenario: normal input is not intercepted by the bridge
    Given the following model responses are queued:
      | type | content   | model |
      | text | I am fine | echo  |
    When the user sends "how are you?" on session "bridge-status"
    Then session "bridge-status" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | I am fine       |

  Scenario: bare /cwd shows the session's current working directory
    Given the following sessions exist:
      | name     | crew | cwd           |
      | cwd-test | main | /work/lettuce |
    When the user sends "/cwd" on session "cwd-test"
    Then the reply contains "/work/lettuce"

  Scenario: /cwd <path> sets the session's working directory
    Given the file "fresh-cwd/.keep" exists with:
      """
      """
    And the following sessions exist:
      | name     | crew | cwd     |
      | cwd-test | main | old-cwd |
    When the user sends "/cwd fresh-cwd" on session "cwd-test"
    Then the reply contains "fresh-cwd"
    And session "cwd-test" matches:
      | key | value             |
      | cwd | #".*fresh-cwd.*" |

  Scenario: /cwd rejects a non-existent path
    Given the following sessions exist:
      | name     | crew | cwd     |
      | cwd-test | main | old-cwd |
    When the user sends "/cwd no-such-dir" on session "cwd-test"
    Then the reply contains "no such directory"
    And session "cwd-test" matches:
      | key | value           |
      | cwd | #".*old-cwd.*" |

  Scenario: /cwd is not sent to the LLM
    Given the file "fresh-cwd/.keep" exists with:
      """
      """
    And the following sessions exist:
      | name     | crew | cwd     |
      | cwd-test | main | old-cwd |
    And session "cwd-test" has transcript:
      | type    | message.role | message.content |
      | message | user         | hello           |
      | message | assistant    | hi              |
    When the user sends "/cwd fresh-cwd" on session "cwd-test"
    Then session "cwd-test" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | hello           |
      | message | assistant    | hi              |
