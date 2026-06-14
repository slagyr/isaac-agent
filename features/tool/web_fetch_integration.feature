Feature: web_fetch integration against real URLs
  Validates the web_fetch tool against live endpoints. Tagged @slow so
  it is excluded from the default `bb features` run; use `bb features-slow`
  to exercise.

  @slow
  Scenario: web_fetch retrieves real example.com content
    When the tool "web_fetch" is called with:
      | url | https://example.com |
    Then the tool result is not an error
    And the tool result contains "Example Domain"

  @slow
  Scenario: web_fetch follows a real redirect
    When the tool "web_fetch" is called with:
      | url | http://example.com |
    Then the tool result is not an error
    And the tool result contains "Example Domain"
