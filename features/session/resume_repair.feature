Feature: Resume repair and comm staleness
  Startup resume (isaac-vdfc) repairs what a hard crash left in the
  transcript before any turn drives against it, and applies the comm
  staleness window before resuming interactive turns. A torn trailing JSONL
  line (crash mid-append — the isaac-63f3 artifact) is truncated to the last
  complete line; every repair is durable and logged, never silent. A comm
  marker inside :turn-resume-window-ms (default 600000, measured from
  :interrupted-at, fallback :started-at) resumes with an interruption note;
  outside the window it is dropped and the transcript untouched — nobody
  wants a surprise reply to a conversation they abandoned. Hail and cron
  markers never go stale: they are work orders.

  Background:
    Given default Grover setup

  @wip
  Scenario: a torn trailing transcript line is truncated at resume
    A hard crash mid-append leaves a partial JSONL line at EOF — unreadable
    by any future transcript read. Resume repair truncates to the last
    complete line and logs what was dropped.
    Given the following sessions exist:
      | name    |
      | logbook |
    And session "logbook" has transcript:
      | type    | message.role | message.content |
      | message | user         | Begin the entry |
      | message | assistant    | Entry started   |
    And session "logbook" has a torn trailing transcript line "{\"type\":\"mess"
    And the isaac EDN file "sessions/turns/logbook.edn" exists with:
      | path       | value                |
      | source     | :comm                |
      | started-at | 2026-04-21T09:59:30Z |
    And the following model responses are queued:
      | type | content    | model |
      | text | Continuing | echo  |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then session "logbook" has transcript matching:
      | type    | message.content | #comment                    |
      | message | Begin the entry |                             |
      | message | Entry started   | torn tail gone — clean read |
      | message | #"interrupted"  | resume note (comm, fresh)   |
      | message | Continuing      | resumed turn completed      |
    And the log has entries matching:
      | level | event                     | session | repair     |
      | :warn | :resume/transcript-repair | logbook | :torn-line |

  @wip
  Scenario: a stale comm marker is dropped, not resumed
    Nobody wants a surprise reply to a conversation they abandoned — outside
    the resume window the marker is discarded and the transcript untouched.
    Given the following sessions exist:
      | name      |
      | firewatch |
    And session "firewatch" has transcript:
      | type    | message.role | message.content |
      | message | user         | Anyone there?   |
    And the isaac EDN file "sessions/turns/firewatch.edn" exists with:
      | path           | value                |
      | source         | :comm                |
      | suspended      | true                 |
      | boundary       | :clean               |
      | interrupted-at | 2026-04-21T09:30:00Z |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then no turn marker exists for session "firewatch"
    And session "firewatch" has transcript matching:
      | type    | message.content | #comment                                |
      | message | Anyone there?   | only entry — nothing appended, no reply |
    And the log has entries matching:
      | level | event              | session   |
      | :info | :resume/comm-stale | firewatch |
