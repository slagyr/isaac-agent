Feature: Crew and session concurrency
  Sessions track a transient `in-flight?` state, set when a turn
  starts and cleared when it ends. The session store enforces the
  session-level invariant — at most one turn runs on a session at
  a time — by refusing same-session dispatches that collide with
  an in-flight turn. Callers decide how to handle the refusal
  (CLI re-presents to the user; Hail / cron retry on their own
  cadence). Callers also consult `can-dispatch?` to route work
  away from at-capacity crews.

  Background:
    Given default Grover setup

  Scenario: a real turn marks its session in-flight, then clears it
    Given the following sessions exist:
      | name |
      | s1   |
    And the following model responses are queued:
      | type | content | model | wait |
      | text | ok      | echo  | true |
    When the user sends "hi" on session "s1"
    Then session "s1" in-flight status is true
    When the turn ends on session "s1"
    Then session "s1" in-flight status is false

  Scenario: a second dispatch on the same in-flight session is refused
    Given the following sessions exist:
      | name |
      | s1   |
    And the following model responses are queued:
      | type | content | model | wait |
      | text | first   | echo  | true |
    When the user sends "hi" on session "s1"
    And the user sends "go again" on session "s1"
    Then dispatch is refused with reason "session-in-flight"
    And the log has entries matching:
      | level | event             | session |
      | warn  | :dispatch/refused | s1      |
    When the turn ends on session "s1"

  Scenario: in-flight clears when a turn errors
    Given the following sessions exist:
      | name |
      | s1   |
    And the following model responses are queued:
      | type  | content | model |
      | error | boom    | echo  |
    When the user sends "hi" on session "s1"
    Then session "s1" in-flight status is false
