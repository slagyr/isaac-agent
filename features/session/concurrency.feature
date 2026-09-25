Feature: Sessions run in parallel; a session runs one turn at a time
  A turn is serialized per session — two prompts in one session never run at
  once — but two sessions run their turns at the same time. There is no
  crew-wide cap (isaac-ximd): the old :max-in-flight defaulted to 1 and serialized
  every session a crew owned.

  Background:
    Given default Grover setup
    And config:
      | key                       | value                |
      | defaults.crew.tools.allow | [:all :test :test/*] |
    And the built-in tools are registered

  Scenario: two sessions on one crew run their turns at the same time — no crew-wide cap (isaac-ximd)
    Given a rendezvous tool "test__handshake" is registered that returns "met" once 2 calls are in flight
    And the following sessions exist:
      | name      | crew |
      | port      | main |
      | starboard | main |
    And the following model responses are queued:
      | model | type       | tool_calls                                          | content |
      |       | tool_calls | [{"function":{"name":"test__handshake","arguments":{}}}] |         |
      |       | tool_calls | [{"function":{"name":"test__handshake","arguments":{}}}] |         |
      | echo  | text       |                                                     | Met.    |
      | echo  | text       |                                                     | Met.    |
    When the user sends "shake on it" on sessions "port" and "starboard" at the same time via memory comm
    Then session "port" has transcript matching:
      | type    | message.role | message.content |
      | message | toolResult   | met             |
      | message | assistant    | Met.            |
    And session "starboard" has transcript matching:
      | type    | message.role | message.content |
      | message | toolResult   | met             |
      | message | assistant    | Met.            |
