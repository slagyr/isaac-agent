Feature: Turn observers — lookout
  Observers witness a turn's lifecycle, fired from guaranteed
  finalization: they see how every turn ends, including the ways it
  dies. Refs are submitted per-turn (name or name:params) and resolve
  against the named registry; unknown names refuse before dispatch.
  :lookout is the built-in observer — it calls out turn-started and
  turn-ended with the outcome on stdout, narration for humans and the
  test double for the interface.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name       |
      | crows-nest |

  Scenario: a submitted lookout narrates the turn
    Given the following model responses are queued:
      | type | content       | model |
      | text | Land ho ahead | echo  |
    When isaac is run with "prompt -m 'Scan the horizon' --session crows-nest --observer lookout"
    Then the stdout matches:
      | pattern                                            |
      | (?s)turn started.*Land ho ahead.*turn ended \(ok\) |
    And the exit code is 0

  Scenario: the lookout reports a failed turn's outcome
    Given the following model responses are queued:
      | type       | status | message       | model |
      | http-error | 403    | fog rolled in | echo  |
    When isaac is run with "prompt -m 'Scan the horizon' --session crows-nest --observer lookout"
    Then the exit code is 1
    And the stdout contains "turn started"
    And the stdout matches:
      | pattern                   |
      | turn ended \(error[^)]*\) |

  Scenario: unknown observer names refuse loudly, before dispatch
    Given the following model responses are queued:
      | type | content    | model |
      | text | Never seen | echo  |
    When isaac is run with "prompt -m 'Scan the horizon' --session crows-nest --observer foghorn:xyz"
    Then the stderr contains "foghorn"
    And the stderr contains "unknown observer"
    And the exit code is 1
    Given the following model responses are queued:
      | type | content    | model |
      | text | Still here | echo  |
    When isaac is run with "prompt -m 'Scan the horizon' --session crows-nest --observer lookout"
    Then the stdout contains "Still here"
