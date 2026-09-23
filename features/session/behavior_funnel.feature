Feature: Canonical session-behavior resolution funnel
  All session-behavior reads route through a single `resolve-behavior`
  function. Each field is either *state-defining* (cascade resolves once
  at session creation; result is locked onto the sidecar) or *behavioral*
  (cascade resolves fresh on every call). The funnel encodes the
  locked-vs-cascade distinction in one place.

  A default is a template: :defaults :<entity> reads as if every entity of
  that kind had written those fields, so a lower layer's key survives a
  higher layer that sets a different key. Compaction therefore merges key by
  key — a crew naming only :strategy keeps the default's :threshold
  (isaac-ruom).

  Tracked by isaac-bv48.

  Background:
    Given an empty Isaac root at "/test"
    And the isaac config path "defaults.crew.model" is "spark"
    And the isaac config path "providers.test.api" is "grover"
    And the isaac config path "models.spark.model" is "echo"
    And the isaac config path "models.spark.provider" is "test"
    And the isaac config path "models.parrot.model" is "echo"
    And the isaac config path "models.parrot.provider" is "test"
    And the isaac config path "models.swift.model" is "echo"
    And the isaac config path "models.swift.provider" is "test"

  Scenario Outline: Cascade precedence resolves the right <field> at creation
    Given the isaac config path "<defaults-path>" is "<defaults>"
    And the isaac config path "crew.main.<field>" is "<crew>"
    And the isaac config path "models.spark.<field>" is "<model>"
    And the isaac config path "providers.test.<field>" is "<provider>"
    When a session "s" is created with explicit <field> "<creator>"
    Then the resolved behavior for "s" has <field> "<expected>"

    Examples:
      | field             | defaults-path                       | creator           | crew                | model            | provider         | defaults         | expected                                                   |
      | history-retention | defaults.provider.history-retention |                   |                     |                  |                  |                  | :retain                                                    |
      | history-retention | defaults.provider.history-retention |                   |                     |                  |                  | :prune           | :prune                                                     |
      | history-retention | defaults.provider.history-retention |                   |                     |                  | :retain          | :prune           | :retain                                                    |
      | history-retention | defaults.provider.history-retention |                   |                     | :prune           | :retain          | :retain          | :prune                                                     |
      | history-retention | defaults.provider.history-retention |                   | :retain             | :prune           | :prune           | :prune           | :retain                                                    |
      | history-retention | defaults.provider.history-retention | :prune            | :retain             | :retain          | :retain          | :retain          | :prune                                                     |
      | effort            | defaults.provider.effort            |                   |                     |                  |                  |                  | 7                                                          |
      | effort            | defaults.provider.effort            |                   |                     |                  |                  | 5                | 5                                                          |
      | effort            | defaults.provider.effort            |                   |                     |                  | 6                | 5                | 6                                                          |
      | effort            | defaults.provider.effort            |                   |                     | 7                | 6                | 5                | 7                                                          |
      | effort            | defaults.provider.effort            |                   | 8                   | 7                | 6                | 5                | 8                                                          |
      | effort            | defaults.provider.effort            | 9                 | 8                   | 7                | 6                | 5                | 9                                                          |
      | model             | defaults.crew.model                 |                   |                     |                  |                  | spark            | spark                                                      |
      | model             | defaults.crew.model                 |                   | parrot              |                  |                  | spark            | parrot                                                     |
      | model             | defaults.crew.model                 | swift             | parrot              |                  |                  | spark            | swift                                                      |
      | crew              | defaults.frequencies.crew           |                   |                     |                  |                  |                  | main                                                       |
      | crew              | defaults.frequencies.crew           |                   |                     |                  |                  | alice            | alice                                                      |
      | crew              | defaults.frequencies.crew           | bob               |                     |                  |                  | alice            | bob                                                        |
      | cwd               | defaults.crew.cwd                   |                   |                     |                  |                  |                  | /test/.isaac/crew/main                                     |
      | cwd               | defaults.crew.cwd                   | /tmp/work         |                     |                  |                  |                  | /tmp/work                                                  |
      | context-mode      | defaults.crew.context-mode          |                   |                     |                  |                  |                  | :full                                                      |
      | context-mode      | defaults.crew.context-mode          |                   |                     |                  |                  | :reset           | :reset                                                     |
      | context-mode      | defaults.crew.context-mode          |                   | :reset              |                  |                  | :full            | :reset                                                     |
      | context-mode      | defaults.crew.context-mode          | :full             | :reset              |                  |                  | :reset           | :full                                                      |
      | compaction        | defaults.provider.compaction        |                   |                     |                  |                  |                  | {:strategy :rubberband :threshold 0.8}                     |
      | compaction        | defaults.provider.compaction        |                   |                     |                  |                  | {:threshold 0.4} | {:strategy :rubberband :threshold 0.4}                     |
      | compaction        | defaults.provider.compaction        |                   |                     |                  | {:threshold 0.5} | {:threshold 0.4} | {:strategy :rubberband :threshold 0.5}                     |
      | compaction        | defaults.provider.compaction        |                   |                     | {:threshold 0.6} | {:threshold 0.5} | {:threshold 0.4} | {:strategy :rubberband :threshold 0.6}                     |
      | compaction        | defaults.provider.compaction        |                   | {:threshold 0.7}    | {:threshold 0.6} | {:threshold 0.5} | {:threshold 0.4} | {:strategy :rubberband :threshold 0.7}                     |
      | compaction        | defaults.provider.compaction        | {:threshold 0.75} | {:threshold 0.7}    | {:threshold 0.6} | {:threshold 0.5} | {:threshold 0.4} | {:strategy :rubberband :threshold 0.75}                    |
      | compaction        | defaults.provider.compaction        |                   | {:strategy :slinky} |                  |                  | {:threshold 0.4} | {:strategy :slinky :threshold 0.4 :head 0.3 :async? false} |

  Scenario Outline: Resolution after config change for <field>
    Given the isaac config path "<defaults-path>" is "<initial>"
    And a session "s" is created with explicit <field> ""
    And the isaac config path "<defaults-path>" is "<changed>"
    Then the resolved behavior for "s" has <field> "<expected>"

    # State-defining fields (rows 1-3) lock at creation: expected = initial.
    # Behavioral fields (rows 4-8) re-cascade: expected = changed.
    Examples:
      | field             | defaults-path                       | initial          | changed          | expected                               |
      | history-retention | defaults.provider.history-retention | :retain          | :prune           | :retain                                |
      | history-retention | defaults.provider.history-retention | :prune           | :retain          | :prune                                 |
      | crew              | defaults.frequencies.crew           | alice            | bob              | alice                                  |
      | effort            | defaults.provider.effort            | 5                | 9                | 9                                      |
      | effort            | defaults.provider.effort            | 9                | 5                | 5                                      |
      | model             | defaults.crew.model                 | spark            | parrot           | parrot                                 |
      | context-mode      | defaults.crew.context-mode          | :full            | :reset           | :reset                                 |
      | compaction        | defaults.provider.compaction        | {:threshold 0.4} | {:threshold 0.5} | {:strategy :rubberband :threshold 0.5} |

  Scenario: :cwd is locked to a session even when the crew default changes
    Given the isaac config path "defaults.frequencies.crew" is "alice"
    And a session "s" is created with explicit crew ""
    # session 's' locks cwd to /test/.isaac/crew/alice
    And the isaac config path "defaults.frequencies.crew" is "bob"
    # new sessions would now lock to /test/.isaac/crew/bob,
    # but 's' keeps the cwd it locked at creation
    Then the resolved behavior for "s" has cwd "/test/.isaac/crew/alice"
