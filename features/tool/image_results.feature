Feature: fs__read images reach every provider as images (isaac-73vs)
  isaac-m4o2 made fs__read return images and handed them to Claude Code as
  MCP image content. Providers that build their own requests still got the
  raw image map in the tool result. Now each provider carries the image in
  its own wire shape for the rest of the turn that read it; the transcript
  keeps only the short note, so later turns and replays see the note.

  - Anthropic Messages: image blocks inside the tool_result content.
  - OpenAI Responses: function_call_output with an input_image item.
  - Chat Completions: tool messages are text-only, so the tool message
    carries the note and a user message right after it carries the image.
  - Ollama: same split, the image in the follow-up message's "images".
  - A model configured with :vision false gets only the note.

  New step: an image file "<name>" exists in the session working directory
  (binary-safe, a valid 1x1 PNG).

  Background:
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the built-in tools are registered

  @wip
  Scenario: Anthropic Messages carries the image inside the tool_result
    Given the isaac EDN file "config/models/lens.edn" exists with:
      | path           | value     |
      | model          | claude-lens |
      | provider       | grover:anthropic |
      | context-window | 128000    |
    And the isaac EDN file "config/crew/lens.edn" exists with:
      | path  | value |
      | model | lens  |
    And the crew "lens" allows tools: "fs/read"
    And the following sessions exist:
      | name     | crew |
      | darkroom | lens |
    And an image file "pixel.png" exists in the session working directory
    And the following model responses are queued:
      | model | type      | tool_call | arguments                  | content         |
      | claude-lens | tool_call | fs__read  | {"file_path":"pixel.png"} |                 |
      | claude-lens | text      |           |                            | A single pixel. |
    When the user sends "what is in pixel.png?" on session "darkroom"
    Then outbound HTTP request 2 matches:
      | key                                            | value              |
      | body.messages.2.role                           | user               |
      | body.messages.2.content.0.type                 | tool_result        |
      | body.messages.2.content.0.content.0.type       | image              |
      | body.messages.2.content.0.content.0.source.type | base64            |
      | body.messages.2.content.0.content.0.source.media_type | image/png    |
      | body.messages.2.content.0.content.0.source.data | #"^iVBORw0KGgo"   |
    And session "darkroom" has transcript matching:
      | type    | message.role | message.content                                  |
      | message | toolResult   | #"^\[image: pixel\.png, image/png, \d+ bytes\]$" |

  @wip
  Scenario: OpenAI Responses carries the image as an input_image in the function_call_output
    Given the isaac EDN file "config/models/lens.edn" exists with:
      | path           | value     |
      | model          | gpt-lens |
      | provider       | grover:chatgpt |
      | context-window | 128000    |
    And the isaac EDN file "config/crew/lens.edn" exists with:
      | path  | value |
      | model | lens  |
    And the crew "lens" allows tools: "fs/read"
    And the following sessions exist:
      | name     | crew |
      | darkroom | lens |
    And an image file "pixel.png" exists in the session working directory
    And the following model responses are queued:
      | model | type      | tool_call | arguments                  | content         |
      | gpt-lens | tool_call | fs__read  | {"file_path":"pixel.png"} |                 |
      | gpt-lens | text      |           |                            | A single pixel. |
    When the user sends "what is in pixel.png?" on session "darkroom"
    Then outbound HTTP request 2 matches:
      | key                              | value                              |
      | body.input.2.type                | function_call_output               |
      | body.input.2.output.0.type       | input_image                        |
      | body.input.2.output.0.image_url  | #"^data:image/png;base64,iVBORw0KGgo" |
    And session "darkroom" has transcript matching:
      | type    | message.role | message.content                                  |
      | message | toolResult   | #"^\[image: pixel\.png, image/png, \d+ bytes\]$" |

  @wip
  Scenario: Chat Completions follows the text-only tool message with a user message carrying the image
    Given the isaac EDN file "config/models/lens.edn" exists with:
      | path           | value     |
      | model          | gpt-lens |
      | provider       | grover:openai |
      | context-window | 128000    |
    And the isaac EDN file "config/crew/lens.edn" exists with:
      | path  | value |
      | model | lens  |
    And the crew "lens" allows tools: "fs/read"
    And the following sessions exist:
      | name     | crew |
      | darkroom | lens |
    And an image file "pixel.png" exists in the session working directory
    And the following model responses are queued:
      | model | type      | tool_call | arguments                  | content         |
      | gpt-lens | tool_call | fs__read  | {"file_path":"pixel.png"} |                 |
      | gpt-lens | text      |           |                            | A single pixel. |
    When the user sends "what is in pixel.png?" on session "darkroom"
    Then outbound HTTP request 2 matches:
      | key                                | value                              |
      | body.messages.3.role               | tool                               |
      | body.messages.3.content            | #"^\[image: pixel\.png"           |
      | body.messages.4.role               | user                               |
      | body.messages.4.content.0.type     | image_url                          |
      | body.messages.4.content.0.image_url.url | #"^data:image/png;base64,iVBORw0KGgo" |
    And session "darkroom" has transcript matching:
      | type    | message.role | message.content                                  |
      | message | toolResult   | #"^\[image: pixel\.png, image/png, \d+ bytes\]$" |

  @wip
  Scenario: Ollama follows the tool message with a message carrying the image
    Given the isaac EDN file "config/models/lens.edn" exists with:
      | path           | value     |
      | model          | llava-lens |
      | provider       | grover:ollama |
      | context-window | 128000    |
    And the isaac EDN file "config/crew/lens.edn" exists with:
      | path  | value |
      | model | lens  |
    And the crew "lens" allows tools: "fs/read"
    And the following sessions exist:
      | name     | crew |
      | darkroom | lens |
    And an image file "pixel.png" exists in the session working directory
    And the following model responses are queued:
      | model | type      | tool_call | arguments                  | content         |
      | llava-lens | tool_call | fs__read  | {"file_path":"pixel.png"} |                 |
      | llava-lens | text      |           |                            | A single pixel. |
    When the user sends "what is in pixel.png?" on session "darkroom"
    Then outbound HTTP request 2 matches:
      | key                       | value                    |
      | body.messages.3.role      | tool                     |
      | body.messages.3.content   | #"^\[image: pixel\.png" |
      | body.messages.4.role      | user                     |
      | body.messages.4.images.0  | #"^iVBORw0KGgo"          |
    And session "darkroom" has transcript matching:
      | type    | message.role | message.content                                  |
      | message | toolResult   | #"^\[image: pixel\.png, image/png, \d+ bytes\]$" |

  @wip
  Scenario: a model configured without vision gets only the note
    Given the isaac EDN file "config/models/lens.edn" exists with:
      | path           | value     |
      | model          | claude-lens |
      | provider       | grover:anthropic |
      | context-window | 128000    |
      | vision         | false     |
    And the isaac EDN file "config/crew/lens.edn" exists with:
      | path  | value |
      | model | lens  |
    And the crew "lens" allows tools: "fs/read"
    And the following sessions exist:
      | name     | crew |
      | darkroom | lens |
    And an image file "pixel.png" exists in the session working directory
    And the following model responses are queued:
      | model | type      | tool_call | arguments                  | content         |
      | claude-lens | tool_call | fs__read  | {"file_path":"pixel.png"} |                 |
      | claude-lens | text      |           |                            | A single pixel. |
    When the user sends "what is in pixel.png?" on session "darkroom"
    Then outbound HTTP request 2 matches:
      | key                                      | value                   |
      | body.messages.2.content.0.type           | tool_result             |
      | body.messages.2.content.0.content        | #"^\[image: pixel\.png" |
    And session "darkroom" has transcript matching:
      | type    | message.role | message.content                                  |
      | message | toolResult   | #"^\[image: pixel\.png, image/png, \d+ bytes\]$" |

