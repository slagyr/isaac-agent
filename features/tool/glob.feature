Feature: Built-in glob tool
  Crew members find files by shell-style glob patterns without shelling out.
  Sorted by modification time, newest first. Directory-scoped when invoked
  through a session; direct tool calls here exercise pattern matching,
  sort order, and default limits.

  Background:
    Given a clean test directory "target/test-state-glob"

  Scenario: glob returns matching file paths
    Given the following files exist:
      | name         | mtime                |
      | src/core.clj | 2026-04-20T00:00:01Z |
      | src/util.clj | 2026-04-20T00:00:02Z |
      | src/notes.md | 2026-04-20T00:00:03Z |
    When the tool "glob" is called with:
      | pattern | **/*.clj |
    Then the tool result is not an error
    And the tool result lines match:
      | text         |
      | src/util.clj |
      | src/core.clj |
    And the tool result does not contain "notes.md"

  Scenario: glob sorts results by modification time, newest first
    Given the following files exist:
      | name        | mtime                |
      | src/old.clj | 2020-01-01T00:00:00Z |
      | src/new.clj | 2026-04-20T00:00:00Z |
    When the tool "glob" is called with:
      | pattern | src/*.clj |
    Then the tool result is not an error
    And the tool result lines match:
      | text        |
      | src/new.clj |
      | src/old.clj |

  Scenario: glob with no matches returns a clear no-matches result
    Given the following files exist:
      | name      |
      | README.md |
    When the tool "glob" is called with:
      | pattern | **/*.clj |
    Then the tool result is not an error
    And the tool result lines match:
      | text       |
      | no matches |

  Scenario: glob truncates output at the default head_limit
    Given the default "glob" head_limit is 3
    And the following files exist:
      | name  | mtime                |
      | a.clj | 2026-04-20T00:00:01Z |
      | b.clj | 2026-04-20T00:00:02Z |
      | c.clj | 2026-04-20T00:00:03Z |
      | d.clj | 2026-04-20T00:00:04Z |
      | e.clj | 2026-04-20T00:00:05Z |
    When the tool "glob" is called with:
      | pattern | *.clj |
    Then the tool result is not an error
    And the tool result lines match:
      | text      |
      | e.clj     |
      | d.clj     |
      | c.clj     |
      | truncated |
      | 5         |
    And the tool result does not contain "b.clj"

  Scenario: glob respects explicit head_limit
    Given the following files exist:
      | name  | mtime                |
      | a.clj | 2026-04-20T00:00:01Z |
      | b.clj | 2026-04-20T00:00:02Z |
      | c.clj | 2026-04-20T00:00:03Z |
      | d.clj | 2026-04-20T00:00:04Z |
      | e.clj | 2026-04-20T00:00:05Z |
    When the tool "glob" is called with:
      | pattern    | *.clj |
      | head_limit | 2     |
    Then the tool result is not an error
    And the tool result lines match:
      | text      |
      | e.clj     |
      | d.clj     |
      | truncated |
      | 5         |
    And the tool result does not contain "c.clj"
