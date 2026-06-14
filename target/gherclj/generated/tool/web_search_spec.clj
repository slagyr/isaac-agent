(ns tool.web-search-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Built-in web_search tool"

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

  (it "web_search returns ranked result entries"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-websearch")
    (isaac.tool.tools-steps/search-query-returns-results "clojure core async" {:headers ["title" "url" "description"], :rows [["core.async guide" "https://clojure.org/async" "Channels and go blocks"] ["Rich Hickey talk" "https://youtu.be/hMIZ9g6ucs" "Intro to core.async"]]})
    (isaac.tool.tools-steps/tool-called "web_search" {:headers ["query" "clojure core async"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["1. core.async guide"] ["https://clojure.org/async"] ["Channels and go blocks"] ["2. Rich Hickey talk"] ["https://youtu.be/hMIZ9g6ucs"] ["Intro to core.async"]]}))

  (it "web_search limits output to num_results"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-websearch")
    (isaac.tool.tools-steps/search-query-returns-results "clojure" {:headers ["title" "url" "description"], :rows [["Guide 1" "https://example.com/1" "snippet 1"] ["Guide 2" "https://example.com/2" "snippet 2"] ["Guide 3" "https://example.com/3" "snippet 3"]]})
    (isaac.tool.tools-steps/tool-called "web_search" {:headers ["query" "clojure"], :rows [["num_results" "2"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-contains "1. Guide 1")
    (isaac.tool.tools-steps/tool-result-contains "2. Guide 2")
    (isaac.tool.tools-steps/tool-result-not-contains "Guide 3"))

  (it "web_search with no matches returns a clear no-results result"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-websearch")
    (isaac.tool.tools-steps/search-query-returns-results "ajshdkajshdakjsh" {:headers ["title" "url" "description"], :rows []})
    (isaac.tool.tools-steps/tool-called "web_search" {:headers ["query" "ajshdkajshdakjsh"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["no results"]]}))

  (it "web_search without configured API key returns a config error"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-websearch")
    (isaac.tool.tools-steps/tool-called "web_search" {:headers ["query" "clojure"], :rows []})
    (isaac.tool.tools-steps/tool-result-is-error)
    (isaac.tool.tools-steps/tool-result-contains "api_key")
    (isaac.tool.tools-steps/tool-result-contains "web_search")))
