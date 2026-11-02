Feature: Input that arrives during a running turn joins it; in queue mode it waits
  A session runs one turn at a time, but a request for a busy session is not
  refused (isaac-xoqn). What happens to it depends on the crew's
  :mid-turn-input (isaac-cgz9):

  :join (default) — the running turn takes it in at its next cycle boundary,
  as a user message labelled as arriving mid-turn. Input that lands during the
  call that would end the turn gets one more cycle. The request is marked
  absorbed and never runs as its own turn. A cancelled turn absorbs nothing.

  :queue — it waits, and when the running turn ends the waiting requests run
  next: those that share a :coalesce-key as ONE turn with their inputs joined
  in arrival order, the rest one turn each. A comm supplies the key (gchat:
  the thread).

  Either way, on an idle session subagent results run after all other
  waiting input.

  Background:
    Given default Grover setup

  Scenario: in queue mode a message that arrives while the turn runs waits and runs next (isaac-xoqn, isaac-cgz9)
    Given config:
      | key                          | value  |
      | defaults.crew.mid-turn-input | :queue |
    And the following sessions exist:
      | name | crew |
      | dm   | main |
    And the LLM response is delayed by 2 seconds
    And the following model responses are queued:
      | model | type | content        |
      | echo  | text | Answered one.  |
      | echo  | text | Answered two.  |
    When the user sends "one" on session "dm" without waiting via memory comm
    And the delayed response starts on session "dm"
    And the user sends "two" on session "dm" without waiting via memory comm
    And the turns on session "dm" finish
    Then session "dm" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | one             |
      | message | assistant    | Answered one.   |
      | message | user         | two             |
      | message | assistant    | Answered two.   |
    And the log has entries matching:
      | level | event         | session |
      | :info | :turn/waiting | dm      |

  Scenario: in queue mode waiting messages with the same coalesce key run as one turn (isaac-xoqn, isaac-cgz9)
    Given config:
      | key                          | value  |
      | defaults.crew.mid-turn-input | :queue |
    And the following sessions exist:
      | name | crew |
      | dm   | main |
    And the LLM response is delayed by 2 seconds
    And the following model responses are queued:
      | model | type | content        |
      | echo  | text | Answered one.  |
      | echo  | text | Both answered. |
    When the user sends "one" on session "dm" without waiting via memory comm
    And the delayed response starts on session "dm"
    And the user sends "two" on session "dm" with coalesce key "t1" without waiting via memory comm
    And the user sends "three" on session "dm" with coalesce key "t1" without waiting via memory comm
    And the turns on session "dm" finish
    Then session "dm" has transcript matching:
      | type    | message.role | message.content       |
      | message | user         | one                   |
      | message | assistant    | Answered one.         |
      | message | user         | #"(?s)two\ntrhee|two\nthree" |
      | message | assistant    | Both answered.        |
    And the log has entries matching:
      | level | event           | session | key | count |
      | :info | :turn/coalesced | dm      | t1  | 2     |

  Scenario: a session's transcript check does not block on a different session's still-parked turn (isaac-n8uv)
    Given the following sessions exist:
      | name | crew |
      | dm   | main |
      | dm2  | main |
    And the following model responses are queued:
      | model | type | content       | wait |
      | echo  | text | First reply.  | true |
      | echo  | text | Second reply. | true |
    When the user sends "one" on session "dm" without waiting via memory comm
    And the user sends "two" on session "dm2" without waiting via memory comm
    And the turn ends on session "dm2"
    Then session "dm2" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | two             |
      | message | assistant    | Second reply.   |
    And session "dm" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | one             |

  Scenario: a message that arrives between tool cycles joins the running turn (isaac-cgz9)
    Given the built-in tools are registered
    And the crew "main" allows tools: "exec/run"
    And the following sessions exist:
      | name | crew |
      | dm   | main |
    And the following model responses are queued:
      | type      | content           | model | tool_call | arguments                       | wait |
      | tool_call |                   |       | exec__run | {"command": "echo hull primed"} | true |
      | text      | Painting it blue. | echo  |           |                                 |      |
    When the user sends "paint the hull red" on session "dm" without waiting via memory comm
    Then within 5 seconds session "dm" is waiting on the model
    When the user sends "wait no, blue" on session "dm" without waiting via memory comm
    And the model releases session "dm"
    And the turns on session "dm" finish
    Then session "dm" has transcript matching:
      | #index | type    | message.role | message.content    |
      | -3     | message | toolResult   | hull primed        |
      | -2     | message | user         | wait no, blue      |
      | -1     | message | assistant    | Painting it blue.  |
    And the tool loop request contains messages with:
      | role | content                                            | #comment                   |
      | user | #"(?s).*arrived during this turn.*wait no, blue.*" | labelled as mid-turn input |
    And the latest turn on session "dm" matches:
      | key   | value         |
      | input | wait no, blue |
      | state | absorbed      |
    And the log has entries matching:
      | level | event          | session | count |
      | :info | :turn/absorbed | dm      | 1     |

  Scenario: a message that arrives during the final model call gets one more cycle (isaac-cgz9)
    Given the following sessions exist:
      | name | crew |
      | dm   | main |
    And the following model responses are queued:
      | type | content     | model | wait |
      | text | Red it is.  | echo  | true |
      | text | Blue, then. | echo  |      |
    When the user sends "red" on session "dm" without waiting via memory comm
    Then within 5 seconds session "dm" is waiting on the model
    When the user sends "wait no, blue" on session "dm" without waiting via memory comm
    And the model releases session "dm"
    And the turns on session "dm" finish
    Then session "dm" has transcript matching:
      | #index | type    | message.role | message.content |
      | -4     | message | user         | red             |
      | -3     | message | assistant    | Red it is.      |
      | -2     | message | user         | wait no, blue   |
      | -1     | message | assistant    | Blue, then.     |
    And the latest turn on session "dm" matches:
      | key   | value    |
      | state | absorbed |

  Scenario: on an idle session a waiting subagent result runs after other waiting input (isaac-cgz9)
    Given the following sessions exist:
      | name   |
      | harbor |
    And the following model responses are queued:
      | type | content            | model |
      | text | Aye, Cordelia.     | echo  |
      | text | The scout reports. | echo  |
    When a turn is submitted with:
      | key         | value                       |
      | id          | scout-1                     |
      | input       | Scout found three moorings. |
      | session     | harbor                      |
      | origin.kind | subagent                    |
    And a turn is submitted with:
      | key       | value          |
      | id        | cordelia-1     |
      | input     | Status report? |
      | session   | harbor         |
      | from.kind | handle         |
      | from.comm | logbook        |
      | from.id   | cordelia       |
    And the turn queue ticks at "2026-03-01T18:00:00"
    Then session "harbor" has transcript matching:
      | #index | type    | message.role | message.content             |
      | 1      | message | user         | Status report?              |
      | 2      | message | assistant    | Aye, Cordelia.              |
      | 3      | message | user         | Scout found three moorings. |
      | 4      | message | assistant    | The scout reports.          |

  Scenario: a cancelled turn absorbs nothing; the waiting message runs as its own turn (isaac-cgz9)
    Given the following sessions exist:
      | name | crew |
      | dm   | main |
    And the following model responses are queued:
      | type | content     | model | wait |
      | text | working     | echo  | true |
      | text | Blue noted. | echo  |      |
    When the user sends "paint the hull red" on session "dm" without waiting via memory comm
    Then within 5 seconds session "dm" is waiting on the model
    When the user sends "wait no, blue" on session "dm" without waiting via memory comm
    And the turn is cancelled on session "dm"
    And the turns on session "dm" finish
    Then session "dm" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | wait no, blue   |
      | message | assistant    | Blue noted.     |
    And the latest turn on session "dm" matches:
      | key   | value    |
      | state | finished |
