Feature: Error Entry Handling
  LLM errors must not poison the session transcript with invalid
  roles. Errors are stored as their own entry type, never as
  messages with role "error". The prompt builder excludes them.

  Background:
    Given default Grover setup

  Scenario: error response is stored as type error, not role error
    Given the following sessions exist:
      | name       |
      | error-test |
    And the following model responses are queued:
      | type  | content              | model |
      | error | something went wrong | echo  |
    When the user sends "hi" on session "error-test"
    Then session "error-test" has transcript matching:
      | type  |
      | error |
    And session "error-test" has no transcript entries with role "error"

  @wip
  Scenario: uncaught exception during a turn lands a closing error entry
    Given the following sessions exist:
      | name   |
      | crashy |
    And session "crashy" has transcript:
      | type    | message.role | message.content |
      | message | user         | earlier prompt  |
      | message | assistant    | earlier reply   |
    And the LLM throws an exception with message "wire format mismatch"
    When the user sends "next thing" on session "crashy"
    Then session "crashy" has transcript matching:
      | type    | message.role | message.content | content              |
      | message | user         | earlier prompt  |                      |
      | message | assistant    | earlier reply   |                      |
      | message | user         | next thing      |                      |
      | error   |              |                 | wire format mismatch |
    And the log has entries matching:
      | level  | event                | session |
      | :error | :session/turn-failed | crashy  |

  Scenario: error entries are excluded from the prompt
    Given the following sessions exist:
      | name       |
      | error-test |
    And session "error-test" has transcript:
      | type    | message.role | message.content |
      | message | user         | hi              |
    And session "error-test" has an error entry "something went wrong"
    And the following model responses are queued:
      | type | content     | model |
      | text | I recovered | echo  |
    When the prompt for session "error-test" is built for provider "openai"
    Then the prompt messages do not contain role "error"

  Scenario: an empty terminal response gets one continuation nudge and recovers (isaac-k4mf)
    A terminal model response with empty content and no error is a suspicious
    shape for any turn. The runtime retries exactly once with a continuation
    nudge; a non-empty retry completes the turn normally.
    Given the following sessions exist:
      | name       |
      | quiet-turn |
    And the following model responses are queued:
      | type | content | model |
      | text |         | echo  |
      | text | done.   | echo  |
    When the user sends "status?" on session "quiet-turn"
    Then session "quiet-turn" has transcript matching:
      | message.role | message.content |
      | assistant    | done.           |
    And the last LLM request matches:
      | key                  | value               |
      | messages[-1].content | #"(?s).*continue.*" |

  Scenario: two empty responses fail the turn explicitly (isaac-k4mf)
    Retry budget is exactly one. A second empty response fails the turn with
    an explicit error entry — never a silent normal completion. Boundedness
    is enforced by the queue: a third request would exhaust it loudly.
    Given the following sessions exist:
      | name      |
      | dead-turn |
    And the following model responses are queued:
      | type | content | model |
      | text |         | echo  |
      | text |         | echo  |
    When the user sends "status?" on session "dead-turn"
    Then session "dead-turn" has transcript matching:
      | type  | content                        |
      | error | #".*empty-terminal-response.*" |

  Scenario: empty terminal response after tool execution fails the same way (isaac-k4mf)
    The observed zanebot shape: tools ran, then the model went silent. Same
    guard, same explicit failure.
    Given the following sessions exist:
      | name      |
      | dead-tool |
    And the isaac file "target/test-state/hello.txt" exists with:
      """
      hi
      """
    And the following model responses are queued:
      | model | tool_call | arguments                                     |
      | echo  | read      | {"file_path": "target/test-state/hello.txt"} |
      | model | type      | content |
      | echo  | text      |         |
      | model | type      | content |
      | echo  | text      |         |
    When the user sends "read it" on session "dead-tool"
    Then session "dead-tool" has transcript matching:
      | type  | content                        |
      | error | #".*empty-terminal-response.*" |
