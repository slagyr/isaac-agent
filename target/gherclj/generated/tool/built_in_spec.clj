(ns tool.built-in-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Built-in Tools"

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

  (it "Read a file"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.foundation.fs-steps/file-at-with-content "test.txt" "line one\\nline two\\nline three")
    (isaac.tool.tools-steps/tool-called "read" {:headers ["file_path" "test.txt"], :rows []})
    (isaac.tool.tools-steps/tool-result-contains "line one")
    (isaac.tool.tools-steps/tool-result-contains "line three")
    (isaac.tool.tools-steps/tool-result-not-error))

  (it "Read a file with offset and limit"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/file-with-lines "long.txt" "100")
    (isaac.tool.tools-steps/tool-called "read" {:headers ["file_path" "long.txt"], :rows [["offset" "10"] ["limit" "5"]]})
    (isaac.tool.tools-steps/tool-result-contains "line 10")
    (isaac.tool.tools-steps/tool-result-contains "line 14")
    (isaac.tool.tools-steps/tool-result-not-contains "line 9")
    (isaac.tool.tools-steps/tool-result-not-contains "line 15"))

  (it "Read a directory"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/dir-with-files "mydir" "\"a.txt\" and \"b.txt\"")
    (isaac.tool.tools-steps/tool-called "read" {:headers ["file_path" "mydir"], :rows []})
    (isaac.tool.tools-steps/tool-result-contains "a.txt")
    (isaac.tool.tools-steps/tool-result-contains "b.txt"))

  (it "Read a missing file"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/tool-called "read" {:headers ["file_path" "no-such-file.txt"], :rows []})
    (isaac.tool.tools-steps/tool-result-is-error)
    (isaac.tool.tools-steps/tool-result-contains "not found"))

  (it "read output prefixes each line with its line number"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.foundation.fs-steps/file-at-with-content "test.txt" "alpha\\nbeta\\ngamma")
    (isaac.tool.tools-steps/tool-called "read" {:headers ["file_path" "test.txt"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["1: alpha"] ["2: beta"] ["3: gamma"]]}))

  (it "read truncates output at the default line limit"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/default-tool-value-is "\"read\"" "limit" 3)
    (isaac.tool.tools-steps/file-with-lines "medium.txt" "5")
    (isaac.tool.tools-steps/tool-called "read" {:headers ["file_path" "medium.txt"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["1: line 1"] ["3: line 3"] ["truncated"] ["5"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "4: line 4"))

  (it "read refuses to dump binary files"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/binary-file-exists "image.bin")
    (isaac.tool.tools-steps/tool-called "read" {:headers ["file_path" "image.bin"], :rows []})
    (isaac.tool.tools-steps/tool-result-is-error)
    (isaac.tool.tools-steps/tool-result-contains "binary"))

  (it "read on an empty file returns a clear empty-file signal"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.foundation.fs-steps/file-at-with-content "empty.txt" "")
    (isaac.tool.tools-steps/tool-called "read" {:headers ["file_path" "empty.txt"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["<empty file>"]]}))

  (it "read with offset and limit preserves absolute line numbers"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/file-with-lines "long.txt" "100")
    (isaac.tool.tools-steps/tool-called "read" {:headers ["file_path" "long.txt"], :rows [["offset" "10"] ["limit" "3"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["10: line 10"] ["11: line 11"] ["12: line 12"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "line 9")
    (isaac.tool.tools-steps/tool-result-not-contains "13: line 13"))

  (it "read on a directory lists entries without line numbers"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/dir-with-files "mydir" "\"a.txt\" and \"b.txt\"")
    (isaac.tool.tools-steps/tool-called "read" {:headers ["file_path" "mydir"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["a.txt"] ["b.txt"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "1:")
    (isaac.tool.tools-steps/tool-result-not-contains "2:"))

  (it "the tool result lines match step accepts negative indices"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.foundation.fs-steps/file-at-with-content "tail.txt" "alpha\\nbeta\\ngamma")
    (isaac.tool.tools-steps/tool-called "read" {:headers ["file_path" "tail.txt"], :rows []})
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text" "#index"], :rows [["gamma" "-1"] ["beta" "-2"]]}))

  (it "Write a new file"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/tool-called "write" {:headers ["file_path" "new.txt"], :rows [["content" "hello world"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/file-has-content "new.txt" "hello world"))

  (it "Overwrite an existing file"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.foundation.fs-steps/file-at-with-content "existing.txt" "old")
    (isaac.tool.tools-steps/tool-called "write" {:headers ["file_path" "existing.txt"], :rows [["content" "new"]]})
    (isaac.tool.tools-steps/file-has-content "existing.txt" "new"))

  (it "Edit replaces matching text"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.foundation.fs-steps/file-at-with-content "code.txt" "foo = 1\\nbar = 2")
    (isaac.tool.tools-steps/tool-called "edit" {:headers ["file_path" "code.txt"], :rows [["old_string" "foo = 1"] ["new_string" "foo = 42"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/file-has-content "code.txt" "foo = 42\\nbar = 2"))

  (it "Edit with no match returns error"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.foundation.fs-steps/file-at-with-content "code.txt" "foo = 1")
    (isaac.tool.tools-steps/tool-called "edit" {:headers ["file_path" "code.txt"], :rows [["old_string" "not here"] ["new_string" "replacement"]]})
    (isaac.tool.tools-steps/tool-result-is-error)
    (isaac.tool.tools-steps/tool-result-contains "not found"))

  (it "Edit with multiple matches and no replace_all returns error"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.foundation.fs-steps/file-at-with-content "code.txt" "x = 1\\nx = 1\\nx = 1")
    (isaac.tool.tools-steps/tool-called "edit" {:headers ["file_path" "code.txt"], :rows [["old_string" "x = 1"] ["new_string" "x = 2"]]})
    (isaac.tool.tools-steps/tool-result-is-error)
    (isaac.tool.tools-steps/tool-result-contains "multiple"))

  (it "Edit with replace_all replaces all occurrences"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.foundation.fs-steps/file-at-with-content "code.txt" "x = 1\\ny = 2\\nx = 1")
    (isaac.tool.tools-steps/tool-called "edit" {:headers ["file_path" "code.txt"], :rows [["old_string" "x = 1"] ["new_string" "x = 99"] ["replace_all" "true"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/file-has-content "code.txt" "x = 99\\ny = 2\\nx = 99"))

  (it "Execute a shell command"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/tool-called "exec" {:headers ["command" "echo hello world"], :rows []})
    (isaac.tool.tools-steps/tool-result-contains "hello world")
    (isaac.tool.tools-steps/tool-result-not-error))

  (it "Execute a failing command"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/tool-called "exec" {:headers ["command" "exit 1"], :rows []})
    (isaac.tool.tools-steps/tool-result-is-error))

  (it "Execute with a working directory"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/dir-with-files "subdir" "\"target.txt\"")
    (isaac.tool.tools-steps/tool-called "exec" {:headers ["command" "ls"], :rows [["workdir" "subdir"]]})
    (isaac.tool.tools-steps/tool-result-contains "target.txt"))

  (it "Execute defaults workdir to the session's cwd"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/dir-with-files "session-cwd" "\"marker.txt\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "cwd"], :rows [["exec-cwd" "main" "session-cwd"]]})
    (isaac.tool.tools-steps/current-session-is "exec-cwd")
    (isaac.tool.tools-steps/tool-called "exec" {:headers ["command" "ls"], :rows []})
    (isaac.tool.tools-steps/tool-result-contains "marker.txt"))

  (it "Explicit workdir overrides the session's cwd"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/dir-with-files "session-cwd" "\"session-marker.txt\"")
    (isaac.tool.tools-steps/dir-with-files "explicit-dir" "\"explicit-marker.txt\"")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "cwd"], :rows [["exec-cwd" "main" "session-cwd"]]})
    (isaac.tool.tools-steps/current-session-is "exec-cwd")
    (isaac.tool.tools-steps/tool-called "exec" {:headers ["command" "ls"], :rows [["workdir" "explicit-dir"]]})
    (isaac.tool.tools-steps/tool-result-contains "explicit-marker.txt")
    (isaac.tool.tools-steps/tool-result-not-contains "session-marker.txt"))

  (it "Execute with timeout exceeded"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-tools")
    (isaac.tool.tools-steps/exec-timeout 25)
    (isaac.tool.tools-steps/tool-called "exec" {:headers ["command" "sleep 10"], :rows []})
    (isaac.tool.tools-steps/tool-result-is-error)
    (isaac.tool.tools-steps/tool-result-contains "timeout")))
