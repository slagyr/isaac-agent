(ns tool.output-cap-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Global cap on tool-result output before transcript persist"

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

  (it "exec stdout exceeding the byte cap is truncated with a marker naming the cap"
    (isaac.foundation.root-steps/empty-state "\"/test\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "isaac.edn" {:headers ["path" "value"], :rows [["tools.defaults.max-lines" "5"] ["tools.defaults.max-bytes" "100"]]})
    (isaac.tool.tools-steps/tool-executed "exec" {:headers ["command"], :rows [["yes x" "tr -d '\\n'" "head -c 200"]]})
    (isaac.tool.tools-steps/tool-result-contains "[ 100 bytes truncated; byte cap hit ]"))

  (it "read of a file with many short lines is truncated with a marker naming the line cap"
    (isaac.foundation.root-steps/empty-state "\"/test\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "isaac.edn" {:headers ["path" "value"], :rows [["tools.defaults.max-lines" "5"] ["tools.defaults.max-bytes" "100"]]})
    (isaac.tool.tools-steps/file-with-lines "/test/lines.txt" "20")
    (isaac.tool.tools-steps/tool-executed "read" {:headers ["file_path"], :rows [["/test/lines.txt"]]})
    (isaac.tool.tools-steps/tool-result-contains "[ 15 lines truncated; line cap hit ]"))

  (it "the truncated tool result is what gets persisted to the transcript"
    (isaac.foundation.root-steps/empty-state "\"/test\"")
    (isaac.foundation.fs-steps/isaac-edn-file-exists "isaac.edn" {:headers ["path" "value"], :rows [["tools.defaults.max-lines" "5"] ["tools.defaults.max-bytes" "100"]]})
    (isaac.session.session-steps/session-exists "cap-persist")
    (isaac.tool.tools-steps/tool-executed-for-session "exec" "cap-persist" {:headers ["command"], :rows [["yes x" "tr -d '\\n'" "head -c 200"]]})
    (isaac.session.session-steps/session-transcript-matching "cap-persist" {:headers ["role" "content-matcher"], :rows [["toolResult" "contains \"[ 100 bytes truncated; byte cap hit ]\""]]})))
