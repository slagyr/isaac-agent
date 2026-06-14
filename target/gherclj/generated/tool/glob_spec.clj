(ns tool.glob-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Built-in glob tool"

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

  (it "glob returns matching file paths"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-glob")
    (isaac.tool.tools-steps/files-exist {:headers ["name" "mtime"], :rows [["src/core.clj" "2026-04-20T00:00:01Z"] ["src/util.clj" "2026-04-20T00:00:02Z"] ["src/notes.md" "2026-04-20T00:00:03Z"]]})
    (isaac.tool.tools-steps/tool-called "glob" {:headers ["pattern" "**/*.clj"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["src/util.clj"] ["src/core.clj"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "notes.md"))

  (it "glob sorts results by modification time, newest first"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-glob")
    (isaac.tool.tools-steps/files-exist {:headers ["name" "mtime"], :rows [["src/old.clj" "2020-01-01T00:00:00Z"] ["src/new.clj" "2026-04-20T00:00:00Z"]]})
    (isaac.tool.tools-steps/tool-called "glob" {:headers ["pattern" "src/*.clj"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["src/new.clj"] ["src/old.clj"]]}))

  (it "glob with no matches returns a clear no-matches result"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-glob")
    (isaac.tool.tools-steps/files-exist {:headers ["name"], :rows [["README.md"]]})
    (isaac.tool.tools-steps/tool-called "glob" {:headers ["pattern" "**/*.clj"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["no matches"]]}))

  (it "glob truncates output at the default head_limit"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-glob")
    (isaac.tool.tools-steps/default-tool-value-is "\"glob\"" "head_limit" 3)
    (isaac.tool.tools-steps/files-exist {:headers ["name" "mtime"], :rows [["a.clj" "2026-04-20T00:00:01Z"] ["b.clj" "2026-04-20T00:00:02Z"] ["c.clj" "2026-04-20T00:00:03Z"] ["d.clj" "2026-04-20T00:00:04Z"] ["e.clj" "2026-04-20T00:00:05Z"]]})
    (isaac.tool.tools-steps/tool-called "glob" {:headers ["pattern" "*.clj"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["e.clj"] ["d.clj"] ["c.clj"] ["truncated"] ["5"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "b.clj"))

  (it "glob respects explicit head_limit"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-glob")
    (isaac.tool.tools-steps/files-exist {:headers ["name" "mtime"], :rows [["a.clj" "2026-04-20T00:00:01Z"] ["b.clj" "2026-04-20T00:00:02Z"] ["c.clj" "2026-04-20T00:00:03Z"] ["d.clj" "2026-04-20T00:00:04Z"] ["e.clj" "2026-04-20T00:00:05Z"]]})
    (isaac.tool.tools-steps/tool-called "glob" {:headers ["pattern" "*.clj"], :rows [["head_limit" "2"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["e.clj"] ["d.clj"] ["truncated"] ["5"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "c.clj")))
