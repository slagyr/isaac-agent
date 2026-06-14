(ns session.history-retention-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Compaction history retention policy"

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

  (it "Under :retain, compacted entries remain in the transcript file"
    (isaac.foundation.root-steps/empty-state "\"/test\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "isaac.edn" {:headers ["path" "value"], :rows [["defaults.history-retention" ":retain"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["retain-keep"]]})
    (isaac.session.session-steps/session-has-transcript "retain-keep" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Earlier question"] ["message" "assistant" "Earlier answer"] ["message" "user" "Recent question"] ["message" "assistant" "Recent answer"]]})
    (isaac.session.session-steps/compaction-spliced-into-session "retain-keep" {:headers ["key" "value"], :rows [["summary" "Caught up the model."] ["firstKeptIndex" "2"] ["compactedIndexes" "[0, 1]"] ["tokensBefore" "20"]]})
    (isaac.session.session-steps/session-transcript-matching "retain-keep" {:headers ["type" "message.content"], :rows [["message" "Earlier question"] ["message" "Earlier answer"] ["message" "Recent question"] ["message" "Recent answer"]]})
    (isaac.session.session-steps/session-transcript-matching "retain-keep" {:headers ["type" "summary"], :rows [["compaction" "Caught up the model."]]}))

  (it "Under :prune, compacted entries are removed from the transcript file"
    (isaac.foundation.root-steps/empty-state "\"/test\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "isaac.edn" {:headers ["path" "value"], :rows [["defaults.history-retention" ":prune"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["prune-cut"]]})
    (isaac.session.session-steps/session-has-transcript "prune-cut" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Earlier question"] ["message" "assistant" "Earlier answer"] ["message" "user" "Recent question"] ["message" "assistant" "Recent answer"]]})
    (isaac.session.session-steps/compaction-spliced-into-session "prune-cut" {:headers ["key" "value"], :rows [["summary" "Caught up the model."] ["firstKeptIndex" "2"] ["compactedIndexes" "[0, 1]"] ["tokensBefore" "20"]]})
    (isaac.session.session-steps/session-transcript-not-matching "prune-cut" {:headers ["type" "message.content"], :rows [["message" "Earlier question"] ["message" "Earlier answer"]]})
    (isaac.session.session-steps/session-transcript-matching "prune-cut" {:headers ["type" "message.content"], :rows [["message" "Recent question"] ["message" "Recent answer"]]})
    (isaac.session.session-steps/session-transcript-matching "prune-cut" {:headers ["type" "summary"], :rows [["compaction" "Caught up the model."]]}))

  (it "Retention is locked at session creation; changing defaults later does not flip it"
    (isaac.foundation.root-steps/empty-state "\"/test\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "isaac.edn" {:headers ["path" "value"], :rows [["defaults.history-retention" ":retain"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["locked-keep"]]})
    (isaac.session.session-steps/session-matches "locked-keep" {:headers ["key" "value"], :rows [["history-retention" ":retain"]]})
    (isaac.foundation.fs-steps/isaac-edn-file-exists "isaac.edn" {:headers ["path" "value"], :rows [["defaults.history-retention" ":prune"]]})
    (isaac.session.session-steps/session-matches "locked-keep" {:headers ["key" "value"], :rows [["history-retention" ":retain"]]}))

  (it "Explicit create-time override wins over crew and defaults"
    (isaac.foundation.root-steps/empty-state "\"/test\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "isaac.edn" {:headers ["path" "value"], :rows [["defaults.history-retention" ":prune"] ["crew.main.history-retention" ":prune"]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "history-retention"], :rows [["override" "main" ":retain"]]})
    (isaac.session.session-steps/session-matches "override" {:headers ["key" "value"], :rows [["history-retention" ":retain"]]})))
