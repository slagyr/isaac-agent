Feature: comm_send tool
  A crew-callable tool that turns a model's intent into an outbound comm
  delivery. comm_send is queue-first: it enqueues a canonical delivery
  record ({:comm <slot> :content <body> ...comm fields}) and the delivery
  worker handles send / retry / dead-letter. Its parameter schema is built
  from the configured comm slots: the framework-owned common fields
  (:comm, :content) plus the UNION of each configured comm's :send-schema
  fields. Module-contributed keys are namespaced by comm type
  (e.g. :telly/target) so the union never collides and isaac-agent stays
  generic.

  New steps this feature introduces (see bean isaac-2s0b):
  - the prompt tool "<name>" has parameters:   (asserts the tool's
    :parameters schema; exact param set; namespaced keys like
    telly/target are single keyword keys, not nested paths)
  - a pending comm delivery matches:           (id-agnostic scan of
    comm/delivery/pending/*.edn)
  - there are no pending comm deliveries
  - the comm_send tool result is an error      (verify if an existing
    :tool-result step already covers this)

  Background:
    Given default Grover setup
    And the crew "main" allows tools: comm_send
    And the following sessions exist:
      | name       | crew |
      | dawn-watch | main |

  @wip
  Scenario: comm_send exposes only the common fields for a comm with no :send-schema
    Given config:
      | key                | value   |
      | comms.skybeam.type | skybeam |
    When the prompt for session "dawn-watch" is built for provider "grover"
    Then the prompt tool "comm_send" has parameters:
      | param   | type   | required |
      | comm    | string | true     |
      | content | string | true     |

  @wip
  Scenario: a comm's :send-schema adds namespaced comm-specific fields to comm_send
    Given the telly comm module is registered
    And config:
      | key               | value |
      | comms.tannoy.type | telly |
    When the prompt for session "dawn-watch" is built for provider "grover"
    Then the prompt tool "comm_send" has parameters:
      | param        | type   | required |
      | comm         | string | true     |
      | content      | string | true     |
      | telly/target | string | false    |
      | telly/loft   | string | false    |

  @wip
  Scenario: calling comm_send enqueues a delivery record, queue-first
    Given the telly comm module is registered
    And config:
      | key               | value |
      | comms.tannoy.type | telly |
    And the following model responses are queued:
      | type     | tool_call | arguments                                                                                |
      | toolCall | comm_send | {"comm":"tannoy","content":"Lantern is lit.","telly/target":"bridge","telly/loft":"high"} |
      | text     |           | Done.                                                                                    |
    When the user sends "tell the bridge" on session "dawn-watch"
    And the turn ends on session "dawn-watch"
    Then a pending comm delivery matches:
      | path         | value           |
      | comm         | tannoy          |
      | content      | Lantern is lit. |
      | telly/target | bridge          |
      | telly/loft   | high            |

  @wip
  Scenario: comm_send to an unknown comm slot errors without enqueueing
    And the following model responses are queued:
      | type     | tool_call | arguments                                    |
      | toolCall | comm_send | {"comm":"phantom","content":"Anyone there?"} |
      | text     |           | Could not send.                              |
    When the user sends "ping phantom" on session "dawn-watch"
    And the turn ends on session "dawn-watch"
    Then the comm_send tool result is an error
    And there are no pending comm deliveries
