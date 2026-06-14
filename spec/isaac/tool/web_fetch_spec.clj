(ns isaac.tool.web-fetch-spec
  (:require
    [babashka.http-client :as http]
    [clojure.string :as str]
    [isaac.tool.web-fetch :as sut]
    [speclj.core :refer :all]))

(describe "Web fetch tool"

  (it "returns the body text of a URL"
    (let [result (with-redefs [http/get (fn [_ _] {:status 200 :headers {"content-type" "text/plain"} :body "Hello, world."})]
                   (sut/web-fetch-tool {"url" "http://example.local/hello"}))]
      (should-be-nil (:isError result))
      (should= 200 (:status result))
      (should= "Hello, world." (:result result))))

  (it "strips script and style blocks from html by default"
    (let [body   "<html><head><style>body { color: red; }</style><script>var secrets = 1;</script></head><body><h1>Hello</h1><p>Main content.</p></body></html>"
          result (with-redefs [http/get (fn [_ _] {:status 200 :headers {"content-type" "text/html"} :body body})]
                   (sut/web-fetch-tool {"url" "http://example.local/page"}))]
      (should-be-nil (:isError result))
      (should (str/includes? (:result result) "Hello"))
      (should (str/includes? (:result result) "Main content."))
      (should-not (str/includes? (:result result) "color: red"))
      (should-not (str/includes? (:result result) "var secrets"))))

  (it "preserves the raw body when format is raw"
    (let [body   "<script>var secret = 1;</script><h1>Title</h1>"
          result (with-redefs [http/get (fn [_ _] {:status 200 :headers {"content-type" "text/html"} :body body})]
                   (sut/web-fetch-tool {"url" "http://example.local/page" "format" "raw"}))]
      (should-be-nil (:isError result))
      (should= body (:result result))))

  (it "truncates output at the default line limit"
    (let [body   (str/join "\n" (map #(str "line " %) (range 1 6)))
          result (binding [sut/*default-limit* 3]
                   (with-redefs [http/get (fn [_ _] {:status 200 :headers {"content-type" "text/plain"} :body body})]
                     (sut/web-fetch-tool {"url" "http://example.local/long"})))]
      (should-be-nil (:isError result))
      (should= "line 1\nline 2\nline 3\nResults truncated. 5 total lines." (:result result))))

  (it "refuses binary content types"
    (let [result (with-redefs [http/get (fn [_ _] {:status 200 :headers {"content-type" "image/png"} :body ""})]
                   (sut/web-fetch-tool {"url" "http://example.local/image.png"}))]
      (should (:isError result))
      (should (re-find #"binary content-type" (:error result)))))

  (it "follows redirects up to the final body"
    (let [result (with-redefs [http/get (fn [url _]
                                          (case url
                                            "http://example.local/old" {:status 301 :headers {"location" "http://example.local/new"} :body ""}
                                            "http://example.local/new" {:status 200 :headers {"content-type" "text/plain"} :body "Moved here."}))]
                   (sut/web-fetch-tool {"url" "http://example.local/old"}))]
      (should-be-nil (:isError result))
      (should= 200 (:status result))
      (should= "Moved here." (:result result)))))
