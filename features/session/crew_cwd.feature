Feature: Crew-level default cwd for new sessions
  A crew's :cwd seeds the session's :cwd when a new session is
  created. :cwd is state-defining (resolved once at create-time,
  locked on the sidecar) — not re-resolved per turn. The cascade
  is: explicit session override > crew > channel default.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: Crew :cwd seeds the new session's cwd
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 1000       |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value                 |
      | model | local                 |
      | soul  | You are Brain.        |
      | cwd   | /lab/world-domination |
    And the following model responses are queued:
      | type | content                           | model      |
      | text | Try to take over the world. Narf! | test-model |
    When the user sends "Are you pondering what I'm pondering?" on session "scheme-prep"
    Then session "scheme-prep" matches:
      | key | value                 |
      | cwd | /lab/world-domination |

  Scenario: Explicit session :cwd overrides crew :cwd
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path     | value      |
      | model    | test-model |
      | provider | grover     |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value                 |
      | model | local                 |
      | soul  | You are Brain.        |
      | cwd   | /lab/world-domination |
    And the following sessions exist:
      | name         | cwd                |
      | rubber-pants | /acme/haberdashery |
    And the following model responses are queued:
      | type | content | model      |
      | text | Narf!   | test-model |
    When the user sends "Where are we going?" on session "rubber-pants"
    Then session "rubber-pants" matches:
      | key | value              |
      | cwd | /acme/haberdashery |

  Scenario: Crew :cwd must be an absolute path
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:crew {:pinky {:cwd "cheese-helmets"}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key            | value                      |
      | crew.pinky.cwd | must be an absolute path.* |
