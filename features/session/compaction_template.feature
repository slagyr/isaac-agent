Feature: Compaction summary template
  The summarizer is prompted with a nine-section template (the shape
  Claude Code and Grok harnesses converged on): request and intent,
  concepts, files and code, errors and fixes, problem solving, all user
  messages, pending tasks, current work, next step. It carries prior
  summaries forward so successive compactions compose instead of
  eroding, and it never narrates the summarization instruction itself.
  An operator may replace the template verbatim with an optional
  config/compaction.md, read at compaction time (no restart).

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/local.edn" exists with:
      | path | value |
      | model | test-model |
      | provider | grover |
      | context-window | 200 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | local |
      | soul | You are Atticus. |
    And the current time is "2026-04-21T10:00:00Z"
    And the following sessions exist:
      | name       | compaction.head |
      | log-keeper | 0.1             |
    And session "log-keeper" has transcript:
      | type    | message.role | message.content                                                            |
      | message | user         | I take tea with two sugars and a splash of milk first thing every morning  |
      | message | assistant    | Noted — tea with two sugars and a splash of milk, served first thing it is |
      | message | user         | Also remember I prefer the loose-leaf Assam blend over the supermarket bags |
      | message | assistant    | Understood — loose-leaf Assam over supermarket teabags, noted for the future |
    And the following model responses are queued:
      | type | content                           | model      |
      | text | Discussion about tea preferences. | test-model |
      | text | Here is my response.              | test-model |

  Scenario: the built-in template asks for nine sections and carries prior summaries forward
    When the user sends "hello" on session "log-keeper"
    Then the compaction request matches:
      | key                 | value |
      | messages[0].content | #"(?s)(?=.*1\. Primary Request and Intent)(?=.*2\. Key Technical Concepts)(?=.*3\. Files and Code Sections)(?=.*4\. Errors and Fixes)(?=.*5\. Problem Solving)(?=.*6\. All User Messages)(?=.*7\. Pending Tasks)(?=.*8\. Current Work)(?=.*9\. Optional Next Step)(?=.*prior compaction summary)(?=.*carry its still-relevant content forward)(?=.*Do NOT include this summarization instruction).*" |

  Scenario: config/compaction.md replaces the template verbatim
    Given the isaac file "config/compaction.md" exists with:
      """
      Summarize the voyage in the style of a ship's log: date, heading, weather, incidents.
      """
    When the user sends "hello" on session "log-keeper"
    Then the compaction request matches:
      | key                 | value                                            |
      | messages[0].content | #"(?s)^Summarize the voyage in the style of a ship's log.*" |
