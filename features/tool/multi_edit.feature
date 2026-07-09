Feature: multi_edit builtin
  N validated string replacements in one tool call, applied atomically.

  Background:
    Given a clean test directory "target/test-state-multi-edit"

  Scenario: three edits across two files in one call
    Given a file "one.txt" exists with content "a"
    And a file "two.txt" exists with content "b\nc"
    When the tool "multi_edit" is called with:
      | edits | [{:file_path "one.txt" :old_string "a" :new_string "A"} {:file_path "two.txt" :old_string "b" :new_string "B"} {:file_path "two.txt" :old_string "c" :new_string "C"}] |
    Then the tool result is not an error
    And the tool result contains "replacement"
    And the file "one.txt" has content "A"
    And the file "two.txt" has content "B\nC"

  Scenario: failing entry aborts with no partial application
    Given a file "only.txt" exists with content "original"
    When the tool "multi_edit" is called with:
      | edits | [{:file_path "only.txt" :old_string "original" :new_string "changed"} {:file_path "only.txt" :old_string "nope" :new_string "x"}] |
    Then the tool result is an error
    And the tool result contains "edit entry 2"
    And the file "only.txt" has content "original"

  Scenario: sequential dependence within one file
    Given a file "chain.txt" exists with content "foo bar"
    When the tool "multi_edit" is called with:
      | edits | [{:file_path "chain.txt" :old_string "foo" :new_string "FOO"} {:file_path "chain.txt" :old_string "FOO bar" :new_string "done"}] |
    Then the tool result is not an error
    And the file "chain.txt" has content "done"

  Scenario: replace_all on one entry
    Given a file "many.txt" exists with content "x\nx"
    When the tool "multi_edit" is called with:
      | edits | [{:file_path "many.txt" :old_string "x" :new_string "y" :replace_all true}] |
    Then the tool result is not an error
    And the file "many.txt" has content "y\ny"

  Scenario: ambiguous old_string without replace_all fails the call
    Given a file "dup.txt" exists with content "z\nz"
    When the tool "multi_edit" is called with:
      | edits | [{:file_path "dup.txt" :old_string "z" :new_string "w"}] |
    Then the tool result is an error
    And the tool result contains "multiple"
