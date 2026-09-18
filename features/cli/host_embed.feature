@wip
Feature: Agent CLI is safe to embed
  Agent commands run inside the server process must not tear down the
  live runtime. Cold `isaac` process teardown is allowed; embed is not.

  Background:
    Given default Grover setup

  Scenario: An embedded sessions show does not clear the live config
    Given the following sessions exist:
      | name     | crew |
      | marigold | main |
    When the command is run embedded with argv "sessions show marigold"
    Then the exit code is 0
    When the command is run embedded with argv "sessions list"
    Then the stdout contains "marigold"
    And the exit code is 0

  Scenario: An embedded prompt records the supplied cwd
    Given the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When the command is run embedded with argv "prompt -m Hi --session voyage" and cwd "/tmp/voyage"
    Then the exit code is 0
    And the following sessions match:
      | id     | cwd         |
      | voyage | /tmp/voyage |
