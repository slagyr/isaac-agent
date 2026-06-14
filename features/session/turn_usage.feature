Feature: Per-turn usage on assistant entries
  Assistant transcript entries carry one consistent per-turn usage block
  regardless of provider wire format or tool-loop iterations.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name       |
      | usage-turn |

  Scenario: Normalizes input, output, and total tokens
    Given the following model responses are queued:
      | type | content | model | usage.input_tokens | usage.output_tokens |
      | text | Hello   | echo  | 100                | 25                  |
    When the user sends "hi" on session "usage-turn"
    Then session "usage-turn" has transcript matching:
      | #index | type    | message.role | message.usage.input-tokens | message.usage.output-tokens | message.usage.total-tokens |
      | -1     | message | assistant    | 100                        | 25                          | 125                        |

  Scenario: Normalizes cache-read from cached tokens
    Given the following model responses are queued:
      | type | content | model | usage.input_tokens | usage.output_tokens | usage.input_tokens_details.cached_tokens |
      | text | Hello   | echo  | 100                | 25                  | 7                                        |
    When the user sends "hi" on session "usage-turn"
    Then session "usage-turn" has transcript matching:
      | #index | type    | message.role | message.usage.cache-read |
      | -1     | message | assistant    | 7                        |

  Scenario: Normalizes cache-write from cache creation tokens
    Given the following model responses are queued:
      | type | content | model | usage.input_tokens | usage.output_tokens | usage.cache_creation_input_tokens |
      | text | Hello   | echo  | 100                | 25                  | 3                                 |
    When the user sends "hi" on session "usage-turn"
    Then session "usage-turn" has transcript matching:
      | #index | type    | message.role | message.usage.cache-write |
      | -1     | message | assistant    | 3                         |

  Scenario: Preserves reasoning tokens on the normalized usage block
    Given the following model responses are queued:
      | type | content | model | usage.input_tokens | usage.output_tokens | usage.output_tokens_details.reasoning_tokens |
      | text | Hello   | echo  | 100                | 25                  | 11                                           |
    When the user sends "hi" on session "usage-turn"
    Then session "usage-turn" has transcript matching:
      | #index | type    | message.role | message.usage.reasoning-tokens |
      | -1     | message | assistant    | 11                             |

  Scenario: Writes usage when the provider omits the usage block entirely
    Given the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When the user sends "hi" on session "usage-turn"
    Then session "usage-turn" has transcript matching:
      | #index | type    | message.role | message.usage.input-tokens | message.usage.output-tokens | message.usage.total-tokens | message.usage.cache-read | message.usage.cache-write |
      | -1     | message | assistant    | 25                         | 12                          | 37                        | 0                        | 0                         |

  Scenario: Uses accumulated tool-loop token counts for the final assistant entry
    Given the built-in tools are registered
    And the crew "main" allows tools: grep
    And the following model responses are queued:
      | type      | content | tool_call | arguments | model | usage.input_tokens | usage.output_tokens | usage.input_tokens_details.cached_tokens | usage.cache_creation_input_tokens |
      | tool_call |         | grep      | {}        | echo  | 10                 | 5                   | 7                                        | 11                                |
      | text      | Done    |           |           | echo  | 4                  | 1                   | 2                                        | 3                                 |
    When the user sends "hi" on session "usage-turn"
    Then session "usage-turn" has transcript matching:
      | #index | type    | message.role | message.content | message.usage.input-tokens | message.usage.output-tokens | message.usage.total-tokens | message.usage.cache-read | message.usage.cache-write |
      | -1     | message | assistant    | Done            | 14                         | 6                          | 20                        | 9                        | 14                        |
