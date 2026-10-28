(ns isaac.agent.drive.request-spec
  (:require
    [isaac.agent.drive.request :as sut]
    [isaac.agent.llm.api.protocol :as api]
    [speclj.core :refer [describe it should= should-be-nil]]))

(describe "build-chat-request"
  (it "passes the nonce through and stamps the session key"
    (let [seen     (atom nil)
          provider (reify api/Api
                     (chat [_ _] nil)
                     (chat-stream [_ _ _] nil)
                     (followup-messages [_ _ _ _ _] nil)
                     (config [_] {})
                     (display-name [_] "test")
                     (format-tools [_ tools] tools)
                     (build-prompt [_ opts]
                       (reset! seen opts)
                       {:model "spark" :messages [{:role "user" :content "hi"}]}))
          request  (sut/build-chat-request provider {:model "spark"
                                                     :nonce "N0NCE-abc123"
                                                     :session-name "greenhouse"
                                                     :transcript []})]
      (should= "N0NCE-abc123" (:nonce @seen))
      (should= "greenhouse" (:session-key request))
      (should= "spark" (:model request))
      (should-be-nil (:stateful request)))))
