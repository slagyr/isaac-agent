Feature: Ollama API surface
  Wire-shape tests for the ollama API. Effort integers on the request
  map are translated to the body.think field. Default :bool mode
  collapses to think true/false (universal across thinking-capable
  Ollama models). Opt-in :levels mode buckets to "low"|"medium"|"high"
  for models that accept tier strings.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/models/qwen3.edn" exists with:
      | path           | value         |
      | model          | qwen3         |
      | provider       | grover:ollama |
      | context-window | 32768         |
    And the isaac EDN file "config/crew/qwerty.edn" exists with:
      | path  | value           |
      | model | qwen3           |
      | soul  | Thinks in QWEN. |
    And the following sessions exist:
      | name       | crew   |
      | qwen-house | qwerty |
    And the following model responses are queued:
      | model | type | content |
      | qwen3 | text | ok      |

  Scenario: Bool mode — effort 0 sends think:false
    Given the isaac EDN file "config/crew/qwerty.edn" exists with:
      | path   | value           |
      | model  | qwen3           |
      | soul   | Thinks in QWEN. |
      | effort | 0               |
    When the user sends "hi" on session "qwen-house"
    Then the last outbound HTTP request matches:
      | key        | value |
      | body.think | false |

  Scenario: Bool mode — effort 1 sends think:true
    Given the isaac EDN file "config/crew/qwerty.edn" exists with:
      | path   | value           |
      | model  | qwen3           |
      | soul   | Thinks in QWEN. |
      | effort | 1               |
    When the user sends "hi" on session "qwen-house"
    Then the last outbound HTTP request matches:
      | key        | value |
      | body.think | true  |

  Scenario: Bool mode — effort 7 sends think:true
    Given the isaac EDN file "config/crew/qwerty.edn" exists with:
      | path   | value           |
      | model  | qwen3           |
      | soul   | Thinks in QWEN. |
      | effort | 7               |
    When the user sends "hi" on session "qwen-house"
    Then the last outbound HTTP request matches:
      | key        | value |
      | body.think | true  |

  Scenario: Levels mode — effort 0 omits think field
    Given the isaac EDN file "config/models/qwen3.edn" exists with:
      | path           | value         |
      | model          | qwen3         |
      | provider       | grover:ollama |
      | context-window | 32768         |
      | think-mode     | levels        |
    And the isaac EDN file "config/crew/qwerty.edn" exists with:
      | path   | value           |
      | model  | qwen3           |
      | soul   | Thinks in QWEN. |
      | effort | 0               |
    When the user sends "hi" on session "qwen-house"
    Then the last provider request does not contain path "body.think"

  Scenario: Levels mode — effort 2 sends think:low
    Given the isaac EDN file "config/models/qwen3.edn" exists with:
      | path           | value         |
      | model          | qwen3         |
      | provider       | grover:ollama |
      | context-window | 32768         |
      | think-mode     | levels        |
    And the isaac EDN file "config/crew/qwerty.edn" exists with:
      | path   | value           |
      | model  | qwen3           |
      | soul   | Thinks in QWEN. |
      | effort | 2               |
    When the user sends "hi" on session "qwen-house"
    Then the last outbound HTTP request matches:
      | key        | value |
      | body.think | low   |

  Scenario: Levels mode — effort 5 sends think:medium
    Given the isaac EDN file "config/models/qwen3.edn" exists with:
      | path           | value         |
      | model          | qwen3         |
      | provider       | grover:ollama |
      | context-window | 32768         |
      | think-mode     | levels        |
    And the isaac EDN file "config/crew/qwerty.edn" exists with:
      | path   | value           |
      | model  | qwen3           |
      | soul   | Thinks in QWEN. |
      | effort | 5               |
    When the user sends "hi" on session "qwen-house"
    Then the last outbound HTTP request matches:
      | key        | value  |
      | body.think | medium |

  Scenario: Levels mode — effort 9 sends think:high
    Given the isaac EDN file "config/models/qwen3.edn" exists with:
      | path           | value         |
      | model          | qwen3         |
      | provider       | grover:ollama |
      | context-window | 32768         |
      | think-mode     | levels        |
    And the isaac EDN file "config/crew/qwerty.edn" exists with:
      | path   | value           |
      | model  | qwen3           |
      | soul   | Thinks in QWEN. |
      | effort | 9               |
    When the user sends "hi" on session "qwen-house"
    Then the last outbound HTTP request matches:
      | key        | value |
      | body.think | high  |
