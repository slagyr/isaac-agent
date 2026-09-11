Feature: The :cycle config group
  Turn knobs live under one crew key so `isaac config schema crew.value.cycle`
  lists them: limit (was :cycle-limit — clean cutover, no alias),
  checkpoint-every, checkpoint-prompt, wrap-up-prompt. Layering is the usual
  one: built-in defaults → crew → charge (hail band, cron) (isaac-tic5).

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: the retired :cycle-limit key is rejected and names its replacement
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :local}
       :crew      {:main {:cycle-limit 120}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config validate"
    Then the stderr matches:
      | pattern                                   |
      | crew\.main\.cycle-limit.*:cycle \{:limit  |
    And the exit code is 1

  Scenario: config schema lists the cycle knobs
    Given config file "isaac.edn" containing:
      """
      {:defaults  {:crew :main :model :local}
       :crew      {:main {}}
       :models    {:local {:model "llama3.3:1b" :provider :anthropic}}
       :providers {:anthropic {}}}
      """
    When isaac is run with "config schema crew.value.cycle"
    Then the stdout matches:
      | pattern            |
      | :limit             |
      | :checkpoint-every  |
      | :checkpoint-prompt |
      | :wrap-up-prompt    |
    And the exit code is 0
