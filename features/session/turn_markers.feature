Feature: Durable turn markers
  A turn's in-flight state must survive the process. The bridge records a
  marker via the SessionStore when a turn is dispatched and clears it when
  the turn completes; the store keeps it at sessions/turns/<session-id>.edn.
  Today the only in-flight signal is an in-memory atom that dies with the
  JVM, so a restart forgets which sessions had active turns — every deploy
  kills active turns dead, never to be resumed. The marker holds the resume
  ROUTING (source, delivery id / comm coordinates, started-at), not
  per-request state: the transcript is already the per-step checkpoint.
  A marker is an orphan only when no live in-flight entry exists for its
  session — never by age, since markers live for the whole turn. (isaac-7li9)

  Background:
    Given default Grover setup

  Scenario: a turn records a durable marker at dispatch and clears it on completion
    Given the following sessions exist:
      | name     |
      | longwave |
    And the following model responses are queued:
      | type | content | model | wait |
      | text | ok      | echo  | true |
    When the user sends "check the beacon" on session "longwave"
    Then a turn marker exists for session "longwave" with:
      | key        | value |
      | source     | :comm |
      | started-at | #*    |
    When the turn ends on session "longwave"
    Then no turn marker exists for session "longwave"

  Scenario: a failed turn still clears its marker
    Given the following sessions exist:
      | name    |
      | skybeam |
    And the following model responses are queued:
      | type  | content | model |
      | error | boom    | echo  |
    When the user sends "hail the relay" on session "skybeam"
    Then no turn marker exists for session "skybeam"

  Scenario: only markers without a live turn are orphans
    Given the following sessions exist:
      | name      |
      | firewatch |
      | logbook   |
    And the following model responses are queued:
      | type | content | model | wait |
      | text | ok      | echo  | true |
    And a turn marker exists for session "logbook" referencing delivery "hail-9"
    When the user sends "keep watch" on session "firewatch"
    Then a turn marker exists for session "firewatch" with:
      | key    | value |
      | source | :comm |
    And the orphaned turn markers are:
      | session |
      | logbook |
    When the turn ends on session "firewatch"
