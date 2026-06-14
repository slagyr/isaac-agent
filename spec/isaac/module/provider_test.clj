(ns isaac.module.provider-test
  (:require
    [isaac.llm.api.protocol :as llm-api]))

(deftype TestProvider [name cfg]
  llm-api/Api
  (chat [_ _] {:message {:role "assistant" :content "ok"} :model "test" :usage {}})
  (chat-stream [_ _ _] {:message {:role "assistant" :content "ok"} :model "test" :usage {}})
  (followup-messages [_ request _ _ _] (:messages request))
  (config [_] cfg)
  (display-name [_] name)
  (format-tools [_ tools] (when (seq tools) (mapv llm-api/wrapped-function-tool tools)))
  (build-prompt [_ opts] {:model (:model opts) :messages []}))

(defn make [name cfg]
  (->TestProvider name cfg))
