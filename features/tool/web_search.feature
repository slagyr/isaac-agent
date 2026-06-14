Feature: Built-in web_search tool
  Crew members search the web via Brave Search API. Returns a numbered
  list of results (title + URL + snippet) suitable for pairing with
  web_fetch to retrieve full content. API key lives in config under
  :tools :web_search :api-key (typically via ${BRAVE_API_KEY} env sub).

  Background:
    Given a clean test directory "target/test-state-websearch"

  Scenario: web_search returns ranked result entries
    Given the search query "clojure core async" returns results:
      | title            | url                         | description            |
      | core.async guide | https://clojure.org/async   | Channels and go blocks |
      | Rich Hickey talk | https://youtu.be/hMIZ9g6ucs | Intro to core.async    |
    When the tool "web_search" is called with:
      | query | clojure core async |
    Then the tool result is not an error
    And the tool result lines match:
      | text                        |
      | 1. core.async guide         |
      | https://clojure.org/async   |
      | Channels and go blocks      |
      | 2. Rich Hickey talk         |
      | https://youtu.be/hMIZ9g6ucs |
      | Intro to core.async         |

  Scenario: web_search limits output to num_results
    Given the search query "clojure" returns results:
      | title   | url                   | description |
      | Guide 1 | https://example.com/1 | snippet 1   |
      | Guide 2 | https://example.com/2 | snippet 2   |
      | Guide 3 | https://example.com/3 | snippet 3   |
    When the tool "web_search" is called with:
      | query       | clojure |
      | num_results | 2       |
    Then the tool result is not an error
    And the tool result contains "1. Guide 1"
    And the tool result contains "2. Guide 2"
    And the tool result does not contain "Guide 3"

  Scenario: web_search with no matches returns a clear no-results result
    Given the search query "ajshdkajshdakjsh" returns results:
      | title | url | description |
    When the tool "web_search" is called with:
      | query | ajshdkajshdakjsh |
    Then the tool result is not an error
    And the tool result lines match:
      | text       |
      | no results |

  Scenario: web_search without configured API key returns a config error
    When the tool "web_search" is called with:
      | query | clojure |
    Then the tool result is an error
    And the tool result contains "api_key"
    And the tool result contains "web_search"
