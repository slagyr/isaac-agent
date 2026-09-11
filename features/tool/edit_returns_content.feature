Feature: Edit returns the updated content
  Workers read a file back after every edit to see what they did: 1931 of
  8538 tool calls on the isaac crews in one day (2026-09-09/10) were a read
  of a file the worker had just edited. `fs__edit`, `fs__multi_edit` and
  `fs__write` answered "edited <path>", so the read-back was the only way
  to see the result. They now answer with the edited region — the changed
  lines plus a few lines of context, numbered like `fs__read` — so the
  read-back has no reason to exist (isaac-yk0u, decision 1).

  Background:
    Given a clean test directory "target/test-state-edit-content"

  Scenario: edit returns the updated region, not a receipt
    Given a file "code.txt" exists with content "alpha\nbeta\nfoo = 1\ngamma\ndelta\nepsilon"
    When the tool "fs__edit" is called with:
      | file_path  | code.txt |
      | old_string | foo = 1  |
      | new_string | foo = 42 |
    Then the tool result is not an error
    And the tool result lines match:
      | text        |
      | 1: alpha    |
      | 2: beta     |
      | 3: foo = 42 |
      | 4: gamma    |
      | 5: delta    |
    And the tool result does not contain "epsilon"
    And the tool result does not contain "edited code.txt"
