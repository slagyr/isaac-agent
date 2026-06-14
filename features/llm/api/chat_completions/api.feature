Feature: OpenAI Completions API — effort wire translation
  When a request carries an :effort integer, the completions adapter maps it
  to the top-level reasoning_effort string field (low/medium/high).
  Effort 0 omits the field. Absent :effort also omits the field.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/models/g5.edn" exists with:
      | path           | value             |
      | model          | gpt-5             |
      | provider       | grover:openai |
      | context-window | 128000            |
    And the isaac EDN file "config/crew/thinker.edn" exists with:
      | path  | value       |
      | model | g5          |
      | soul  | Think hard. |
    And the following sessions exist:
      | name | crew    |
      | desk | thinker |
    And the following model responses are queued:
      | model | type | content |
      | gpt-5 | text | ok      |

  Scenario: Effort 7 maps to reasoning_effort high
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | g5          |
      | soul   | Think hard. |
      | effort | 7           |
    When the user sends "hi" on session "desk"
    Then the last outbound HTTP request matches:
      | key                   | value |
      | body.reasoning_effort | high  |

  Scenario: Effort 10 maps to reasoning_effort high
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | g5          |
      | soul   | Think hard. |
      | effort | 10          |
    When the user sends "hi" on session "desk"
    Then the last outbound HTTP request matches:
      | key                   | value |
      | body.reasoning_effort | high  |

  Scenario: Effort 5 maps to reasoning_effort medium
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | g5          |
      | soul   | Think hard. |
      | effort | 5           |
    When the user sends "hi" on session "desk"
    Then the last outbound HTTP request matches:
      | key                   | value  |
      | body.reasoning_effort | medium |

  Scenario: Effort 2 maps to reasoning_effort low
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | g5          |
      | soul   | Think hard. |
      | effort | 2           |
    When the user sends "hi" on session "desk"
    Then the last outbound HTTP request matches:
      | key                   | value |
      | body.reasoning_effort | low   |

  Scenario: Effort 0 omits reasoning_effort
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | g5          |
      | soul   | Think hard. |
      | effort | 0           |
    When the user sends "hi" on session "desk"
    Then the last provider request does not contain path "body.reasoning_effort"

  Scenario: allows-effort false omits reasoning_effort
    Given the isaac EDN file "config/models/g5.edn" exists with:
      | path          | value             |
      | model         | gpt-5             |
      | provider      | grover:openai |
      | context-window | 128000           |
      | allows-effort | false             |
    When the user sends "hi" on session "desk"
    Then the last provider request does not contain path "body.reasoning_effort"
