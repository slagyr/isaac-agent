Feature: web_search integration against the real Brave API
  Validates the web_search tool against the live Brave Search API.
  Tagged @slow so it is excluded from the default `bb features` run;
  use `bb features-slow` to exercise. Requires BRAVE_API_KEY in the
  environment; scenarios fail with a clear message if the key is
  missing.

  @slow
  Scenario: web_search returns real Brave results
    Given the BRAVE_API_KEY environment variable is set
    When the tool "web_search" is called with:
      | query | clojure.org |
    Then the tool result is not an error
    And the tool result contains "clojure.org"
