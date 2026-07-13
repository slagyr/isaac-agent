(ns isaac.llm.api.responses
  "OpenAI Responses API adapter — oauth-device providers (chatgpt).
   Uses /responses endpoint with OAuth Bearer auth."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.effort :as effort]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.api.openai.shared :as shared]
    [isaac.llm.http :as llm-http]
    [isaac.llm.prompt.builder :as prompt]
    [isaac.logger :as log]))

(defn- ->responses-output [content]
  (cond
    (string? content) content
    (nil? content)    ""
    :else             (json/generate-string content)))

(defn- sanitize-responses-message [{:keys [call_id content output role tool_call_id tool_calls type]}]
  (cond
    (= "function_call_output" type)
    {:type    "function_call_output"
     :call_id call_id
     :output  (->responses-output output)}

    (= "tool" role)
    {:type    "function_call_output"
     :call_id tool_call_id
     :output  (->responses-output content)}

    (and (= "assistant" role) (seq tool_calls))
    (mapv (fn [tc]
            {:type      "function_call"
             :call_id   (or (:id tc) (get-in tc [:function :id]))
             :name      (or (:name tc) (get-in tc [:function :name]))
             :arguments (or (when (string? (:arguments tc)) (:arguments tc))
                            (get-in tc [:function :arguments])
                            "{}")})
          tool_calls)

    :else
    {:role role :content content}))

