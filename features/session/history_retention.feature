Feature: Compaction history retention policy
  After compaction, retention determines whether the compacted entries
  remain on disk. Under :retain (default), they stay in the transcript
  file and the session sidecar records the post-compaction offset so
  the prompt-build read path can seek past them. Under :prune, current
  behavior is preserved: compacted entries are removed.

  Retention is resolved once at session creation (cascade:
  explicit create-time override > crew > model > provider > :defaults
  > :retain) and persisted onto the session sidecar. Changes to crew
  config or installation defaults after the session exists do NOT flip
  its retention — the value is locked.

  Tracked by isaac-q90z.

  Background:
    Given an empty Isaac root at "/test"

  Scenario: Under :retain, compacted entries remain in the transcript file
    Given the EDN isaac file "isaac.edn" exists with:
      | path                       | value   |
      | defaults.history-retention | :retain |
    And the following sessions exist:
      | name        |
      | retain-keep |
    And session "retain-keep" has transcript:
      | type    | message.role | message.content  |
      | message | user         | Earlier question |
      | message | assistant    | Earlier answer   |
      | message | user         | Recent question  |
      | message | assistant    | Recent answer    |
    When compaction is spliced into session "retain-keep" with:
      | key              | value                |
      | summary          | Caught up the model. |
      | firstKeptIndex   | 2                    |
      | compactedIndexes | [0, 1]               |
      | tokensBefore     | 20                   |
    Then session "retain-keep" has transcript matching:
      | type    | message.content  |
      | message | Earlier question |
      | message | Earlier answer   |
      | message | Recent question  |
      | message | Recent answer    |
    And session "retain-keep" has transcript matching:
      | type       | summary              |
      | compaction | Caught up the model. |

  Scenario: Under :prune, compacted entries are removed from the transcript file
    Given the EDN isaac file "isaac.edn" exists with:
      | path                       | value  |
      | defaults.history-retention | :prune |
    And the following sessions exist:
      | name      |
      | prune-cut |
    And session "prune-cut" has transcript:
      | type    | message.role | message.content  |
      | message | user         | Earlier question |
      | message | assistant    | Earlier answer   |
      | message | user         | Recent question  |
      | message | assistant    | Recent answer    |
    When compaction is spliced into session "prune-cut" with:
      | key              | value                |
      | summary          | Caught up the model. |
      | firstKeptIndex   | 2                    |
      | compactedIndexes | [0, 1]               |
      | tokensBefore     | 20                   |
    Then session "prune-cut" has transcript not matching:
      | type    | message.content  |
      | message | Earlier question |
      | message | Earlier answer   |
    And session "prune-cut" has transcript matching:
      | type    | message.content |
      | message | Recent question |
      | message | Recent answer   |
    And session "prune-cut" has transcript matching:
      | type       | summary              |
      | compaction | Caught up the model. |

  Scenario: Retention is locked at session creation; changing defaults later does not flip it
    Given the EDN isaac file "isaac.edn" exists with:
      | path                       | value   |
      | defaults.history-retention | :retain |
    And the following sessions exist:
      | name        |
      | locked-keep |
    Then session "locked-keep" matches:
      | key               | value   |
      | history-retention | :retain |
    When the EDN isaac file "isaac.edn" exists with:
      | path                       | value  |
      | defaults.history-retention | :prune |
    Then session "locked-keep" matches:
      | key               | value   |
      | history-retention | :retain |

  Scenario: Explicit create-time override wins over crew and defaults
    Given the EDN isaac file "isaac.edn" exists with:
      | path                        | value  |
      | defaults.history-retention  | :prune |
      | crew.main.history-retention | :prune |
    And the following sessions exist:
      | name     | crew | history-retention |
      | override | main | :retain           |
    Then session "override" matches:
      | key               | value   |
      | history-retention | :retain |
