@wip
Feature: Parallel tool batches — a response's tool calls execute concurrently
  A batch of tool calls in one provider response is a set of independent
  calls: the model gets every result back together and cannot see one before
  issuing the next. Isaac runs the batch concurrently, bounded by
  tools.max-parallel, and hands the results back in the order the model
  issued them. The test-double tools here block on conditions, never clocks:
  their one-second ceilings run only when the implementation is already wrong.

  Background:
    Given default Grover setup
    And the built-in tools are registered

  Scenario: two calls in one batch overlap — a rendezvous serial execution could never satisfy
    Given a rendezvous tool "test__handshake" is registered that returns "met" once 2 calls are in flight
    And the following sessions exist:
      | name    |
      | on-deck |
    And the following model responses are queued:
      | model | type       | tool_calls                                                                                                          | content   |
      |       | tool_calls | [{"function":{"name":"test__handshake","arguments":{}}},{"function":{"name":"test__handshake","arguments":{}}}] |           |
      | echo  | text       |                                                                                                                     | Both met. |
    When the user sends "shake on it" on session "on-deck" via memory comm
    Then the memory comm has events matching:
      | event       | tool-name       |
      | tool-call   | test__handshake |
      | tool-call   | test__handshake |
      | tool-result | test__handshake |
      | tool-result | test__handshake |
      | reply       |                 |
    And session "on-deck" has transcript matching:
      | type    | message.role | message.content[0].text |
      | message | toolResult   | met                     |
      | message | toolResult   | met                     |

  Scenario: results go back to the model in batch order even when the later call finishes first
    Given a gated tool "test__slow" is registered that returns "slow done" once tool "test__quick" has completed
    And a streaming tool "test__quick" is registered that emits progress [] and returns "quick done"
    And the following sessions exist:
      | name    |
      | on-deck |
    And the following model responses are queued:
      | model | type       | tool_calls                                                                                              | content |
      |       | tool_calls | [{"function":{"name":"test__slow","arguments":{}}},{"function":{"name":"test__quick","arguments":{}}}] |         |
      | echo  | text       |                                                                                                         | Noted.  |
    When the user sends "go" on session "on-deck" via memory comm
    Then the memory comm has events matching:
      | event       | tool-name   |
      | tool-result | test__quick |
      | tool-result | test__slow  |
    And the last LLM request matches:
      | path                | value      |
      | messages[3].content | slow done  |
      | messages[4].content | quick done |

  Scenario: the transcript records every call before any result, and results pair with calls by id
    Given a gated tool "test__slow" is registered that returns "slow done" once tool "test__quick" has completed
    And a streaming tool "test__quick" is registered that emits progress [] and returns "quick done"
    And the following sessions exist:
      | name    |
      | on-deck |
    And the following model responses are queued:
      | model | type       | tool_calls                                                                                              | content |
      |       | tool_calls | [{"function":{"name":"test__slow","arguments":{}}},{"function":{"name":"test__quick","arguments":{}}}] |         |
      | echo  | text       |                                                                                                         | Noted.  |
    When the user sends "go" on session "on-deck" via memory comm
    Then session "on-deck" has transcript matching:
      | type    | message.role | message.content[0].name | message.content[0].text |
      | message | assistant    | test__slow              |                         |
      | message | assistant    | test__quick             |                         |
      | message | toolResult   |                         | quick done              |
      | message | toolResult   |                         | slow done               |
      | message | assistant    |                         | Noted.                  |
    And every toolResult in session "on-deck" pairs with a toolCall by id

  Scenario: tools.max-parallel is a config knob with a default of 4
    Given an Isaac root at "target/test-state"
    When isaac is run with "config get tools.max-parallel"
    Then the stdout contains "4"

  Scenario: cancel mid-batch — in-flight calls stop, queued calls never run, both report cancelled
    Given config:
      | key                | value |
      | tools.max-parallel | 1     |
    And a blocking tool "test__anchor" is registered that returns cancelled once the turn is cancelled
    And a streaming tool "test__quick" is registered that emits progress [] and returns "never ran"
    And the following sessions exist:
      | name    |
      | on-deck |
    And the following model responses are queued:
      | model | type       | tool_calls                                                                                                | content |
      |       | tool_calls | [{"function":{"name":"test__anchor","arguments":{}}},{"function":{"name":"test__quick","arguments":{}}}] |         |
      | echo  | text       |                                                                                                           | never   |
    When the user sends "drop anchor" on session "on-deck"
    And the turn is cancelled on session "on-deck" after 2 tool calls
    Then the turn result is "cancelled"
    And the memory comm has events matching:
      | event       | tool-name    |
      | tool-call   | test__anchor |
      | tool-cancel | test__anchor |
    And the memory comm has events matching:
      | event       | tool-name   |
      | tool-call   | test__quick |
      | tool-cancel | test__quick |
    And session "on-deck" has transcript not matching:
      | type    | message.role | message.content[0].text |
      | message | toolResult   | never ran               |
      | message | assistant    | never                   |

  Scenario: one call fails and the other succeeds — each result is its own, the cycle completes
    Given a streaming tool "test__quick" is registered that emits progress [] and returns "quick done"
    And the following sessions exist:
      | name    |
      | on-deck |
    And the following model responses are queued:
      | model | type       | tool_calls                                                                                                                       | content |
      |       | tool_calls | [{"function":{"name":"fs__read","arguments":{"filePath":"charts/missing.txt"}}},{"function":{"name":"test__quick","arguments":{}}}] |         |
      | echo  | text       |                                                                                                                                  | Noted.  |
    When the user sends "go" on session "on-deck" via memory comm
    Then the memory comm has events matching:
      | event       | tool-name   |
      | tool-result | fs__read    |
      | tool-result | test__quick |
      | reply       |             |
    And session "on-deck" has transcript matching:
      | type    | message.role | message.content[0].text             |
      | message | toolResult   | #"not found: .*charts/missing\.txt" |
      | message | toolResult   | quick done                          |
      | message | assistant    | Noted.                              |
    And the last LLM request matches:
      | path                | value                               |
      | messages[3].content | #"not found: .*charts/missing\.txt" |
      | messages[4].content | quick done                          |
