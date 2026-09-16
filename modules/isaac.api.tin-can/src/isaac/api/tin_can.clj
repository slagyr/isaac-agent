(ns isaac.api.tin-can
  (:require
    [isaac.llm.api.protocol :as api]))

(defn- content-text [content]
  (cond
    (string? content) content
    (sequential? content) (apply str (keep (fn [b] (when (= "text" (:type b)) (:text b))) content))
    :else (str content)))

(defn- last-user-content [request]
  (let [msg (->> (:messages request)
                 (filter #(= "user" (:role %)))
                 last)]
    (content-text (:content msg))))

(deftype TinCan [name cfg]
  api/Api
  (chat [_ request]
    {:content     (str "tin-can heard: " (last-user-content request))
     :model       "tin-can"
     :stop-reason :end-turn
     :tool-calls  []
     :usage       {:prompt-tokens 1 :output-tokens 1}})
  (chat-stream [this request on-chunk]
    (let [result (api/chat this request)]
      (on-chunk {:text-delta (:content result)})
      result))
  (followup-messages [_ request _ _ _]
    (:messages request))
  (config [_] cfg)
  (display-name [_] name)
  (format-tools [_ tools] (when (seq tools) (mapv api/wrapped-function-tool tools)))
  (build-prompt [_ opts]
    {:model    (:model opts)
     :messages (into [] (keep (fn [e]
                                (if (contains? e :type)
                                  (case (:type e)
                                    "message" (:message e)
                                    nil)
                                  e))
                              (:transcript opts)))}))

(defn make [name cfg]
  (TinCan. name cfg))
