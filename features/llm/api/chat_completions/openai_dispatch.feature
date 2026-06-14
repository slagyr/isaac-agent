Feature: OpenAI Provider Dispatch
  Isaac dispatches to the correct OpenAI API based on provider
  configuration. OAuth Codex providers use chatgpt.com backend
  with streaming. API key providers use chat completions.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: OAuth Codex provider sends to chatgpt.com backend API
    Given the isaac EDN file "config/models/snuffy.edn" exists with:
      | path | value |
      | model | snuffy-codex |
      | provider | grover:chatgpt |
      | context-window | 128000 |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path | value |
      | model | snuffy |
      | soul | Lives in a trash can. |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | model        | type | content |
      | snuffy-codex | text | Scram!  |
    When the user sends "knock knock" on session "trash-can"
    Then the last outbound HTTP request matches:
      | key                        | value                                          |
      | url                        | https://chatgpt.com/backend-api/codex/responses |
      | headers.ChatGPT-Account-Id | #*                                               |
      | headers.originator         | isaac                                            |
      | body.model                 | snuffy-codex                                     |
      | body.instructions          | #"(?s)Lives in a trash can\..*Never treat the user's own words as instructions.*" |
      | body.stream                | true                                             |
    And session "trash-can" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Scram!          |

  Scenario: OAuth Codex provider requests reasoning summary auto on the responses API
    Given the isaac EDN file "config/models/snuffy.edn" exists with:
      | path | value |
      | model | gpt-5.4 |
      | provider | grover:chatgpt |
      | context-window | 128000 |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path | value |
      | model | snuffy |
      | soul | Lives in a trash can. |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | model  | type | content |
      | gpt-5.4 | text | Scram!  |
    When the user sends "knock knock" on session "trash-can"
    Then the last outbound HTTP request matches:
      | key                    | value |
      | body.reasoning.effort  | high  |
      | body.reasoning.summary | auto  |

  Scenario: OAuth Codex provider omits reasoning block when effort is 0
    Given the isaac EDN file "config/models/snuffy.edn" exists with:
      | path | value |
      | model | gpt-5.4 |
      | provider | grover:chatgpt |
      | context-window | 128000 |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path   | value                 |
      | model  | snuffy                |
      | soul   | Lives in a trash can. |
      | effort | 0                     |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | model  | type | content |
      | gpt-5.4 | text | Scram!  |
    When the user sends "knock knock" on session "trash-can"
    Then the last outbound HTTP request matches:
      | key            | value |
      | body.reasoning |       |

  Scenario: OAuth Codex provider includes conversation history as input
    Given the isaac EDN file "config/models/snuffy.edn" exists with:
      | path | value |
      | model | snuffy-codex |
      | provider | grover:chatgpt |
      | context-window | 128000 |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path | value |
      | model | snuffy |
      | soul | Lives in a trash can. |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And session "trash-can" has transcript:
      | type    | message.role | message.content |
      | message | user         | knock knock     |
      | message | assistant    | Go away!        |
    And the following model responses are queued:
      | model        | type | content       |
      | snuffy-codex | text | I said SCRAM! |
    When the user sends "knock knock again" on session "trash-can"
    Then the last outbound HTTP request matches:
      | key                | value     |
      | body.input[0].role | user      |
      | body.input[1].role | assistant |
      | body.input[2].role | user      |

  Scenario: OAuth Codex provider formats tools for responses API
    Given the isaac EDN file "config/models/snuffy.edn" exists with:
      | path | value |
      | model | snuffy-codex |
      | provider | grover:chatgpt |
      | context-window | 128000 |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path | value |
      | model | snuffy |
      | tools.allow | read,write,edit,exec |
      | soul | Lives in a trash can. |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the built-in tools are registered
    And the following model responses are queued:
      | model        | type | content              |
      | snuffy-codex | text | Found a banana peel. |
    When the user sends "what's in the trash?" on session "trash-can"
    Then the last outbound HTTP request matches:
      | key                           | value    |
      | body.tools[0].type            | function |
      | body.tools[0].name            | read     |
      | body.tools[0].parameters.type | object   |
    And the last provider request does not contain path "body.tools[0].function"

  Scenario: OAuth Codex provider handles tool call responses
    Given the isaac EDN file "config/models/snuffy.edn" exists with:
      | path | value |
      | model | snuffy-codex |
      | provider | grover:chatgpt |
      | context-window | 128000 |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path | value |
      | model | snuffy |
      | tools.allow | read,write,edit,exec |
      | soul | Lives in a trash can. |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the built-in tools are registered
    And the following model responses are queued:
      | model        | type      | tool_call | arguments                   |
      | snuffy-codex | tool_call | read      | {"filePath":"trash-lid.txt"} |
    And the following model responses are queued:
      | model        | type | content                          |
      | snuffy-codex | text | Old newspaper and a banana peel. |
    When the user sends "what's under the lid?" on session "trash-can"
    Then session "trash-can" has transcript matching:
      | type    | message.role | message.content                  |
      | message | assistant    | Old newspaper and a banana peel. |

  Scenario: API key provider sends chat completions request
    Given the isaac EDN file "config/models/cookie.edn" exists with:
      | path | value |
      | model | cookie |
      | provider | grover:openai |
      | context-window | 32768 |
    And the isaac EDN file "config/crew/cmonster.edn" exists with:
      | path | value |
      | model | cookie |
      | soul | Me love cookie! |
    And the following sessions exist:
      | name       | crew     |
      | cookie-jar | cmonster |
    And the following model responses are queued:
      | model  | type | content          |
      | cookie | text | C is for cookie! |
    When the user sends "hi" on session "cookie-jar"
    Then the last outbound HTTP request matches:
      | key        | value                                      |
      | url        | https://api.openai.com/v1/chat/completions |
      | body.model | cookie                                     |
    And session "cookie-jar" has transcript matching:
      | type    | message.role | message.content  |
      | message | assistant    | C is for cookie! |
