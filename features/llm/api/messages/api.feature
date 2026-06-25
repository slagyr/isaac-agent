Feature: Anthropic Messages API surface
  Wire-shape tests for the messages API. Isaac's 0-10 effort knob maps to
  Anthropic adaptive thinking: effort 0 omits the thinking block; effort
  1-10 sends body.thinking.type "adaptive" with body.output_config.effort
  (low/medium/high/max). max_tokens is a flat output cap (16000). There is
  no budget_tokens — the deprecated enabled/budget path is not used. The
  adapter is model-name-agnostic; a model that should not think sets
  effort 0 in config.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/models/claude.edn" exists with:
      | path           | value             |
      | model          | claude-sonnet-4-6 |
      | provider       | grover:anthropic  |
      | context-window | 200000            |
    And the isaac EDN file "config/crew/thinker.edn" exists with:
      | path  | value       |
      | model | claude      |
      | soul  | Think hard. |
    And the following sessions exist:
      | name     | crew    |
      | thinking | thinker |
    And the following model responses are queued:
      | model             | type | content |
      | claude-sonnet-4-6 | text | ok      |

  Scenario: adaptive thinking sends type adaptive and an effort level, no budget_tokens
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 7           |
    When the user sends "hi" on session "thinking"
    Then the last outbound HTTP request matches:
      | key                       | value    |
      | body.thinking.type        | adaptive |
      | body.output_config.effort | high     |
      | body.max_tokens           | 16000    |
    And the last provider request does not contain path "body.thinking.budget_tokens"

  Scenario: effort 0 sends no thinking block
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 0           |
    When the user sends "hi" on session "thinking"
    Then the last provider request does not contain path "body.thinking"
    And the last provider request does not contain path "body.output_config"
    And the last outbound HTTP request matches:
      | key             | value |
      | body.max_tokens | 16000 |

  Scenario: effort 1 maps to adaptive effort low
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 1           |
    When the user sends "hi" on session "thinking"
    Then the last outbound HTTP request matches:
      | key                       | value |
      | body.output_config.effort | low   |

  Scenario: effort 3 maps to adaptive effort low
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 3           |
    When the user sends "hi" on session "thinking"
    Then the last outbound HTTP request matches:
      | key                       | value |
      | body.output_config.effort | low   |

  Scenario: effort 4 maps to adaptive effort medium
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 4           |
    When the user sends "hi" on session "thinking"
    Then the last outbound HTTP request matches:
      | key                       | value  |
      | body.output_config.effort | medium |

  Scenario: effort 6 maps to adaptive effort medium
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 6           |
    When the user sends "hi" on session "thinking"
    Then the last outbound HTTP request matches:
      | key                       | value  |
      | body.output_config.effort | medium |

  Scenario: effort 9 maps to adaptive effort high
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 9           |
    When the user sends "hi" on session "thinking"
    Then the last outbound HTTP request matches:
      | key                       | value |
      | body.output_config.effort | high  |

  Scenario: effort 10 maps to adaptive effort max
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 10          |
    When the user sends "hi" on session "thinking"
    Then the last outbound HTTP request matches:
      | key                       | value |
      | body.output_config.effort | max   |
