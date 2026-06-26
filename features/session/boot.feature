Feature: Boot Files
  The drive includes project boot files (AGENTS.md) from the
  discovered project root in the system prompt alongside the soul.
  Boot files provide project context — conventions, tools, workflow.

  Background:
    Given default Grover setup

  Scenario: session includes AGENTS.md from project root in system prompt
    Given the following sessions exist:
      | name      | cwd                 |
      | boot-test | target/test-project |
    And the file "target/test-project/AGENTS.md" exists with:
      """
      ## House Rules
      No tabs. Ever. Hieronymus will judge you.
      """
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When the user sends "hi" on session "boot-test"
    Then the system prompt contains "You are Atticus."
    And the system prompt contains "Hieronymus will judge you"

  Scenario: session discovers AGENTS.md when cwd is a nested subdirectory
    Given the following sessions exist:
      | name      | cwd                          |
      | boot-test | target/test-project/src/deep |
    And the file "target/test-project/AGENTS.md" exists with:
      """
      ## House Rules
      No tabs. Ever. Hieronymus will judge you.
      """
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When the user sends "hi" on session "boot-test"
    Then the system prompt contains "You are Atticus."
    And the system prompt contains "Hieronymus will judge you"

  Scenario: session works without AGENTS.md in project root
    Given the following sessions exist:
      | name      | cwd                  |
      | boot-test | target/empty-project |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When the user sends "hi" on session "boot-test"
    Then the system prompt contains "You are Atticus."
    And the system prompt does not contain "Hieronymus"
