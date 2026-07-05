Feature: ChatGPT OAuth token refresh
  Codex providers authenticate via oauth-device login. Expired access
  tokens should refresh transparently using the stored refresh token.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: expired chatgpt token is refreshed before use (isaac-zcsk)
    Given the isaac file "auth.json" exists with:
      """
      {"chatgpt": {"type": "oauth", "access": "at-expired", "refresh": "rt-valid", "expires": 0}}
      """
    And the oauth token refresh for provider "chatgpt" returns access token "at-new" expires in 3600
    When the oauth tokens for provider "chatgpt" are resolved
    Then the persisted oauth tokens for "chatgpt" have a future expiry
    And the persisted oauth access for "chatgpt" is "at-new"

  Scenario: invalid refresh token reports login guidance (isaac-zcsk)
    Given the isaac file "auth.json" exists with:
      """
      {"chatgpt": {"type": "oauth", "access": "at-expired", "refresh": "rt-invalid", "expires": 0}}
      """
    And the oauth token refresh for provider "chatgpt" fails
    When the oauth tokens for provider "chatgpt" are resolved
    Then an error is reported indicating authentication failed
    And the error message contains "isaac auth login --provider chatgpt"
