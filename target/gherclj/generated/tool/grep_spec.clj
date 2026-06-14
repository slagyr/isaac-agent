(ns tool.grep-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Built-in grep tool"

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

  (it "grep returns matching lines with file:line prefix"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-grep")
    (isaac.foundation.fs-steps/file-at-with-content "src/core.clj" "(defn greet [name])\\n(defn shout [name])")
    (isaac.tool.tools-steps/tool-called "grep" {:headers ["pattern" "defn"], :rows [["path" "src"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["core.clj:1:"] ["(defn greet"] ["core.clj:2:"] ["(defn shout"]]}))

  (it "grep with no matches returns a clear no-matches result"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-grep")
    (isaac.foundation.fs-steps/file-at-with-content "src/core.clj" "(defn greet [name])")
    (isaac.tool.tools-steps/tool-called "grep" {:headers ["pattern" "xyzzy"], :rows [["path" "src"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["no matches"]]}))

  (it "grep glob filter limits search to matching files"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-grep")
    (isaac.foundation.fs-steps/file-at-with-content "src/core.clj" "(defn greet [name])")
    (isaac.foundation.fs-steps/file-at-with-content "src/notes.md" "defn is a Clojure macro")
    (isaac.tool.tools-steps/tool-called "grep" {:headers ["pattern" "defn"], :rows [["path" "src"] ["glob" "*.clj"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["core.clj"] ["(defn greet"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "notes.md"))

  (it "grep output_mode files_with_matches returns paths only"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-grep")
    (isaac.foundation.fs-steps/file-at-with-content "src/core.clj" "(defn greet [name])")
    (isaac.foundation.fs-steps/file-at-with-content "src/util.clj" "(defn shout [name])")
    (isaac.foundation.fs-steps/file-at-with-content "src/notes.md" "no matches here")
    (isaac.tool.tools-steps/tool-called "grep" {:headers ["pattern" "defn"], :rows [["path" "src"] ["output_mode" "files_with_matches"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["core.clj"] ["util.clj"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "notes.md")
    (isaac.tool.tools-steps/tool-result-not-contains "(defn"))

  (it "grep output_mode count returns match count per file"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-grep")
    (isaac.foundation.fs-steps/file-at-with-content "src/core.clj" "(defn greet [name])\\n(defn shout [name])")
    (isaac.foundation.fs-steps/file-at-with-content "src/util.clj" "(defn only [name])")
    (isaac.tool.tools-steps/tool-called "grep" {:headers ["pattern" "defn"], :rows [["path" "src"] ["output_mode" "count"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["core.clj:2"] ["util.clj:1"]]}))

  (it "grep truncates output at the default head_limit"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-grep")
    (isaac.tool.tools-steps/default-tool-value-is "\"grep\"" "head_limit" 3)
    (isaac.tool.tools-steps/file-with-lines "short.txt" "5")
    (isaac.tool.tools-steps/tool-called "grep" {:headers ["pattern" "line"], :rows [["path" "short.txt"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["line 1"] ["line 3"] ["truncated"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "line 4"))

  (it "grep respects explicit head_limit"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-grep")
    (isaac.tool.tools-steps/file-with-lines "big.txt" "300")
    (isaac.tool.tools-steps/tool-called "grep" {:headers ["pattern" "line"], :rows [["path" "big.txt"] ["head_limit" "5"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["line 1"] ["line 5"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "line 6"))

  (it "grep with -i flag is case-insensitive"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-grep")
    (isaac.foundation.fs-steps/file-at-with-content "src/core.clj" "(DEFN greet)\\n(defn shout)")
    (isaac.tool.tools-steps/tool-called "grep" {:headers ["pattern" "defn"], :rows [["path" "src"] ["-i" "true"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["(DEFN greet"] ["(defn shout"]]}))

  (it "grep -C includes context lines before and after matches"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-grep")
    (isaac.foundation.fs-steps/file-at-with-content "src/core.clj" "(ns core)\\n(defn before [x] x)\\n(defn target [x] x)\\n(defn after [x] x)\\n(def tail 1)")
    (isaac.tool.tools-steps/tool-called "grep" {:headers ["pattern" "target"], :rows [["path" "src"] ["-C" "1"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["(defn before [x] x)"] ["(defn target [x] x)"] ["(defn after [x] x)"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "(ns core)")
    (isaac.tool.tools-steps/tool-result-not-contains "(def tail 1)")))
