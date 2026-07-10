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

  Scenario: Single-use refresh rotation persists the rotated token
    Given chatgpt oauth token is expired with refresh "rt-old"
    And oauth token refresh returns access token "at-new" and refresh token "rt-rotated" expires in 3600
    When chatgpt oauth access is resolved
    Then persisted chatgpt oauth access is "at-new"
    And persisted chatgpt oauth refresh is "rt-rotated"

  Scenario: Concurrent refreshers share the single rotated refresh token
    Given chatgpt oauth token is expired with refresh "rt-old"
    When two concurrent chatgpt oauth refreshers resolve access
    Then both chatgpt oauth refreshers observe access "at-new" and refresh "rt-rotated"

  Scenario: Refresh failure without retry metadata still classifies oauth access as a provider wall
    Given chatgpt oauth token is expired with refresh "rt-old"
    And oauth token refresh fails
    Then chatgpt oauth access is deferred using the default provider-wall delay

  Scenario: Refresh provider wall preserves the explicit retry delay
    Given chatgpt oauth token is expired with refresh "rt-old"
    And oauth token refresh returns a provider wall after 60 seconds
    Then chatgpt oauth access is deferred as a provider wall after 60 seconds

