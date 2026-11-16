@wip
Feature: Subagent delegation guidance
  A crew whose turn has agent__spawn gets standing guidance in its system
  prompt on when and how to delegate. The gate is the turn's effective tool
  list, not crew config, so a subagent child never gets it. (isaac-fnyp)

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name   | crew |
      | bridge | main |
    And the current session is "bridge"

  Scenario: a crew that can spawn subagents is told when and how to delegate (isaac-fnyp)
    Given the crew "main" allows tools: "agent/spawn,fs/read"
    And the following model responses are queued:
      | type | content | model |
      | text | Aye.    | echo  |
    When the user sends "Survey the harbor." on session "bridge"
    Then the system prompt contains "Delegating to subagents:"

  Scenario: a crew without agent__spawn gets no delegation guidance (isaac-fnyp)
    Given the crew "main" allows tools: "fs/read"
    And the following model responses are queued:
      | type | content | model |
      | text | Aye.    | echo  |
    When the user sends "Survey the harbor." on session "bridge"
    Then the system prompt does not contain "Delegating to subagents:"

  Scenario: a subagent child gets no delegation guidance, even when its crew allows agent__spawn (isaac-fnyp)
    Given the crew "main" allows tools: "agent/spawn,fs/read"
    And the following model responses are queued:
      | type | content         | model |
      | text | Three moorings. | echo  |
    When the tool "agent__spawn" is called with:
      | prompt | Count the moorings. |
    And the turn queue ticks at "2026-03-01T18:00:00"
    Then the following sessions match:
      | parent | crew |
      | bridge | main |
    And the last LLM request matches:
      | key                 | value                      |
      | messages[0].content | #"(?s).*Tool discipline.*" |
    And the system prompt does not contain "Delegating to subagents:"
