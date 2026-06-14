(ns session.keys-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Session Routing"

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

  (it "Session tracks last delivery channel"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["my-chat"]]})
    (isaac.session.session-steps/entries-appended "my-chat" {:headers ["type" "message.role" "message.content" "message.channel" "message.to"], :rows [["message" "user" "Hello" "cli" "micah"]]})
    (isaac.session.session-steps/sessions-match {:headers ["id" "last-channel" "last-to"], :rows [["my-chat" "cli" "micah"]]}))

  (it "Delivery channel updates when channel changes"
    (isaac.foundation.root-steps/in-memory-state "\"target/test-state\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["my-chat"]]})
    (isaac.session.session-steps/entries-appended "my-chat" {:headers ["type" "message.role" "message.content" "message.channel" "message.to"], :rows [["message" "user" "Hello" "cli" "micah"]]})
    (isaac.session.session-steps/entries-appended "my-chat" {:headers ["type" "message.role" "message.content" "message.channel" "message.to"], :rows [["message" "user" "Hello again" "telegram" "micah"]]})
    (isaac.session.session-steps/sessions-match {:headers ["id" "last-channel" "last-to"], :rows [["my-chat" "telegram" "micah"]]})))
