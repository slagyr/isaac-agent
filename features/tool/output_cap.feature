Feature: Global cap on tool-result output before transcript persist
  Tool results land in the transcript bounded by a global line + byte cap
  applied before persistence. Either bound trips; the truncation marker
  names how much was cut and which cap fired. The on-disk transcript stores
  exactly what the tool returned — no full-fidelity sidecar.

  Tracked by isaac-q7x6.

  Background:
    Given an empty Isaac root at "/test"
    And the EDN isaac file "isaac.edn" exists with:
      | path                     | value |
      | tools.defaults.max-lines | 5     |
      | tools.defaults.max-bytes | 100   |

  Scenario: exec stdout exceeding the byte cap is truncated with a marker naming the cap
    When tool "exec" is executed with:
      | command                           |
      | yes x | tr -d '\n' | head -c 200  |
    Then the tool result contains "[ 100 bytes truncated; byte cap hit ]"

  Scenario: read of a file with many short lines is truncated with a marker naming the line cap
    Given a file "/test/lines.txt" exists with 20 lines
    When tool "read" is executed with:
      | file_path       |
      | /test/lines.txt |
    Then the tool result contains "[ 15 lines truncated; line cap hit ]"

  Scenario: the truncated tool result is what gets persisted to the transcript
    Given session "cap-persist" exists
    When tool "exec" is executed for session "cap-persist" with:
      | command                           |
      | yes x | tr -d '\n' | head -c 200  |
    Then session "cap-persist" has transcript matching:
      | role       | content-matcher                                  |
      | toolResult | contains "[ 100 bytes truncated; byte cap hit ]" |
