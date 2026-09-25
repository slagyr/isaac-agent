Feature: A busy session queues its next messages; same-thread prompts consolidate
  A request for a session already in flight is not refused (isaac-xoqn). It waits,
  and when the running turn ends the waiting requests run next — those that
  share a :coalesce-key as ONE turn with their inputs joined in arrival
  order, the rest one turn each. A comm supplies the key (gchat: the thread).

  Background:
    Given default Grover setup

  @wip
  Scenario: a message that arrives while the session's turn runs waits and runs next (isaac-xoqn)
    Given the following sessions exist:
      | name | crew |
      | dm   | main |
    And the LLM response is delayed by 2 seconds
    And the following model responses are queued:
      | model | type | content        |
      | echo  | text | Answered one.  |
      | echo  | text | Answered two.  |
    When the user sends "one" on session "dm" without waiting via memory comm
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

  @wip
  Scenario: waiting messages with the same coalesce key run as one turn (isaac-xoqn)
    Given the following sessions exist:
      | name | crew |
      | dm   | main |
    And the LLM response is delayed by 2 seconds
    And the following model responses are queued:
      | model | type | content        |
      | echo  | text | Answered one.  |
      | echo  | text | Both answered. |
    When the user sends "one" on session "dm" without waiting via memory comm
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
