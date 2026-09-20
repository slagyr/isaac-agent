Feature: Resumed turns run on the turn queue, not the boot thread
  Resume is recovery work, not boot work (isaac-yxch). The startup scan reads
  every interrupted marker, hands the turn to the normal turn queue — the same
  waiting room a held CLI or comm turn parks in — and returns. Nothing about a
  resumed turn's length can keep component start-all! from finishing or the
  HTTP listener from binding: one pathological tool call used to hold boot
  hostage for ten minutes with the port closed and the log silent. Counts are
  logged at scan time (:resume/scan-complete); each resumed turn logs its own
  outcome (:turn.queue/woke) whenever it finishes. Markers that never deserved
  a turn — stale comm, cancelled — are still dropped at scan time and never
  reach the queue.

  Background:
    Given default Grover setup

  Scenario: the resume scan enqueues the interrupted turn and returns without running it
    Boot must not wait on a turn. The scan parks the work and finishes; the
    transcript is untouched until the queue wakes.
    Given the following sessions exist:
      | name    |
      | logbook |
    And session "logbook" has transcript:
      | type    | message.role | message.content |
      | message | user         | Begin the entry |
    And the isaac EDN file "sessions/turns/logbook.edn" exists with:
      | path       | value                |
      | source     | :comm                |
      | started-at | 2026-04-21T09:59:30Z |
    And the following model responses are queued:
      | type | content    | model |
      | text | Continuing | echo  |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then the log has entries matching:
      | level | event                 | session |
      | :info | :turn.queue/held      | logbook |
      | :info | :resume/scan-complete |         |
    And session "logbook" has transcript matching:
      | type    | message.content | #comment                             |
      | message | Begin the entry | no note, no reply — nothing ran here |
    And no turn marker exists for session "logbook"
    When isaac is run with "turns list"
    Then the stdout matches:
      | session | state |
      | logbook | held  |

  Scenario: the queued resume turn completes after boot and logs its outcome
    The turn may take minutes — a slow provider, a long tool, a big cycle
    budget. It runs on the queue's own thread and reports when it is done.
    Given the following sessions exist:
      | name    |
      | logbook |
    And session "logbook" has transcript:
      | type    | message.role | message.content |
      | message | user         | Begin the entry |
    And the isaac EDN file "sessions/turns/logbook.edn" exists with:
      | path       | value                |
      | source     | :comm                |
      | started-at | 2026-04-21T09:59:30Z |
    And the following model responses are queued:
      | type | content    | model |
      | text | Continuing | echo  |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    And the turn queue ticks at "2026-04-21T10:05:00Z"
    Then session "logbook" has transcript matching:
      | type    | message.content    | #comment               |
      | message | Begin the entry    |                        |
      | message | #".*interrupted.*" | resume note            |
      | message | Continuing         | resumed turn completed |
    And the log has entries matching:
      | level | event            | session |
      | :info | :turn.queue/woke | logbook |
    When isaac is run with "turns list"
    Then the stdout does not contain "logbook"

  Scenario: resume with no markers enqueues nothing
    Given the following sessions exist:
      | name    |
      | logbook |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then the log has entries matching:
      | level | event                 | markers | requeued | dropped |
      | :info | :resume/scan-complete | 0       | 0        | 0       |
    When isaac is run with "turns list"
    Then the stdout does not contain "logbook"

  Scenario: a marker outside the resume window is dropped, not enqueued
    Staleness is still decided at scan time, before anything is parked —
    nobody wants a surprise reply waiting in the queue for a conversation
    they abandoned.
    Given the following sessions exist:
      | name      |
      | firewatch |
    And session "firewatch" has transcript:
      | type    | message.role | message.content |
      | message | user         | Anyone there?   |
    And the isaac EDN file "sessions/turns/firewatch.edn" exists with:
      | path           | value                |
      | source         | :comm                |
      | interrupted-at | 2026-04-21T09:30:00Z |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then the log has entries matching:
      | level | event              | session   |
      | :info | :resume/comm-stale | firewatch |
    And no turn marker exists for session "firewatch"
    When isaac is run with "turns list"
    Then the stdout does not contain "firewatch"
