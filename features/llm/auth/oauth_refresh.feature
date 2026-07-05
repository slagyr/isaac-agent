Feature: ChatGPT OAuth token refresh
  Codex providers authenticate via oauth-device login. Expired access
  tokens should refresh transparently using the stored refresh token.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: expired chatgpt token is refreshed before use (isaac-zcsk)
    Given chatgpt oauth token is expired with refresh "rt-valid"
    And oauth token refresh returns access token "at-new" expires in 3600
    When chatgpt oauth access is resolved
    Then chatgpt oauth tokens have a future expiry
    And persisted chatgpt oauth access is "at-new"

  Scenario: invalid refresh token reports login guidance (isaac-zcsk)
    Given chatgpt oauth token is expired with refresh "rt-invalid"
    And oauth token refresh fails
    When chatgpt oauth access is resolved
    Then chatgpt oauth access is unavailable
    And the oauth access error mentions "isaac auth login --provider chatgpt"