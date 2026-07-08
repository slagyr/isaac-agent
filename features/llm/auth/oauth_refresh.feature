Feature: OAuth token refresh
  OAuth providers persist rotating refresh tokens and surface refresh failures as
  provider walls so hail work defers instead of burning attempts.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: Expired chatgpt oauth token refreshes and persists the new access token
    Given chatgpt oauth token is expired with refresh "rt-old"
    And oauth token refresh returns access token "at-new" expires in 3600
    When chatgpt oauth access is resolved
    Then chatgpt oauth tokens have a future expiry
    And persisted chatgpt oauth access is "at-new"

