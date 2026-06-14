(ns isaac.drive.dispatch-spec
  (:require
    [isaac.drive.dispatch :as sut]
    [isaac.fs :as fs]
    [isaac.llm.provider :as llm-provider]
    [isaac.module.loader :as module-loader]
    [isaac.llm.api.responses :as responses]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.providers :as providers]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "dispatch"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [it] (nexus/-with-nested-nexus {:fs (fs/mem-fs)} (it)))

  (after (api/unregister! :test-api))
  (after (sut/clear-last-request!))

  (it "captures the last pre-wire request"
    (let [captured (atom nil)
          p        (reify api/Api
                     (chat [_ request]
                       (reset! captured request)
                       {:message {:role "assistant" :content "ok"} :model "m" :usage {}})
                     (chat-stream [_ _ _] nil)
                     (followup-messages [_ request _ _ _] (:messages request))
                     (config [_] {})
                     (display-name [_] "test")
                     (format-tools [_ _] nil)
                     (build-prompt [_ _] nil))
          request  {:model "echo" :messages [{:role "user" :content "hi"}] :effort 7}]
      (sut/dispatch-chat p request)
      (should= request @captured)
      (should= request (sut/last-request))))

  (it "activates a module when a provider api is missing from the registry"
    (api/unregister! :test-api)
    (module-loader/clear-activations!)
    (let [p (llm-provider/make-provider "test-provider"
                                  {:api          "test-api"
                                   :module-index {:isaac.module.provider-test {:manifest {:isaac.agent/llm-api {:test-api {:factory 'isaac.module.provider-test/make}}}}}})]
      (should= "test-provider" (api/display-name p))
      (should-not-be-nil (api/factory-for :test-api))))

  (context "unknown provider"

    (it "emits :unknown-provider error for an unrecognized provider name"
      (let [p   (llm-provider/make-provider "totally-bogus" {})
            res (api/chat p {})]
        (should= :unknown-provider (:error res))
        (should (clojure.string/includes? (:message res) "unknown provider \"totally-bogus\""))
        (should (clojure.string/includes? (:message res) "configured:"))
        (should (clojure.string/includes? (:message res) "known templates:"))))

    (it "includes a did-you-mean suggestion when the name is close to a known provider"
      (let [p   (llm-provider/make-provider "ollam" {})
            res (api/chat p {})]
        (should= :unknown-provider (:error res))
        (should (clojure.string/includes? (:message res) "did you mean \"ollama\""))))

    (it "omits did-you-mean when no close match exists"
      (let [p   (llm-provider/make-provider "zzzzzzz" {})
            res (api/chat p {})]
        (should= :unknown-provider (:error res))
        (should-not (clojure.string/includes? (:message res) "did you mean"))))

    (it "lists known providers from the manifest"
      (let [p         (llm-provider/make-provider "totally-bogus" {})
            res       (api/chat p {})
            known-set (providers/known-providers)]
        (doseq [provider-name known-set]
          (should (clojure.string/includes? (:message res) provider-name))))))

  (context "normalize-provider defaults"

    (it "merges oauth defaults for real chatgpt provider"
      (let [captured     (atom nil)
            provider-cfg (providers/lookup {:providers {:chatgpt {}}} nil "chatgpt")]
        (with-redefs [responses/chat (fn [_req _provider-name cfg]
                                       (reset! captured cfg)
                                       {:message {:role "assistant" :content "ok"} :model "m" :usage {} :_headers {}})]
          (sut/dispatch-chat (llm-provider/make-provider "chatgpt" provider-cfg)
                             {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}))
        (should= "oauth-device" (:auth @captured))
        (should= "https://chatgpt.com/backend-api/codex" (:base-url @captured))))

    (it "allows user config to override defaults for chatgpt"
      (let [captured     (atom nil)
            provider-cfg (providers/lookup {:providers {:chatgpt {:name "custom-name"}}} nil "chatgpt")]
        (with-redefs [responses/chat (fn [_req _provider-name cfg]
                                       (reset! captured cfg)
                                       {:message {:role "assistant" :content "ok"} :model "m" :usage {} :_headers {}})]
          (sut/dispatch-chat (llm-provider/make-provider "chatgpt" provider-cfg)
                             {:model "gpt-5.4" :messages [{:role "user" :content "hi"}]}))
        (should= "custom-name" (:name @captured))))))
