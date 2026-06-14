Feature: Built-in web_fetch tool
  Crew members fetch URL content via HTTP GET. Text is extracted from
  HTML by default (script and style blocks stripped); binary content
  types are refused; redirects are followed.

  Background:
    Given a clean test directory "target/test-state-webfetch"

  Scenario: web_fetch returns the body text of a URL
    Given the URL "http://example.local/hello" has body:
      """
      Hello, world.
      """
    When the tool "web_fetch" is called with:
      | url | http://example.local/hello |
    Then the tool result is not an error
    And the tool result lines match:
      | text          |
      | Hello, world. |

  Scenario: web_fetch strips script and style blocks
    Given the URL "http://example.local/page" has body:
      """
      <html><head>
        <style>body { color: red; font: 12pt Arial; }</style>
        <script>var secrets = "tracker";</script>
      </head><body>
        <h1>Hello</h1>
        <p>Main content.</p>
      </body></html>
      """
    When the tool "web_fetch" is called with:
      | url | http://example.local/page |
    Then the tool result is not an error
    And the tool result lines match:
      | text          |
      | Hello         |
      | Main content. |
    And the tool result does not contain "color: red"
    And the tool result does not contain "var secrets"

  Scenario: web_fetch with format=raw preserves script and style blocks
    Given the URL "http://example.local/page" has body:
      """
      <script>var secret = 1;</script><h1>Title</h1>
      """
    When the tool "web_fetch" is called with:
      | url    | http://example.local/page |
      | format | raw                       |
    Then the tool result is not an error
    And the tool result contains "<script>"
    And the tool result contains "var secret"
    And the tool result contains "<h1>Title</h1>"

  Scenario: web_fetch truncates output at the default line limit
    Given the default "web_fetch" limit is 3
    And the URL "http://example.local/long" has body:
      """
      line 1
      line 2
      line 3
      line 4
      line 5
      """
    When the tool "web_fetch" is called with:
      | url | http://example.local/long |
    Then the tool result is not an error
    And the tool result lines match:
      | text      |
      | line 1    |
      | line 3    |
      | truncated |
      | 5         |
    And the tool result does not contain "line 4"

  Scenario: web_fetch refuses binary content types
    Given the URL "http://example.local/image.png" responds with:
      | header.content-type | image/png |
    When the tool "web_fetch" is called with:
      | url | http://example.local/image.png |
    Then the tool result is an error
    And the tool result contains "binary"

  Scenario: web_fetch follows redirects
    Given the URL "http://example.local/old" responds with:
      | status          | 301                      |
      | header.location | http://example.local/new |
    And the URL "http://example.local/new" has body:
      """
      Moved here.
      """
    When the tool "web_fetch" is called with:
      | url | http://example.local/old |
    Then the tool result is not an error
    And the tool result contains "Moved here"
