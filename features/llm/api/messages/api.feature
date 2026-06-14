Feature: Anthropic Messages API surface
  Wire-shape tests for the messages API. Effort integers on
  the request map are translated to an integer thinking budget on
  body.thinking.budget_tokens, scaling linearly with the model's
  thinking-budget-max config field. Effort 0 omits the thinking block.

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/models/claude.edn" exists with:
      | path                | value                     |
      | model               | claude-sonnet-4-5         |
      | provider            | grover:anthropic          |
      | context-window      | 200000                    |
      | thinking-budget-max | 32000                     |
    And the isaac EDN file "config/crew/thinker.edn" exists with:
      | path  | value       |
      | model | claude      |
      | soul  | Think hard. |
    And the following sessions exist:
      | name     | crew    |
      | thinking | thinker |
    And the following model responses are queued:
      | model             | type | content |
      | claude-sonnet-4-5 | text | ok      |

  Scenario: Effort 0 omits the thinking block entirely
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 0           |
    When the user sends "hi" on session "thinking"
    Then the last provider request does not contain path "body.thinking"

  Scenario: Effort 1 maps to 10% of budget-max (3200 tokens)
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 1           |
    When the user sends "hi" on session "thinking"
    Then the last outbound HTTP request matches:
      | key                         | value   |
      | body.thinking.type          | enabled |
      | body.thinking.budget_tokens | 3200    |

  Scenario: Effort 5 maps to 50% of budget-max (16000 tokens)
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 5           |
    When the user sends "hi" on session "thinking"
    Then the last outbound HTTP request matches:
      | key                         | value   |
      | body.thinking.type          | enabled |
      | body.thinking.budget_tokens | 16000   |

  Scenario: Effort 10 maps to 100% of budget-max (32000 tokens)
    Given the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 10          |
    When the user sends "hi" on session "thinking"
    Then the last outbound HTTP request matches:
      | key                         | value   |
      | body.thinking.type          | enabled |
      | body.thinking.budget_tokens | 32000   |

  Scenario: thinking-budget-max 64000 scales 50% effort to 32000 tokens
    Given the isaac EDN file "config/models/claude.edn" exists with:
      | path                | value                     |
      | model               | claude-sonnet-4-5         |
      | provider            | grover:anthropic          |
      | context-window      | 200000                    |
      | thinking-budget-max | 64000                     |
    And the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 5           |
    When the user sends "hi" on session "thinking"
    Then the last outbound HTTP request matches:
      | key                         | value |
      | body.thinking.budget_tokens | 32000 |

  Scenario: thinking-budget-max 64000 scales effort 10 to 64000 tokens
    Given the isaac EDN file "config/models/claude.edn" exists with:
      | path                | value                     |
      | model               | claude-sonnet-4-5         |
      | provider            | grover:anthropic          |
      | context-window      | 200000                    |
      | thinking-budget-max | 64000                     |
    And the isaac EDN file "config/crew/thinker.edn" exists with:
      | path   | value       |
      | model  | claude      |
      | soul   | Think hard. |
      | effort | 10          |
    When the user sends "hi" on session "thinking"
    Then the last outbound HTTP request matches:
      | key                         | value |
      | body.thinking.budget_tokens | 64000 |
