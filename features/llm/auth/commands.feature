Feature: Auth Commands
  Isaac provides commands to manage authentication credentials
  for LLM providers. OpenClaw-compatible aliases are supported
  via "models auth" for drop-in compatibility.

  Background:
    Given an Isaac root at "target/test-state"

  # --- Login ---

  Scenario: Login with Anthropic API key
    When isaac is run with "auth login --provider anthropic --api-key"
    Then the stdout prompts for an API key
    And the exit code is 0

  Scenario: Login without specifying provider
    When isaac is run with "auth login"
    Then the stdout contains "Usage:"
    And the stdout contains "--provider"
    And the exit code is 1

  Scenario: Login with unknown provider
    When isaac is run with "auth login --provider bogus"
    Then the stdout contains "Unknown provider: bogus"
    And the exit code is 1

  Scenario: Login with Grok device-code OAuth
    When isaac is run with "auth login --provider grok"
    Then the stdout contains "https://accounts.x.ai/oauth2/device"
    And the stdout contains "Tokens saved for grok"
    And the exit code is 0

  # --- Status ---

  Scenario: Show auth status
    When isaac is run with "auth status"
    Then the stdout contains "ollama"
    And the exit code is 0

  # --- Logout ---

  Scenario: Logout from a provider
    Given authenticated credentials exist for provider "anthropic"
    When isaac is run with "auth logout --provider anthropic"
    Then the stdout contains "Logged out"
    And credentials for "anthropic" are removed
    And the exit code is 0

  # --- Help ---

  Scenario: Auth help
    When isaac is run with "auth --help"
    Then the stdout contains "Usage: isaac auth"
    And the stdout contains "login"
    And the stdout contains "status"
    And the stdout contains "logout"
    And the exit code is 0
