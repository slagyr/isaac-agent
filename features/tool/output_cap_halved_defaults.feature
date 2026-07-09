Feature: Tool output cap halved code defaults
  When no tools.defaults override is configured, registry snapshot caps
  fall back to output_cap.clj defaults (1000 lines / 131072 bytes).

  Scenario: with no config override a long read truncates at the default line cap
    Given an empty Isaac root at "/test"
    And a file "/test/many-lines.txt" exists with 1020 lines
    When tool "read" is executed with:
      | file_path            |
      | /test/many-lines.txt |
    Then the tool result contains "[ 20 lines truncated; line cap hit ]"