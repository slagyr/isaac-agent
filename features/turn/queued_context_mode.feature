Feature: A queued turn honors :with-context-mode
  A turn submitted by frequencies (a hail, cron, any queue producer) may carry
  :with-context-mode to pick how that one turn's context is built. It beats
  the session's and the crew's context mode for that turn only, the same as
  --with-context-mode does on the CLI (isaac-zdnx). (isaac-onzi)

  Background:
    Given default Grover setup
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path         | value             |
      | model        | echo              |
      | soul         | You are a pirate. |
      | context-mode | reset             |
    And the isaac EDN file "config/crew/lookout.edn" exists with:
      | path  | value              |
      | model | echo               |
      | soul  | You are a lookout. |
    And the following sessions exist:
      | name       | crew    |
      | mooring    | ketch   |
      | crows-nest | lookout |
    And session "mooring" has transcript:
      | type    | message.role | message.content |
      | message | user         | Ahoy there      |
      | message | assistant    | Arr, matey      |
    And session "crows-nest" has transcript:
      | type    | message.role | message.content |
      | message | user         | Sail ho         |
      | message | assistant    | Where away?     |
    And the following model responses are queued:
      | type | content | model |
      | text | Aye aye | echo  |

  Scenario: :with-context-mode full replays history for a crew set to reset
    When a turn with input "Status report" is submitted with frequencies:
      | key               | value   |
      | session           | mooring |
      | with-context-mode | full    |
    And the turn queue ticks at "2026-10-09T09:15:00Z"
    Then the last LLM request matches:
      | key                 | value         |
      | messages[1].role    | user          |
      | messages[1].content | Ahoy there    |
      | messages[2].role    | assistant     |
      | messages[2].content | Arr, matey    |
      | messages[3].role    | user          |
      | messages[3].content | Status report |

  Scenario: :with-context-mode reset drops history for a crew left at full
    When a turn with input "Status report" is submitted with frequencies:
      | key               | value      |
      | session           | crows-nest |
      | with-context-mode | reset      |
    And the turn queue ticks at "2026-10-09T09:15:00Z"
    Then the last LLM request matches:
      | key                 | value         |
      | messages[0].role    | system        |
      | messages[1].role    | user          |
      | messages[1].content | Status report |
