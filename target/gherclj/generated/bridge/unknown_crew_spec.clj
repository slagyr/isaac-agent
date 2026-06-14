(ns bridge.unknown-crew-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.comm.comm-steps :as comm-steps]
            [isaac.foundation.log-steps :as log-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Unknown crew rejects the turn"

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

  (it "a turn on a session whose crew is unknown is rejected with guidance"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; when the user sends "hello" on session "stale"
    ;; then the reply contains "unknown crew on session stale: wormwood"
    ;; and the reply contains "pass --crew to override"
    ;; then the log has entries matching:
    (pending "not yet implemented"))

  (it "a turn via a non-CLI comm shows a /crew hint instead of --crew"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; when the user sends "hello" on session "stale" via memory comm
    ;; then the reply contains "unknown crew on session stale: wormwood"
    ;; and the reply contains "/crew"
    ;; and the reply does not contain "pass --crew to override"
    ;; then the log has entries matching:
    (pending "not yet implemented"))

  (it "switching the rejected session to a known crew restores normal turns"
    ;; given default Grover setup
    ;; given the following sessions exist:
    ;; when the user sends "hello" on session "stale"
    ;; then the reply contains "unknown crew on session stale: wormwood"
    ;; then the log has entries matching:
    ;; when the user sends "/crew main" on session "stale"
    ;; then the reply contains "switched crew to main"
    ;; then the log has entries matching:
    ;; when the user sends "try again" on session "stale"
    ;; then the system prompt contains "You are Atticus."
    ;; then the log has entries matching:
    (pending "not yet implemented")))
