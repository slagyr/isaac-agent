Feature: The provider response schema (isaac-g71i)

  The schema in isaac.llm.api.protocol is the seam between the drive and the
  provider adapters: it defines everything the drive needs from a model
  response, and everything an adapter must provide. Adapters translate their
  own wire format into it — the drive reads only schema fields, never
  snake_case wire keys and never by guessing between nesting depths.

  Token accounting is the adapter's job, because only the adapter knows its
  own API's convention: :usage :prompt-tokens is the whole context the model
  processed, cached portions included. OpenAI's input_tokens already counts
  cached tokens; Anthropic's does not and must have cache read and cache
  write added back. Cache and reasoning counts ride along for cost and stats
  only, never for context size.

  Scenario 1 exercises every built-in adapter against one reply; the rest pin
  the fields the drive depends on.

  Scenario Outline: <provider> returns its reply in the drive's response schema
    Given default Grover setup
    And the isaac EDN file "config/models/harbor.edn" exists with:
      | path           | value      |
      | model          | harbor-1   |
      | provider       | <provider> |
      | context-window | 400000     |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | harbor           |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name    |
      | on-deck |
    And the following model responses are queued:
      | type | content        | usage.input_tokens | usage.output_tokens |
      | text | Sails trimmed. | 100                | 25                  |
    When the user sends "trim the sails" on session "on-deck"
    Then the last provider response matches:
      | key                 | value          |
      | content             | Sails trimmed. |
      | tool-calls          | []             |
      | stop-reason         | :end-turn      |
      | model               | #*             |
      | usage.prompt-tokens | 100            |
      | usage.output-tokens | 25             |

    Examples:
      | provider         |
      | grover:anthropic |
      | grover:openai    |
      | grover:chatgpt   |
      | grover:ollama    |

  Scenario: an off-contract adapter return fails the turn as a provider contract error
    Given default Grover setup
    And the following sessions exist:
      | name    |
      | on-deck |
    And grover returns this raw response:
      """
      {:content 42 :tool-calls [] :model "echo" :usage {:output-tokens 3}}
      """
    When the user sends "trim the sails" on session "on-deck"
    Then session "on-deck" has transcript matching:
      | #index | type  | error              | content                                                    |
      | -1     | error | :provider-contract | #"(?s)(?=.*content)(?=.*stop-reason)(?=.*prompt-tokens).*" |
    And the log has entries matching:
      | level  | event                            | provider |
      | :error | :chat/provider-contract-violated | grover   |

  Scenario: a valid adapter return reaches the drive with nothing dropped
    Given default Grover setup
    And the following sessions exist:
      | name    |
      | on-deck |
    And grover returns this raw response:
      """
      {:content     "Sails trimmed."
       :tool-calls  []
       :stop-reason :end-turn
       :model       "echo"
       :usage       {:prompt-tokens 100 :output-tokens 25}
       :harbor-log  ["luffing" "close-hauled"]}
      """
    When the user sends "trim the sails" on session "on-deck"
    Then the last provider response matches:
      | key           | value          |
      | content       | Sails trimmed. |
      | harbor-log[1] | close-hauled   |
    And session "on-deck" has transcript matching:
      | #index | type    | message.role | message.content |
      | -1     | message | assistant    | Sails trimmed.  |

  Scenario: a reasoning block with no summary rides along and the turn stays clean (isaac-ddls)
    Given default Grover setup
    And the following sessions exist:
      | name    |
      | on-deck |
    And grover returns this raw response:
      """
      {:content     "Sails trimmed."
       :tool-calls  []
       :stop-reason :end-turn
       :model       "echo"
       :usage       {:prompt-tokens 100 :output-tokens 25}
       :reasoning   {:summary ""}}
      """
    When the user sends "trim the sails" on session "on-deck"
    Then session "on-deck" has transcript matching:
      | #index | type    | message.role | message.content |
      | -1     | message | assistant    | Sails trimmed.  |
    And the log has no entries matching:
      | event                            |
      | :chat/provider-contract-violated |

  Scenario: OpenAI-style usage stamps the context size without adding cached tokens
    Given default Grover setup
    And the isaac EDN file "config/models/snuffy.edn" exists with:
      | path           | value          |
      | model          | snuffy-codex   |
      | provider       | grover:chatgpt |
      | context-window | 400000         |
    And the isaac EDN file "config/models/grouch.edn" exists with:
      | path           | value         |
      | model          | grouch-4      |
      | provider       | grover:openai |
      | context-window | 400000        |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value  |
      | model | snuffy |
    And the isaac EDN file "config/crew/bert.edn" exists with:
      | path  | value  |
      | model | grouch |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
      | paperclip | bert  |
    And the following model responses are queued:
      | model        | type | content  | usage.input_tokens | usage.input_tokens_details.cached_tokens | usage.prompt_tokens | usage.prompt_tokens_details.cached_tokens | usage.output_tokens | usage.completion_tokens |
      | snuffy-codex | text | Scram.   | 1000               | 900                                      |                     |                                           | 40                  |                         |
      | grouch-4     | text | Go away. |                    |                                          | 1000                | 900                                       |                     | 40                      |
    When the user sends "knock knock" on session "trash-can"
    And the user sends "knock knock" on session "paperclip"
    Then the following sessions match:
      | name      | last-input-tokens |
      | trash-can | 1000              |
      | paperclip | 1000              |
    And the last provider response matches:
      | key                     | value |
      | usage.prompt-tokens     | 1000  |
      | usage.cache-read-tokens | 900   |

  Scenario: Anthropic-style usage stamps input plus cache read plus cache write as the context size
    Given default Grover setup
    And the isaac EDN file "config/models/tinfoil.edn" exists with:
      | path           | value            |
      | model          | tinfoil-sonnet   |
      | provider       | grover:anthropic |
      | context-window | 400000           |
    And the isaac EDN file "config/crew/bert.edn" exists with:
      | path  | value   |
      | model | tinfoil |
    And the following sessions exist:
      | name      | crew |
      | paperclip | bert |
    And the following model responses are queued:
      | model          | type | content  | usage.input_tokens | usage.cache_read_input_tokens | usage.cache_creation_input_tokens | usage.output_tokens |
      | tinfoil-sonnet | text | Go away. | 100                | 800                           | 100                               | 40                  |
    When the user sends "knock knock" on session "paperclip"
    Then the following sessions match:
      | name      | last-input-tokens |
      | paperclip | 1000              |
    And the last provider response matches:
      | key                      | value |
      | usage.prompt-tokens      | 1000  |
      | usage.cache-read-tokens  | 800   |
      | usage.cache-write-tokens | 100   |

  Scenario: a tool turn sums usage for stats but stamps context size from its last request
    Given default Grover setup
    And the built-in tools are registered
    And the isaac EDN file "config/models/tinfoil.edn" exists with:
      | path           | value            |
      | model          | tinfoil-sonnet   |
      | provider       | grover:anthropic |
      | context-window | 400000           |
    And the isaac EDN file "config/crew/bert.edn" exists with:
      | path  | value   |
      | model | tinfoil |
    And the crew "bert" allows tools: "fs/grep"
    And the following sessions exist:
      | name      | crew |
      | paperclip | bert |
    And the following model responses are queued:
      | model          | type      | content          | tool_call | arguments | usage.input_tokens | usage.cache_read_input_tokens | usage.cache_creation_input_tokens | usage.output_tokens |
      | tinfoil-sonnet | tool_call |                  | fs__grep  | {}        | 100                | 800                           | 100                               | 20                  |
      | tinfoil-sonnet | text      | Found the ducky. |           |           | 50                 | 1000                          | 0                                 | 30                  |
    When the user sends "where is my rubber ducky?" on session "paperclip"
    Then the following sessions match:
      | name      | last-input-tokens | turn-input-tokens | output-tokens |
      | paperclip | 1050              | 2050              | 50            |

  Scenario Outline: <provider> maps wire stop <wire> to <expected>
    Given default Grover setup
    And the isaac EDN file "config/models/harbor.edn" exists with:
      | path     | value      |
      | model    | harbor-1   |
      | provider | <provider> |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | harbor           |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name    |
      | on-deck |
    And the following model responses are queued:
      | type | content  |
      | text | Aye aye. |
    And grover's next reply stops with wire reason "<wire>"
    When the user sends "trim the sails" on session "on-deck"
    Then the last provider response matches:
      | key         | value      |
      | stop-reason | <expected> |

    Examples:
      | provider         | wire                         | expected    |
      | grover:anthropic | end_turn                     | :end-turn   |
      | grover:anthropic | max_tokens                   | :max-tokens |
      | grover:anthropic | refusal                      | :refused    |
      | grover:anthropic | pause_turn                   | :other      |
      | grover:openai    | stop                         | :end-turn   |
      | grover:openai    | length                       | :max-tokens |
      | grover:openai    | content_filter               | :refused    |
      | grover:chatgpt   | completed                    | :end-turn   |
      | grover:chatgpt   | incomplete:max_output_tokens | :max-tokens |
      | grover:chatgpt   | incomplete:content_filter    | :refused    |
      | grover:ollama    | stop                         | :end-turn   |
      | grover:ollama    | length                       | :max-tokens |

  Scenario: a tool call with unparseable arguments comes back to the model as its tool result
    Given default Grover setup
    And the built-in tools are registered
    And the isaac EDN file "config/models/snuffy.edn" exists with:
      | path           | value          |
      | model          | snuffy-codex   |
      | provider       | grover:chatgpt |
      | context-window | 400000         |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value  |
      | model | snuffy |
    And the crew "oscar" allows tools: "fs/grep"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And grover's next reply calls "fs__grep" with raw arguments:
      """
      {"pattern": "ducky", "path":
      """
    And the following model responses are queued:
      | model        | type | content                |
      | snuffy-codex | text | I will try that again. |
    When the user sends "find my rubber ducky" on session "trash-can"
    Then session "trash-can" has transcript matching:
      | #index | type       | message.role | name     | message.content                        |
      | -3     | message    | assistant    | fs__grep |                                        |
      | -2     | toolResult |              |          | #"(?s)(?=.*arguments)(?=.*fs__grep).*" |
      | -1     | message    | assistant    |          | I will try that again.                 |

  Scenario: a rate-limited provider reports its own retry-after on the error
    Given default Grover setup
    And the isaac EDN file "config/models/snuffy.edn" exists with:
      | path     | value          |
      | model    | snuffy-codex   |
      | provider | grover:chatgpt |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value  |
      | model | snuffy |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | model        | type       | status | retry-after |
      | snuffy-codex | http-error | 429    | 60          |
    When the user sends "knock knock" on session "trash-can"
    Then the last provider response matches:
      | key            | value         |
      | error          | :rate-limited |
      | retry-after-ms | 60000         |
    And the turn result is unavailable with retry-after-ms 60000 and reason wall

  Scenario: a context-overflow error compacts and retries without matching error text
    Given default Grover setup
    And the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value  |
      | model          | echo   |
      | provider       | grover |
      | context-window | 200    |
    And the following sessions exist:
      | name     | last-input-tokens |
      | longhaul | 100               |
    And the following model responses are queued:
      | type  | content                 | model |
      | error | context length exceeded | echo  |
      | text  | Compacted conversation. | echo  |
      | text  | Sails trimmed.          | echo  |
    When the user sends "trim the sails" on session "longhaul"
    Then the last provider response matches:
      | key     | value          |
      | content | Sails trimmed. |
    And session "longhaul" has compaction
    And session "longhaul" has transcript matching:
      | #index | type    | message.role | message.content |
      | -1     | message | assistant    | Sails trimmed.  |

  Scenario Outline: <provider> streams text deltas to the comm
    Given default Grover setup
    And the isaac EDN file "config/models/harbor.edn" exists with:
      | path     | value      |
      | model    | harbor-1   |
      | provider | <provider> |
    And the provider "<provider>" is configured with:
      | key                   | value |
      | stream-non-tool-turns | true  |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | harbor           |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name    |
      | on-deck |
    And the following model responses are queued:
      | type | content                          |
      | text | ["Sails " "trimmed " "at last."] |
    When the user sends "trim the sails" on session "on-deck" via memory comm
    Then the memory comm has events matching:
      | event      | text                   |
      | turn-start |                        |
      | chatter    | #"Sails\s"            |
      | chatter    | #"trimmed\s"          |
      | chatter    | at last.               |
      | reply      | Sails trimmed at last. |
      | turn-end   |                        |

    Examples:
      | provider         |
      | grover:anthropic |
      | grover:openai    |
      | grover:chatgpt   |
      | grover:ollama    |

  Scenario: reasoning streams to the comm and the transcript records the requested effort
    Given default Grover setup
    And the following sessions exist:
      | name              |
      | reasoning-persist |
    And the following model responses are queued:
      | type | content | model | reasoning.effort | reasoning.summary   |
      | text | done    | echo  | high             | Checked the rigging |
    When the user sends "think" on session "reasoning-persist" via memory comm
    Then the memory comm has events matching:
      | event     | text                |
      | reckoning | Checked the rigging |
    And session "reasoning-persist" has transcript matching:
      | type    | message.role | message.reasoning.summary | message.reasoning.effort |
      | message | assistant    | Checked the rigging       | 7                        |
