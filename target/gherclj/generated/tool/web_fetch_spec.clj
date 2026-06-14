(ns tool.web-fetch-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Built-in web_fetch tool"

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

  (it "web_fetch returns the body text of a URL"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-webfetch")
    (isaac.tool.tools-steps/url-has-body "\"http://example.local/hello\"" "Hello, world.")
    (isaac.tool.tools-steps/tool-called "web_fetch" {:headers ["url" "http://example.local/hello"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["Hello, world."]]}))

  (it "web_fetch strips script and style blocks"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-webfetch")
    (isaac.tool.tools-steps/url-has-body "\"http://example.local/page\"" "<html><head>\n  <style>body { color: red; font: 12pt Arial; }</style>\n  <script>var secrets = \"tracker\";</script>\n</head><body>\n  <h1>Hello</h1>\n  <p>Main content.</p>\n</body></html>")
    (isaac.tool.tools-steps/tool-called "web_fetch" {:headers ["url" "http://example.local/page"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["Hello"] ["Main content."]]})
    (isaac.tool.tools-steps/tool-result-not-contains "color: red")
    (isaac.tool.tools-steps/tool-result-not-contains "var secrets"))

  (it "web_fetch with format=raw preserves script and style blocks"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-webfetch")
    (isaac.tool.tools-steps/url-has-body "\"http://example.local/page\"" "<script>var secret = 1;</script><h1>Title</h1>")
    (isaac.tool.tools-steps/tool-called "web_fetch" {:headers ["url" "http://example.local/page"], :rows [["format" "raw"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-contains "<script>")
    (isaac.tool.tools-steps/tool-result-contains "var secret")
    (isaac.tool.tools-steps/tool-result-contains "<h1>Title</h1>"))

  (it "web_fetch truncates output at the default line limit"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-webfetch")
    (isaac.tool.tools-steps/default-tool-value-is "\"web_fetch\"" "limit" 3)
    (isaac.tool.tools-steps/url-has-body "\"http://example.local/long\"" "line 1\nline 2\nline 3\nline 4\nline 5")
    (isaac.tool.tools-steps/tool-called "web_fetch" {:headers ["url" "http://example.local/long"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["line 1"] ["line 3"] ["truncated"] ["5"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "line 4"))

  (it "web_fetch refuses binary content types"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-webfetch")
    (isaac.tool.tools-steps/url-responds-with "\"http://example.local/image.png\"" {:headers ["header.content-type" "image/png"], :rows []})
    (isaac.tool.tools-steps/tool-called "web_fetch" {:headers ["url" "http://example.local/image.png"], :rows []})
    (isaac.tool.tools-steps/tool-result-is-error)
    (isaac.tool.tools-steps/tool-result-contains "binary"))

  (it "web_fetch follows redirects"
    (isaac.tool.tools-steps/clean-test-dir "target/test-state-webfetch")
    (isaac.tool.tools-steps/url-responds-with "\"http://example.local/old\"" {:headers ["status" "301"], :rows [["header.location" "http://example.local/new"]]})
    (isaac.tool.tools-steps/url-has-body "\"http://example.local/new\"" "Moved here.")
    (isaac.tool.tools-steps/tool-called "web_fetch" {:headers ["url" "http://example.local/old"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-contains "Moved here")))
