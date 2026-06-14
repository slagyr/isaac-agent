Feature: Session Routing
  Sessions track which channel last delivered a message, enabling
  multi-channel conversations where the same session can be
  accessed from CLI, ACP, web, etc.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: Session tracks last delivery channel
    Given the following sessions exist:
      | name       |
      | my-chat    |
    When entries are appended to session "my-chat":
      | type    | message.role | message.content | message.channel | message.to |
      | message | user         | Hello           | cli             | micah      |
    Then the following sessions match:
      | id      | last-channel | last-to |
      | my-chat | cli         | micah  |

  Scenario: Delivery channel updates when channel changes
    Given the following sessions exist:
      | name       |
      | my-chat    |
    And entries are appended to session "my-chat":
      | type    | message.role | message.content | message.channel | message.to |
      | message | user         | Hello           | cli             | micah      |
    When entries are appended to session "my-chat":
      | type    | message.role | message.content | message.channel | message.to |
      | message | user         | Hello again     | telegram        | micah      |
    Then the following sessions match:
      | id      | last-channel | last-to   |
      | my-chat | telegram    | micah    |
