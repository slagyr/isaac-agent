Feature: Built-in grep tool
  Crew members search file contents via ripgrep without shelling out.
  Directory-scoped when invoked through a session; direct tool calls
  here exercise argv translation, output formats, and default limits.

  Background:
    Given a clean test directory "target/test-state-grep"

  Scenario: grep returns matching lines with file:line prefix
    Given a file "src/core.clj" exists with content "(defn greet [name])\n(defn shout [name])"
    When the tool "grep" is called with:
      | pattern | defn |
      | path    | src  |
    Then the tool result is not an error
    And the tool result lines match:
      | text        |
      | core.clj:1: |
      | (defn greet |
      | core.clj:2: |
      | (defn shout |

  Scenario: grep with no matches returns a clear no-matches result
    Given a file "src/core.clj" exists with content "(defn greet [name])"
    When the tool "grep" is called with:
      | pattern | xyzzy |
      | path    | src   |
    Then the tool result is not an error
    And the tool result lines match:
      | text       |
      | no matches |

  Scenario: grep glob filter limits search to matching files
    Given a file "src/core.clj" exists with content "(defn greet [name])"
    And a file "src/notes.md" exists with content "defn is a Clojure macro"
    When the tool "grep" is called with:
      | pattern | defn  |
      | path    | src   |
      | glob    | *.clj |
    Then the tool result is not an error
    And the tool result lines match:
      | text        |
      | core.clj    |
      | (defn greet |
    And the tool result does not contain "notes.md"

  Scenario: grep output_mode files_with_matches returns paths only
    Given a file "src/core.clj" exists with content "(defn greet [name])"
    And a file "src/util.clj" exists with content "(defn shout [name])"
    And a file "src/notes.md" exists with content "no matches here"
    When the tool "grep" is called with:
      | pattern     | defn               |
      | path        | src                |
      | output_mode | files_with_matches |
    Then the tool result is not an error
    And the tool result lines match:
      | text     |
      | core.clj |
      | util.clj |
    And the tool result does not contain "notes.md"
    And the tool result does not contain "(defn"

  Scenario: grep output_mode count returns match count per file
    Given a file "src/core.clj" exists with content "(defn greet [name])\n(defn shout [name])"
    And a file "src/util.clj" exists with content "(defn only [name])"
    When the tool "grep" is called with:
      | pattern     | defn  |
      | path        | src   |
      | output_mode | count |
    Then the tool result is not an error
    And the tool result lines match:
      | text       |
      | core.clj:2 |
      | util.clj:1 |

  Scenario: grep truncates output at the default head_limit
    Given the default "grep" head_limit is 3
    And a file "short.txt" exists with 5 lines
    When the tool "grep" is called with:
      | pattern | line      |
      | path    | short.txt |
    Then the tool result is not an error
    And the tool result lines match:
      | text      |
      | line 1    |
      | line 3    |
      | truncated |
    And the tool result does not contain "line 4"

  Scenario: grep respects explicit head_limit
    Given a file "big.txt" exists with 300 lines
    When the tool "grep" is called with:
      | pattern    | line    |
      | path       | big.txt |
      | head_limit | 5       |
    Then the tool result is not an error
    And the tool result lines match:
      | text   |
      | line 1 |
      | line 5 |
    And the tool result does not contain "line 6"

  Scenario: grep with -i flag is case-insensitive
    Given a file "src/core.clj" exists with content "(DEFN greet)\n(defn shout)"
    When the tool "grep" is called with:
      | pattern | defn |
      | path    | src  |
      | -i      | true |
    Then the tool result is not an error
    And the tool result lines match:
      | text        |
      | (DEFN greet |
      | (defn shout |

  Scenario: grep -C includes context lines before and after matches
    Given a file "src/core.clj" exists with content "(ns core)\n(defn before [x] x)\n(defn target [x] x)\n(defn after [x] x)\n(def tail 1)"
    When the tool "grep" is called with:
      | pattern | target |
      | path    | src    |
      | -C      | 1      |
    Then the tool result is not an error
    And the tool result lines match:
      | text                |
      | (defn before [x] x) |
      | (defn target [x] x) |
      | (defn after [x] x)  |
    And the tool result does not contain "(ns core)"
    And the tool result does not contain "(def tail 1)"