(defn- truthy-stateful? [v]
  (cond
    (true? v)  true
    (false? v) false
    (nil? v)   false
    (string? v) (contains? #{"true" "1" "yes"} (str/lower-case v))
    :else      (boolean v)))

(defn- responses-request-base [model input store?]
  {:model model
   :input input
   :store (boolean store?)})

(defn- ->responses-request
  ([request] (->responses-request request nil))
  ([{:keys [model messages system tools stateful previous_response_id previous-response-id]} provider-cfg]
   (let [store?       (truthy-stateful? (if (nil? stateful)
                                          (get provider-cfg :stateful)
                                          stateful))
         prev-id      (or previous_response_id previous-response-id)
         chained?     (and store? (not (str/blank? (str prev-id))))
         all-messages (cond->> messages
                         system (into [{:role "system" :content system}]))
         instructions (->> all-messages
                           (filter #(= "system" (:role %)))
                           (map :content)
                           (remove str/blank?)
                           (str/join "

"))
         input        (if chained?
                        (->> all-messages
                             (remove #(= "system" (:role %)))
                             (filter #(or (= "tool" (:role %))
                                          (= "function_call_output" (:type %))))
                             (mapcat #(let [r (sanitize-responses-message %)]
                                        (if (vector? r) r [r])))
                             vec)
                        (->> all-messages
                             (remove #(= "system" (:role %)))
                             (mapcat #(let [r (sanitize-responses-message %)]
                                        (if (vector? r) r [r])))
                             vec))]
     (cond-> (responses-request-base model input store?)
       (seq tools)                       (assoc :tools tools)
       (and (not chained?) (not (str/blank? instructions))) (assoc :instructions instructions)
       chained?                          (assoc :previous_response_id (str prev-id))))))

(defn- ->codex-responses-request [request provider-cfg]
  (let [base  (->responses-request request provider-cfg)
        base  (if (contains? base :instructions) base (assoc base :instructions ""))
        level (effort/effort->string (:effort request))]
    (if level
      (assoc base :reasoning {:effort level :summary "auto"})
      base)))

(defn- incomplete-responses-stream? [{:keys [content model response tool-calls]}]
  (and (str/blank? content)
       (nil? model)
       (nil? response)
       (empty? tool-calls)))

(defn- process-responses-sse-event [data accumulated]
  (case (:type data)
    "response.output_text.delta"
    (update accumulated :content str (:delta data))

    "response.output_item.added"
    (let [item (:item data)]
      (if (= "function_call" (:type item))
        (update accumulated :tool-calls conj {:id        (:id item)
                                              :name      (:name item)
                                              :arguments {}
                                              :raw-args  ""})
        accumulated))

    "response.function_call_arguments.delta"
    (update accumulated :tool-calls
            (fn [tool-calls]
              (mapv (fn [tool-call]
                      (if (= (:id tool-call) (:item_id data))
                        (update tool-call :raw-args str (:delta data))
                        tool-call))
                    tool-calls)))

    "response.function_call_arguments.done"
    (update accumulated :tool-calls
            (fn [tool-calls]
              (mapv (fn [tool-call]
                      (if (= (:id tool-call) (:item_id data))
                        (let [raw-args (:raw-args tool-call)]
                          (-> tool-call
                              (assoc :arguments (if (str/blank? raw-args)
                                                  {}
                                                  (json/parse-string raw-args true)))
                              (dissoc :raw-args)))
                        tool-call))
                    tool-calls)))

    "response.completed"
    (let [response (:response data)]
      (cond-> accumulated
        response          (assoc :response response)
        (:id response)    (assoc :response-id (:id response))
        (:model response) (assoc :model (:model response))
        (:usage response) (assoc :usage (:usage response))))

    "response.created"
    (let [response (:response data)]
      (cond-> accumulated
        (:id response) (assoc :response-id (:id response))))

    accumulated))

(defn- chat-stream-with-responses-api [provider-name config base-url _headers request on-delta]
  (let [url     (str base-url "/responses")
        body    (assoc (->codex-responses-request
                         (cond-> request
                           (and (nil? (:stateful request))
                                (contains? config :stateful))
                           (assoc :stateful (:stateful config)))
                         config)
                       :stream true)
        initial {:role "assistant" :content "" :model nil :usage {} :response nil :tool-calls []}
        send!   (fn []
                  (llm-http/post-sse! url (shared/auth-headers provider-name config) body
                                      (fn [chunk]
                                        (when (= "response.output_text.delta" (:type chunk))
                                          (on-delta {:delta {:text (:delta chunk)}})))
                                      process-responses-sse-event initial (shared/llm-http-opts config)))
        result  (shared/with-oauth-refresh-retry provider-name config send!)]
    (cond
      (:error result)
      result

      (incomplete-responses-stream? result)
      {:error :llm-error
       :message "responses stream ended without response.completed"}

      :else
      (let [tool-calls  (:tool-calls result)
            response    (:response result)
            response-id (or (:response-id result) (:id response))]
        (log/debug :responses/reasoning
                   :model             (:model result)
                   :effort            (get-in response [:reasoning :effort])
                   :summary           (get-in response [:reasoning :summary])
                   :reasoning-tokens  (get-in response [:usage :output_tokens_details :reasoning_tokens])
                   :cached-tokens     (get-in response [:usage :input_tokens_details :cached_tokens]))
        (cond-> {:message    (cond-> {:role "assistant" :content (:content result)}
                                      (seq tool-calls) (assoc :tool_calls (mapv (fn [tc]
                                                                                   {:id       (:id tc)
                                                                                    :type     "function"
                                                                                    :function {:name      (:name tc)
                                                                                               :arguments (:arguments tc)}})
                                                                               tool-calls)))
                 :model      (:model result)
                 :response   response
                 :tool-calls tool-calls
                 :usage      (shared/parse-usage (:usage result))
                 :_headers   (shared/auth-headers provider-name config)}
          response-id (assoc :response-id response-id))))))

(defn chat
  "Send a Responses API request (streams internally; returns accumulated response)."
  [request provider-name cfg]
  (let [base-url (shared/provider-base-url cfg)
        auth-err (shared/missing-auth-error provider-name cfg)]
    (if auth-err
      auth-err
      (chat-stream-with-responses-api provider-name cfg base-url (shared/auth-headers provider-name cfg) request (fn [_] nil)))))

(defn chat-stream
  "Send a streaming Responses API request via SSE."
  [request on-chunk provider-name cfg]
  (let [base-url (shared/provider-base-url cfg)
        auth-err (shared/missing-auth-error provider-name cfg)]
    (if auth-err
      auth-err
      (chat-stream-with-responses-api provider-name cfg base-url (shared/auth-headers provider-name cfg) request on-chunk))))

(deftype ResponsesAPI [provider-name cfg]
  api/Api
  (chat [_ req] (chat req provider-name cfg))
  (chat-stream [_ req on-chunk] (chat-stream req on-chunk provider-name cfg))
  (followup-messages [_ req resp tcs trs] (shared/followup-messages req resp tcs trs))
  (config [_] cfg)
  (display-name [_] provider-name)
  (format-tools [this tools] (when (seq tools) (mapv api/flat-function-tool tools)))
  (build-prompt [this opts]
    (let [raw-tools (:tools opts)
          base      (prompt/build (-> opts
                                      (assoc :filter-fn prompt/filter-messages-openai)
                                      (dissoc :tools)))]
      (cond-> base
        (seq raw-tools) (assoc :tools (api/format-tools this raw-tools))))))

(defn make [name cfg]
  (->ResponsesAPI name cfg))
