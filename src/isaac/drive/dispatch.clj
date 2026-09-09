(ns isaac.drive.dispatch
  (:require
    [isaac.attention :as attention]
    [isaac.config.loader :as loader]
    [isaac.drive.provider-wall :as provider-wall]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.registry :as registry]
    [isaac.llm.tool-loop :as tool-loop]
    [isaac.logger :as log]))

(def built-in-providers registry/built-in-providers)

(defonce ^:private last-request* (atom nil))

(defn last-request []
  @last-request*)

(defn clear-last-request! []
  (reset! last-request* nil))

(defn- response-preview [result]
  (let [content    (or (get-in result [:message :content])
                       (get-in result [:response :message :content]))
        tool-calls (or (get-in result [:message :tool_calls])
                       (get-in result [:response :message :tool_calls]))]
    (cond-> {}
      (string? content) (assoc :content-chars (count content))
      tool-calls (assoc :tool-calls-count (count tool-calls)))))

(defn- broken-provider-error? [result]
  (contains? #{:api-error :llm-error} (:error result)))

(defn- result-message [result]
  (or (:message result)
      (let [body       (:body result)
            body-error (:error body)]
        (cond
          (map? body-error) (or (:message body-error) (str body-error))
          (string? body-error) body-error))))

(defn- request-session [p request]
  (or (:session-key request)
      (:session request)
      (:session-name request)
      (get (api/config p) :session-key)))

(defn- maybe-notify-broken! [p provider request result]
  (when (and (broken-provider-error? result)
             (nil? (provider-wall/classify result (loader/snapshot "dispatch broken-provider") provider)))
    (attention/maybe-notify-provider-broken!
      (loader/snapshot "dispatch broken-provider")
      {:provider provider
       :model    (or (:model request) (:model result))
       :session  (request-session p request)
       :message  (result-message result)})))

(defn- log-dispatch-result [p provider request result error-event response-event]
  (if (:error result)
    (do
      (log/error error-event :provider provider :error (:error result) :status (:status result))
      (maybe-notify-broken! p provider request result))
    (log/debug response-event (merge {:provider provider :model (:model result)}
                                     (response-preview result))))
  result)

(defn dispatch-chat [p request]
  (let [name (api/display-name p)]
    (reset! last-request* request)
    (log/debug :chat/request :provider name :model (:model request))
    (log-dispatch-result p name request (api/chat p request) :chat/error :chat/response)))

(defn dispatch-chat-stream [p request on-chunk]
  (let [name (api/display-name p)]
    (reset! last-request* request)
    (log/debug :chat/stream-request :provider name :model (:model request))
    (log-dispatch-result p name request (api/chat-stream p request on-chunk)
                         :chat/stream-error :chat/stream-response)))

(defn dispatch-chat-with-tools
  "Run a tool-call loop for this api. Composed from Api/chat
   and Api/followup-messages."
  [p request tool-fn]
  (let [name (api/display-name p)]
    (reset! last-request* request)
    (log/debug :chat/request-with-tools :provider name :model (:model request))
    (log-dispatch-result p name request
                         (tool-loop/run #(api/chat p %)
                                        #(api/followup-messages p %1 %2 %3 %4)
                                        request
                                        tool-fn)
                         :chat/error :chat/response)))
