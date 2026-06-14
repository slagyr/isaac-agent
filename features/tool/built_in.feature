Feature: Built-in Tools

  Isaac provides built-in tools for file operations and
  shell command execution.

  Background:
    Given a clean test directory "target/test-state-tools"

  # --- read ---

  Scenario: Read a file
    Given a file "test.txt" exists with content "line one\nline two\nline three"
    When the tool "read" is called with:
      | file_path | test.txt |
    Then the tool result contains "line one"
    And the tool result contains "line three"
    And the tool result is not an error

  Scenario: Read a file with offset and limit
    Given a file "long.txt" exists with 100 lines
    When the tool "read" is called with:
      | file_path | long.txt |
      | offset   | 10       |
      | limit    | 5        |
    Then the tool result contains "line 10"
    And the tool result contains "line 14"
    And the tool result does not contain "line 9"
    And the tool result does not contain "line 15"

  Scenario: Read a directory
    Given a directory "mydir" exists with files "a.txt" and "b.txt"
    When the tool "read" is called with:
      | file_path | mydir |
    Then the tool result contains "a.txt"
    And the tool result contains "b.txt"

  Scenario: Read a missing file
    When the tool "read" is called with:
      | file_path | no-such-file.txt |
    Then the tool result is an error
    And the tool result contains "not found"

  Scenario: read output prefixes each line with its line number
    Given a file "test.txt" exists with content "alpha\nbeta\ngamma"
    When the tool "read" is called with:
      | file_path | test.txt |
    Then the tool result is not an error
    And the tool result lines match:
      | text     |
      | 1: alpha |
      | 2: beta  |
      | 3: gamma |

  Scenario: read truncates output at the default line limit
    Given the default "read" limit is 3
    And a file "medium.txt" exists with 5 lines
    When the tool "read" is called with:
      | file_path | medium.txt |
    Then the tool result is not an error
    And the tool result lines match:
      | text      |
      | 1: line 1 |
      | 3: line 3 |
      | truncated |
      | 5         |
    And the tool result does not contain "4: line 4"

  Scenario: read refuses to dump binary files
    Given a binary file "image.bin" exists
    When the tool "read" is called with:
      | file_path | image.bin |
    Then the tool result is an error
    And the tool result contains "binary"

  Scenario: read on an empty file returns a clear empty-file signal
    Given a file "empty.txt" exists with content ""
    When the tool "read" is called with:
      | file_path | empty.txt |
    Then the tool result is not an error
    And the tool result lines match:
      | text         |
      | <empty file> |

  Scenario: read with offset and limit preserves absolute line numbers
    Given a file "long.txt" exists with 100 lines
    When the tool "read" is called with:
      | file_path | long.txt |
      | offset   | 10       |
      | limit    | 3        |
    Then the tool result is not an error
    And the tool result lines match:
      | text        |
      | 10: line 10 |
      | 11: line 11 |
      | 12: line 12 |
    And the tool result does not contain "line 9"
    And the tool result does not contain "13: line 13"

  Scenario: read on a directory lists entries without line numbers
    Given a directory "mydir" exists with files "a.txt" and "b.txt"
    When the tool "read" is called with:
      | file_path | mydir |
    Then the tool result is not an error
    And the tool result lines match:
      | text  |
      | a.txt |
      | b.txt |
    And the tool result does not contain "1:"
    And the tool result does not contain "2:"

  Scenario: the tool result lines match step accepts negative indices
    Given a file "tail.txt" exists with content "alpha\nbeta\ngamma"
    When the tool "read" is called with:
      | file_path | tail.txt |
    Then the tool result lines match:
      | text  | #index |
      | gamma | -1     |
      | beta  | -2     |

  # --- write ---

  Scenario: Write a new file
    When the tool "write" is called with:
      | file_path | new.txt     |
      | content  | hello world |
    Then the tool result is not an error
    And the file "new.txt" has content "hello world"

  Scenario: Overwrite an existing file
    Given a file "existing.txt" exists with content "old"
    When the tool "write" is called with:
      | file_path | existing.txt |
      | content  | new          |
    Then the file "existing.txt" has content "new"

  # --- edit ---

  Scenario: Edit replaces matching text
    Given a file "code.txt" exists with content "foo = 1\nbar = 2"
    When the tool "edit" is called with:
      | file_path  | code.txt |
      | old_string | foo = 1  |
      | new_string | foo = 42 |
    Then the tool result is not an error
    And the file "code.txt" has content "foo = 42\nbar = 2"

  Scenario: Edit with no match returns error
    Given a file "code.txt" exists with content "foo = 1"
    When the tool "edit" is called with:
      | file_path  | code.txt    |
      | old_string | not here    |
      | new_string | replacement |
    Then the tool result is an error
    And the tool result contains "not found"

  Scenario: Edit with multiple matches and no replace_all returns error
    Given a file "code.txt" exists with content "x = 1\nx = 1\nx = 1"
    When the tool "edit" is called with:
      | file_path  | code.txt |
      | old_string | x = 1    |
      | new_string | x = 2    |
    Then the tool result is an error
    And the tool result contains "multiple"

  Scenario: Edit with replace_all replaces all occurrences
    Given a file "code.txt" exists with content "x = 1\ny = 2\nx = 1"
    When the tool "edit" is called with:
      | file_path   | code.txt |
      | old_string  | x = 1    |
      | new_string  | x = 99   |
      | replace_all | true     |
    Then the tool result is not an error
    And the file "code.txt" has content "x = 99\ny = 2\nx = 99"

  # --- exec ---

  Scenario: Execute a shell command
    When the tool "exec" is called with:
      | command | echo hello world |
    Then the tool result contains "hello world"
    And the tool result is not an error

  Scenario: Execute a failing command
    When the tool "exec" is called with:
      | command | exit 1 |
    Then the tool result is an error

  Scenario: Execute with a working directory
    Given a directory "subdir" exists with files "target.txt"
    When the tool "exec" is called with:
      | command | ls     |
      | workdir | subdir |
    Then the tool result contains "target.txt"

  Scenario: Execute defaults workdir to the session's cwd
    Given a directory "session-cwd" exists with files "marker.txt"
    And the following sessions exist:
      | name     | crew | cwd         |
      | exec-cwd | main | session-cwd |
    And the current session is "exec-cwd"
    When the tool "exec" is called with:
      | command | ls |
    Then the tool result contains "marker.txt"

  Scenario: Explicit workdir overrides the session's cwd
    Given a directory "session-cwd" exists with files "session-marker.txt"
    And a directory "explicit-dir" exists with files "explicit-marker.txt"
    And the following sessions exist:
      | name     | crew | cwd         |
      | exec-cwd | main | session-cwd |
    And the current session is "exec-cwd"
    When the tool "exec" is called with:
      | command | ls           |
      | workdir | explicit-dir |
    Then the tool result contains "explicit-marker.txt"
    And the tool result does not contain "session-marker.txt"

  Scenario: Execute with timeout exceeded
    Given the exec timeout is set to 25 milliseconds
    When the tool "exec" is called with:
      | command | sleep 10 |
    Then the tool result is an error
    And the tool result contains "timeout"
