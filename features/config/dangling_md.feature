Feature: Config validation — dangling .md warnings
  Companion .md files (crew/<id>.md, cron/<id>.md, etc.) are expected
  to live alongside their .edn entity or config entry. A lone .md
  file with no matching entry is likely a typo or half-done config.
  Isaac warns at config-load time rather than silently ignoring.

  Warnings surface on stderr; exit stays 0. Does NOT auto-create
  entity entries from dangling .md files (too magic). Future: strict
  mode that promotes these to errors.

  Background:
    Given an Isaac root at "isaac-state"

  Scenario: dangling crew/<id>.md with no matching entity warns
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :llama}
       :crew      {:main {:soul "Hello"}}
       :models    {:llama {:model "llama" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    And config file "crew/ghost.md" containing:
      """
      I have no matching entity.
      """
    When isaac is run with "config validate"
    Then the stderr contains "dangling"
    And the stderr contains "crew/ghost.md"
    And the exit code is 0

  Scenario: a single-file crew/<id>.md entity is not dangling
    Given config file "crew/main.md" containing:
      """
      ---
      model: llama
      ---

      You are Atticus.
      """
    And config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :llama}
       :models    {:llama {:model "llama" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config validate"
    Then the stderr does not contain "dangling"
    And the exit code is 0
