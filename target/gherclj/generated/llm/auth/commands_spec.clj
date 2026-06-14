(ns llm.auth.commands-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.root-steps :as root-steps]))

(describe "Auth Commands"

  (around [it]
    (binding [g/*state* (atom {})]
      (lifecycle/run-before-feature-hooks!)
      (try
        (it)
        (finally
          (lifecycle/run-after-feature-hooks!)))))

  (around [it]
    (binding [g/*state* (atom @g/*state*)]
      (lifecycle/run-before-scenario-hooks!)
      (try
        (it)
        (finally
          (lifecycle/run-after-scenario-hooks!)))))

  (it "Login with Anthropic API key"
    ;; given an Isaac root at "target/test-state"
    ;; when isaac is run with "auth login --provider anthropic --api-key"
    ;; then the stdout prompts for an API key
    ;; then the exit code is 0
    (pending "not yet implemented"))

  (it "Login without specifying provider"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.cli-steps/isaac-run "auth login")
    (isaac.foundation.cli-steps/stdout-contains "Usage:")
    (isaac.foundation.cli-steps/stdout-contains "--provider")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "Login with unknown provider"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.cli-steps/isaac-run "auth login --provider bogus")
    (isaac.foundation.cli-steps/stdout-contains "Unknown provider: bogus")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "Show auth status"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.cli-steps/isaac-run "auth status")
    (isaac.foundation.cli-steps/stdout-contains "ollama")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "Logout from a provider"
    ;; given an Isaac root at "target/test-state"
    ;; given authenticated credentials exist for provider "anthropic"
    ;; when isaac is run with "auth logout --provider anthropic"
    ;; then the stdout contains "Logged out"
    ;; and credentials for "anthropic" are removed
    ;; then the exit code is 0
    (pending "not yet implemented"))

  (it "Auth help"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.foundation.cli-steps/isaac-run "auth --help")
    (isaac.foundation.cli-steps/stdout-contains "Usage: isaac auth")
    (isaac.foundation.cli-steps/stdout-contains "login")
    (isaac.foundation.cli-steps/stdout-contains "status")
    (isaac.foundation.cli-steps/stdout-contains "logout")
    (isaac.foundation.cli-steps/exit-code-is "0")))
