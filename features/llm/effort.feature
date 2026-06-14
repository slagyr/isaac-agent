Feature: Universal effort knob
  Isaac exposes a single integer effort knob (0-10) applied to every model
  and provider. The universal layer resolves the value from the configuration
  chain and attaches :effort to the request map before any API adapter
  touches the wire shape.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name     |
      | effort-s |
    And the following model responses are queued:
      | type | content |
      | text | ok      |

  Scenario: Default effort is 7 when nothing is configured
    When the user sends "hi" on session "effort-s"
    Then the last LLM request matches:
      | key    | value |
      | effort | 7     |

  Scenario: Provider-level effort overrides the default
    Given the isaac EDN file "config/providers/grover.edn" exists with:
      | path   | value |
      | effort | 3     |
    When the user sends "hi" on session "effort-s"
    Then the last LLM request matches:
      | key    | value |
      | effort | 3     |

  Scenario: Model-level effort overrides provider-level
    Given the isaac EDN file "config/providers/grover.edn" exists with:
      | path   | value |
      | effort | 3     |
    And the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value  |
      | model          | echo   |
      | provider       | grover |
      | context-window | 32768  |
      | effort         | 5      |
    When the user sends "hi" on session "effort-s"
    Then the last LLM request matches:
      | key    | value |
      | effort | 5     |

  Scenario: Crew-level effort overrides model-level and provider-level
    Given the isaac EDN file "config/providers/grover.edn" exists with:
      | path   | value |
      | effort | 3     |
    And the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value  |
      | model          | echo   |
      | provider       | grover |
      | context-window | 32768  |
      | effort         | 5      |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path   | value         |
      | model  | grover        |
      | soul   | You are Isaac |
      | effort | 9             |
    When the user sends "hi" on session "effort-s"
    Then the last LLM request matches:
      | key    | value |
      | effort | 9     |

  Scenario: Session effort overrides all config tiers
    Given the isaac EDN file "config/providers/grover.edn" exists with:
      | path   | value |
      | effort | 3     |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path   | value         |
      | model  | grover        |
      | soul   | You are Isaac |
      | effort | 9             |
    And the session "effort-s" has effort 2
    When the user sends "hi" on session "effort-s"
    Then the last LLM request matches:
      | key    | value |
      | effort | 2     |

  Scenario: defaults.effort overrides the built-in default of 7
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path            | value |
      | defaults.effort | 4     |
    When the user sends "hi" on session "effort-s"
    Then the last LLM request matches:
      | key    | value |
      | effort | 4     |

  Scenario: allows-effort false omits effort from the request
    Given the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value  |
      | model          | echo   |
      | provider       | grover |
      | context-window | 32768  |
      | allows-effort  | false  |
    When the user sends "hi" on session "effort-s"
    Then the last LLM request has no effort

  Scenario: Effort 0 is passed to the request
    Given the isaac EDN file "config/crew/main.edn" exists with:
      | path   | value         |
      | model  | grover        |
      | soul   | You are Isaac |
      | effort | 0             |
    When the user sends "hi" on session "effort-s"
    Then the last LLM request matches:
      | key    | value |
      | effort | 0     |
