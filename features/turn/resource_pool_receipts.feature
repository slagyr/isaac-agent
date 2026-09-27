@wip
Feature: Resource pool receipts
  A pool that leases a turn what it needs hands back a RECEIPT: bindings
  plus a release id. Bindings shape the turn before it runs — for now the
  only permitted key is :session/cwd, which sets the directory for THIS
  turn (boot files, tools) without changing the session's own cwd. Each
  user message in the transcript records the cwd its turn ran in. Any
  other binding key is a pool bug: the turn fails loudly and its leases
  are given back. A running turn's marker records its leases, so a crash
  cannot strand them: on restart the leases are released and the
  re-driven turn asks for the same pools again. (isaac-i5lv)

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name   |
      | harbor |

  Scenario: a cwd binding sets the directory for that turn only
    Given the file "target/slip-a/AGENTS.md" exists with:
      """
      Mind the eastern shoals.
      """
    And the file "target/slip-b/AGENTS.md" exists with:
      """
      Mind the western reef.
      """
    And a scripted resource pool "slip" binds:
      | key         | value         |
      | session/cwd | target/slip-a |
    And the following model responses are queued:
      | type | content       | model |
      | text | Eastern berth | echo  |
      | text | Western berth | echo  |
    When the user sends "Where are we?" on session "harbor" with resource pools "slip"
    Then the system prompt contains "eastern shoals"
    Given a scripted resource pool "slip" binds:
      | key         | value         |
      | session/cwd | target/slip-b |
    When the user sends "And now?" on session "harbor" with resource pools "slip"
    Then the system prompt contains "western reef"
    And the system prompt does not contain "eastern shoals"
    And session "harbor" has transcript matching:
      | type    | message.role | message.content | cwd                 |
      | message | user         | Where are we?   | #".*target/slip-a$" |
      | message | user         | And now?        | #".*target/slip-b$" |

  Scenario: an unknown binding key fails the turn loudly and gives the lease back
    Given a scripted resource pool "slip" binds:
      | key           | value    |
      | session/model | grover-2 |
    And the following model responses are queued:
      | type | content       | model |
      | text | Eastern berth | echo  |
    When isaac is run with "prompt -m 'Where are we?' --session harbor --pool slip"
    Then the stderr contains "session/model"
    And the stderr contains "unknown binding"
    And the exit code is 1
    Given a scripted resource pool "slip" binds:
      | key         | value         |
      | session/cwd | target/slip-a |
    When isaac is run with "prompt -m 'Where are we?' --session harbor --pool slip"
    Then the stdout contains "Eastern berth"
    And the exit code is 0

  Scenario: a running turn's marker records its leases
    Given a scripted resource pool "dock" admits 1 turn at a time
    And the following model responses are queued:
      | type | content  | model | wait |
      | text | Tied off | echo  | true |
    When the user sends "Come alongside" on session "harbor" with resource pools "dock"
    Then a turn marker exists for session "harbor" with:
      | key                  | value |
      | leases[0].pool       | dock  |
      | leases[0].release-id | #*    |
    When the turn ends on session "harbor"
    Then no turn marker exists for session "harbor"

  Scenario: restart releases a crashed turn's lease and the re-driven turn re-acquires it
    The crashed turn holds the only dock lease. If restart did not release
    it, the re-driven turn would wait forever.
    Given a scripted resource pool "dock" admits 1 turn at a time
    And resource pool "dock" has lease "lease-7" out
    And session "harbor" has transcript:
      | type    | message.role | message.content |
      | message | user         | Come alongside  |
    And the isaac EDN file "sessions/turns/harbor.edn" exists with:
      | path       | value                                   |
      | source     | :comm                                   |
      | started-at | 2026-04-21T09:59:30Z                    |
      | leases     | [{:pool "dock" :release-id "lease-7"}] |
    And the following model responses are queued:
      | type | content  | model |
      | text | Tied off | echo  |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    And the turn queue ticks at "2026-04-21T10:00:01Z"
    Then session "harbor" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Come alongside  |
      | message | assistant    | Tied off        |
    When isaac is run with "turns list"
    Then the stdout does not contain "harbor"
