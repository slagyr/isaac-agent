@wip
Feature: Idle sealing — a quiet thread becomes recallable within minutes
  Recall only sees sealed, indexed scenes. Today a scene seals on size cap,
  on topic drift, or when the episode closes — and an episode closes only
  when the NEXT message arrives cold, on compaction, or by hand. A short
  conversation that goes quiet therefore stays invisible to every other
  thread indefinitely (isaac-q34y). The episodes worker ticks every 30s:
  an open episode whose last message is older than :episodes :seal
  :idle-minutes (default 3), with an unsealed tail and no turn in flight,
  seals its WHOLE tail (leave-open 0) and indexes it; the episode stays
  open and warm. A resumed conversation appends a continuation scene.
  Episodes cold past :episodes :ttl-minutes (default 60) close on the same
  tick. Closing is housekeeping; sealing is what makes memory visible.

  Background:
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes  {:gist-model :gist}
       :embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """

  Scenario: an idle thread seals its tail on the tick and stays open
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                    | model |
      | text | Charted, keep west         | echo  |
      | text | Buoys marked with the tool | echo  |
      | text | 1-4: Reef passage charted  | gist  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    Given the current time is "2026-03-01T10:01:00"
    When isaac is run with "prompt -m 'Mark the buoys' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T10:05:00"
    Then an episode exists for crew "cordelia" matching:
      | key    | value |
      | status | open  |
    And that episode has scenes matching:
      | gist                 | text             | seal-reason |
      | Reef passage charted | #"(?s)keep west" | idle        |
    And the index for crew "cordelia" has a row for gist "Reef passage charted"

  Scenario: a warm thread is left alone
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T10:02:00"
    Then an episode exists for crew "cordelia" matching:
      | key    | value |
      | status | open  |
    And that episode has 0 scenes

  Scenario: resuming after an idle seal continues the same episode
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                            | model |
      | text | Charted, keep west                 | echo  |
      | text | 1-2: Reef passage charted          | gist  |
      | text | Buoys marked with the tool         | echo  |
      | text | 1-2: (cont 1-2) Buoy tooling       | gist  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T10:05:00"
    Given the current time is "2026-03-01T10:06:00"
    When isaac is run with "prompt -m 'Mark the buoys' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T10:10:00"
    Then crew "cordelia" has 1 episode
    And an episode exists for crew "cordelia" matching:
      | key    | value |
      | status | open  |
    And that episode has scenes matching:
      | gist                 | text                | continues |
      | Reef passage charted | #"(?s)keep west"    |           |
      | Buoy tooling         | #"(?s)Buoys marked" | #".+"     |

  Scenario: another thread can recall the sealed scene within minutes
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                   | model |
      | text | Charted, keep west        | echo  |
      | text | 1-2: Reef passage charted | gist  |
      | text | West of the buoys, aye.   | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T10:05:00"
    Given the current time is "2026-03-01T10:06:00"
    When isaac is run with "prompt -m 'Which way through the reef passage?' --session harbor-log --crew cordelia"
    Then the exit code is 0
    And crew "cordelia" has 2 episodes
    And the last LLM request matches:
      | key      | value                                                  |
      | messages | #"(?s)Recalled from earlier conversations.*recall__scene" |
      | messages | #"(?s)Reef passage charted"                            |

  Scenario: a cold episode closes on the tick and the next message chains a successor
    Given the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                   | model |
      | text | Charted, keep west        | echo  |
      | text | 1-2: Reef passage charted | gist  |
      | text | Watches dogged            | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    When the episodes worker ticks at "2026-03-01T10:05:00"
    When the episodes worker ticks at "2026-03-01T11:05:00"
    Then an episode exists for crew "cordelia" matching:
      | key    | value  |
      | status | closed |
    Given the current time is "2026-03-01T11:10:00"
    When isaac is run with "prompt -m 'Set the watch rotation' --session reef-chat --crew cordelia"
    Then crew "cordelia" has 2 episodes
    And the episodes for crew "cordelia" on thread "reef-chat" chain by lineage
