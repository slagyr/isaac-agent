Feature: Crew memory tools
  Each crew has a persistent memory directory under their quarters
  (<root>/crew/<id>/memory/). Three tools access it:
  - memory_write:  append to today's journal note
  - memory_get:    read notes within a date range (inclusive)
  - memory_search: grep the crew's memory files

  Memory is per-crew. Daily notes are keyed by UTC date.

  Background:
    Given default Grover setup in "isaac-state"
    And the current time is "2026-04-21T10:00:00Z"

  Scenario: memory_write appends content to today's note
    When the tool "memory_write" is called with:
      | content | Hieronymus hates artichokes. |
    Then the file "crew/main/memory/2026-04-21.md" matches:
      | text                         |
      | Hieronymus hates artichokes. |

  Scenario: memory_write accepts an array of entries
    When the tool "memory_write" is called with:
      | content | ["Orpheus the cat sneaks in during rainstorms." "Grandma's sourdough lives on /Volumes/zane/recipes."] |
    Then the file "crew/main/memory/2026-04-21.md" matches:
      | text                                                |
      | Orpheus the cat sneaks in during rainstorms.        |
      | Grandma's sourdough lives on /Volumes/zane/recipes. |

  Scenario: memory_write appends to existing content instead of overwriting
    When the tool "memory_write" is called with:
      | content | The vault door creaks when opened clockwise. |
    And the tool "memory_write" is called with:
      | content | Hieronymus fell asleep under the stairs again. |
    Then the file "crew/main/memory/2026-04-21.md" matches:
      | text                                           |
      | The vault door creaks when opened clockwise.   |
      | Hieronymus fell asleep under the stairs again. |

  Scenario: memory_get reads a day's note within the range
    Given a file "crew/main/memory/2026-04-15.md" exists with content "Orpheus brought a dead mouse to the back door."
    When the tool "memory_get" is called with:
      | start_time | 2026-04-15 |
      | end_time   | 2026-04-15 |
    Then the tool result is not an error
    And the tool result lines match:
      | text                                           |
      | Orpheus brought a dead mouse to the back door. |

  Scenario: memory_get returns notes from each day in an inclusive range
    Given a file "crew/main/memory/2026-04-14.md" exists with content "The moonflowers bloomed last night."
    And a file "crew/main/memory/2026-04-16.md" exists with content "Wind knocked over the hedgehog figurine."
    And a file "crew/main/memory/2026-04-19.md" exists with content "User found a geode in the attic."
    When the tool "memory_get" is called with:
      | start_time | 2026-04-14 |
      | end_time   | 2026-04-16 |
    Then the tool result is not an error
    And the tool result lines match:
      | text                                     |
      | The moonflowers bloomed last night.      |
      | Wind knocked over the hedgehog figurine. |
    And the tool result does not contain "User found a geode in the attic."

  Scenario: memory_search returns matching lines across all memory files
    Given a file "crew/main/memory/2026-04-15.md" exists with content "Orpheus brought a dead mouse to the back door."
    And a file "crew/main/memory/2026-04-19.md" exists with content "Orpheus sulked under the porch for most of the afternoon."
    And a file "crew/main/memory/2026-04-20.md" exists with content "The moonflowers bloomed last night."
    When the tool "memory_search" is called with:
      | query | Orpheus |
    Then the tool result is not an error
    And the tool result lines match:
      | text                         |
      | 2026-04-15                   |
      | Orpheus brought a dead mouse |
      | 2026-04-19                   |
      | Orpheus sulked               |
    And the tool result does not contain "moonflowers"

  Scenario: memory_search is case-insensitive
    Given a file "crew/main/memory/2026-04-15.md" exists with content "Orpheus brought a dead mouse to the back door."
    When the tool "memory_search" is called with:
      | query | orpheus |
    Then the tool result is not an error
    And the tool result contains "Orpheus brought a dead mouse"
