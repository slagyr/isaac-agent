Feature: OAuth provider descriptors
  Isaac resolves OAuth device-code providers from manifest templates rather than
  hardcoding OpenAI-only endpoints. ChatGPT remains unchanged; Grok gains the
  same Isaac-native login/refresh path with xAI's issuer and client-id.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: Grok provider login uses descriptor-driven xAI device endpoints
    Given the provider "grok" is configured with:
      | key  | value |
      | type | grok  |
    When isaac is run with "auth login --provider grok"
    Then the stdout contains "https://accounts.x.ai/oauth2/device"
    And the exit code is 0

  Scenario: ChatGPT login remains on today's OpenAI device endpoints
    Given the provider "chatgpt" is configured with:
      | key  | value |
      | type | chatgpt |
    When isaac is run with "auth login --provider chatgpt"
    Then the stdout contains "https://auth.openai.com/codex/device"
    And the exit code is 0
