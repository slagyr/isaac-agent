Feature: OpenAI Responses API — effort wire translation
  When a request carries an :effort integer, the responses adapter maps it
  to the nested reasoning.effort string field (low/medium/high) with
  summary "auto". Effort 0 or absent :effort omits the reasoning block.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/models/snuffy.edn" exists with:
      | path           | value                 |
      | model          | snuffy-codex          |
      | provider       | grover:chatgpt |
      | context-window | 128000                |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value                 |
      | model | snuffy                |
      | soul  | Lives in a trash can. |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | model        | type | content |
      | snuffy-codex | text | ok      |

  Scenario: Effort 7 maps to reasoning high with summary auto
    Given the isaac EDN file "config/crew/oscar.edn" exists with:
      | path   | value                 |
      | model  | snuffy                |
      | soul   | Lives in a trash can. |
      | effort | 7                     |
    When the user sends "hi" on session "trash-can"
    Then the last outbound HTTP request matches:
      | key                    | value |
      | body.reasoning.effort  | high  |
      | body.reasoning.summary | auto  |

  Scenario: Effort 5 maps to reasoning medium with summary auto
    Given the isaac EDN file "config/crew/oscar.edn" exists with:
      | path   | value                 |
      | model  | snuffy                |
      | soul   | Lives in a trash can. |
      | effort | 5                     |
    When the user sends "hi" on session "trash-can"
    Then the last outbound HTTP request matches:
      | key                    | value  |
      | body.reasoning.effort  | medium |
      | body.reasoning.summary | auto   |

  Scenario: Effort 2 maps to reasoning low with summary auto
    Given the isaac EDN file "config/crew/oscar.edn" exists with:
      | path   | value                 |
      | model  | snuffy                |
      | soul   | Lives in a trash can. |
      | effort | 2                     |
    When the user sends "hi" on session "trash-can"
    Then the last outbound HTTP request matches:
      | key                    | value |
      | body.reasoning.effort  | low   |
      | body.reasoning.summary | auto  |

  Scenario: Effort 0 omits the reasoning block
    Given the isaac EDN file "config/crew/oscar.edn" exists with:
      | path   | value                 |
      | model  | snuffy                |
      | soul   | Lives in a trash can. |
      | effort | 0                     |
    When the user sends "hi" on session "trash-can"
    Then the last provider request does not contain path "body.reasoning"

  Scenario: allows-effort false omits the reasoning block
    Given the isaac EDN file "config/models/snuffy.edn" exists with:
      | path          | value                 |
      | model         | snuffy-codex          |
      | provider      | grover:chatgpt |
      | context-window | 128000               |
      | allows-effort | false                 |
    When the user sends "hi" on session "trash-can"
    Then the last provider request does not contain path "body.reasoning"
