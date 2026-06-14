(ns isaac.tool.web-search-spec
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.config.api :as config]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.tool.web-search :as sut]
    [isaac.tool.support :as support]
    [speclj.core :refer :all]))

(describe "Web search tool"
  (around [it]
    (nexus/-with-nexus {:root support/test-dir :fs (fs/mem-fs)}
      (it)))

  (it "returns ranked result entries"
    (let [body   (json/generate-string {:web {:results [{:title "core.async guide"
                                                         :url "https://clojure.org/async"
                                                         :description "Channels and go blocks"}
                                                        {:title "Rich Hickey talk"
                                                         :url "https://youtu.be/hMIZ9g6ucs"
                                                         :description "Intro to core.async"}]}})
          _      (config/dangerously-install-config! {:tools {:web_search {:provider :brave :api-key "brave-key"}}} "spec")
          result (with-redefs [http/get (fn [_ _] {:status 200 :headers {"content-type" "application/json"} :body body})]
                   (sut/web-search-tool {"query" "clojure core async"}))]
      (should-be-nil (:isError result))
      (should (str/includes? (:result result) "1. core.async guide"))
      (should (str/includes? (:result result) "https://clojure.org/async"))
      (should (str/includes? (:result result) "2. Rich Hickey talk"))))

  (it "limits output to num_results"
    (let [body   (json/generate-string {:web {:results [{:title "Guide 1" :url "https://example.com/1" :description "snippet 1"}
                                                        {:title "Guide 2" :url "https://example.com/2" :description "snippet 2"}
                                                        {:title "Guide 3" :url "https://example.com/3" :description "snippet 3"}]}})
          _      (config/dangerously-install-config! {:tools {:web_search {:provider :brave :api-key "brave-key"}}} "spec")
          result (with-redefs [http/get (fn [_ _] {:status 200 :headers {"content-type" "application/json"} :body body})]
                   (sut/web-search-tool {"query" "clojure" "num_results" 2}))]
      (should-be-nil (:isError result))
      (should (str/includes? (:result result) "1. Guide 1"))
      (should (str/includes? (:result result) "2. Guide 2"))
      (should-not (str/includes? (:result result) "Guide 3"))))

  (it "returns no results when the provider returns none"
    (let [body   (json/generate-string {:web {:results []}})
          _      (config/dangerously-install-config! {:tools {:web_search {:provider :brave :api-key "brave-key"}}} "spec")
          result (with-redefs [http/get (fn [_ _] {:status 200 :headers {"content-type" "application/json"} :body body})]
                   (sut/web-search-tool {"query" "ajshdkajshdakjsh"}))]
      (should-be-nil (:isError result))
      (should= "no results" (:result result))))

  (it "returns a config error when no api key is configured"
    (config/dangerously-install-config! {:tools {:web_search {:provider :brave}}} "spec")
    (let [result (sut/web-search-tool {"query" "clojure"})]
      (should (:isError result))
      (should (str/includes? (:error result) "web_search"))
      (should (str/includes? (:error result) "api_key")))))
