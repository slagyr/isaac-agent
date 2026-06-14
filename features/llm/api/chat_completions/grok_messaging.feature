@wip
Feature: Grok Messaging
  Grok uses the OpenAI-compatible chat completions API.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/count.edn" exists with:
      | path | value |
      | model | count |
      | provider | grover:grok |
      | context-window | 131072 |
    And the isaac EDN file "config/crew/count.edn" exists with:
      | path | value |
      | model | count |
      | soul | Counts everything. |

  Scenario: Provider-returned model version is stored in transcript
    Given the following sessions exist:
      | name     | crew  |
      | counting | count |
    And session "counting" has transcript:
      | type    | message.role | message.content           |
      | message | user         | How many bats do you see? |
    And the following model responses are queued:
      | model          | type | content                 |
      | count-20250710 | text | One! One bat! Ah ah ah! |
    When the user sends "How many bats do you see?" on session "counting"
    Then session "counting" has transcript matching:
      | type    | message.role | message.model  | message.provider |
      | message | assistant    | count-20250710 | grover:grok      |

  Scenario: Configured model is stored when provider returns no model
    Given the following sessions exist:
      | name     | crew  |
      | counting | count |
    And session "counting" has transcript:
      | type    | message.role | message.content  |
      | message | user         | How many clouds? |
    And the following model responses are queued:
      | model | type | content                    |
      |       | text | Two! Two clouds! Ah ah ah! |
    When the user sends "How many clouds?" on session "counting"
    Then session "counting" has transcript matching:
      | type    | message.role | message.model |
      | message | assistant    | count         |
