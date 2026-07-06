Feature: Suspend in-flight turns on shutdown
  A clean shutdown must not kill active turns dead. Suspend stops uncompleted
  turns at their next step boundary so they can be resumed: reuse of the
  cooperative cancellation path (current tool/stream finishes or aborts, its
  result persists, the loop stops instead of taking the next step), plus a
  bounded wait. The turn's durable marker (isaac-7li9) is STAMPED — :suspended
  true with :boundary :clean (stopped at a boundary) or :unclean (cap expired
  mid-step) — never deleted; isaac-vdfc resumes clean boundaries directly and
  repairs unclean ones. A user cancel deletes the marker: cancel means "stop,
  don't come back"; only suspend preserves. SIGKILL never runs any of this —
  that is the hard-crash path (isaac-vdfc). (isaac-2xj5)

  Background:
    Given default Grover setup

  @wip
  Scenario: suspend interrupts a waiting turn at the boundary and stamps its marker
    Given the following sessions exist:
      | name     |
      | longwave |
    And the following model responses are queued:
      | type | content | model | wait |
      | text | ok      | echo  | true |
    When the user sends "check the beacon" on session "longwave"
    And in-flight turns are suspended
    Then the turn result is "suspended"
    And a turn marker exists for session "longwave" with:
      | key       | value  |
      | source    | :comm  |
      | suspended | true   |
      | boundary  | :clean |
    And session "longwave" has transcript matching:
      | type    | message.role | message.content  | #comment                                    |
      | message | user         | check the beacon | last entry — stopped before any model reply |

  @wip
  Scenario: a user-cancelled turn deletes its marker — cancel is not suspend
    A user cancel means "stop, don't come back" — resuming a cancelled turn
    after a restart would be wrong. Only suspend preserves the marker.
    Given the following sessions exist:
      | name    |
      | skybeam |
    And the following model responses are queued:
      | type | content | model | wait |
      | text | ok      | echo  | true |
    When the user sends "hail the relay" on session "skybeam"
    And the turn is cancelled on session "skybeam"
    Then the turn result is "cancelled"
    And no turn marker exists for session "skybeam"

  @wip
  Scenario: a turn that cannot reach a step boundary before the cap is stamped unclean
    Tools run to completion by the cancellation contract, so a long exec holds
    the turn mid-step past the cap. Suspend stamps :unclean and returns — it
    does not wait for the stray tool; in a real shutdown the process exit takes
    the tool with it. :unclean tells isaac-vdfc the boundary is not trustworthy.
    Given the crew "main" allows tools: "exec"
    And the built-in tools are registered
    And the following sessions exist:
      | name    |
      | logbook |
    And the following model responses are queued:
      | tool_call | arguments               |
      | exec      | {"command": "sleep 30"} |
    When the user sends "run the diagnostic" on session "logbook"
    And in-flight turns are suspended with timeout 100ms
    Then a turn marker exists for session "logbook" with:
      | key       | value    | #comment                                |
      | source    | :comm    |                                         |
      | suspended | true     |                                         |
      | boundary  | :unclean | cap expired mid-exec — vdfc must repair |
