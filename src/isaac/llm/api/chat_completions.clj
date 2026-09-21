(ns isaac.llm.api.chat-completions
  "OpenAI Chat Completions API adapter — non-oauth providers (openai, grok).
   Uses /chat/completions endpoint with Bearer API-key auth."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.effort :as effort]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.api.openai.shared :as shared]
    [isaac.llm.http :as llm-http]
    [isaac.llm.prompt.builder :as prompt]))

(defn- parse-arguments [arguments]
  (if (string? arguments)
    (try
      {:arguments (json/parse-string arguments true)}
      (catch Exception e
        {:arguments {} :arguments-error (.getMessage e)}))
    {:arguments (or arguments {})}))

(defn- extract-tool-calls [tool-calls]
  (mapv (fn [tc]
          (merge {:id   (or (:id tc) (str (java.util.UUID/randomUUID)))
                  :name (get-in tc [:function :name])}
                 (parse-arguments (get-in tc [:function :arguments]))))
        (or tool-calls [])))

(defn- stop-reason [wire tool-calls]
  (cond
    (seq tool-calls) :tool-use
    (= "stop" wire) :end-turn
    (= "length" wire) :max-tokens
    (= "content_filter" wire) :refused
    :else :other))

(defn process-sse-event
  "Accumulate an OpenAI Chat Completions SSE event into the running state."
  [data accumulated]
  (let [delta (get-in data [:choices 0 :delta])]
    (cond-> accumulated
      (:content delta) (update :content str (:content delta))
      (:model data)    (assoc :model (:model data))
      (:usage data)    (assoc :usage (:usage data))
      (get-in data [:choices 0 :finish_reason])
      (assoc :finish-reason (get-in data [:choices 0 :finish_reason])))))

(def ^:private wire-fields
  "Fields the OpenAI Chat Completions API accepts, mapped to their wire names.
   Isaac hangs its own keys on the request map — :session-key, :stateful,
   :provider, :root, :context-window — and those must never reach the wire: a
   strict server rejects unknown fields outright (Fireworks: \"Extra inputs are
   not permitted\"), and :session-key is session metadata leaving the machine.
   Lenient servers ignoring them is what hid this. (isaac-uxe1)

   :system is deliberately absent — the prompt builder already emits the system
   prompt as a {:role \"system\"} entry in :messages."
  {:model               :model
   :messages            :messages
   :tools               :tools
   :tool-choice         :tool_choice
   :tool_choice         :tool_choice
   :max-tokens          :max_tokens
   :max_tokens          :max_tokens
   :reasoning_effort    :reasoning_effort
   :temperature         :temperature
   :top_p               :top_p
   :stop                :stop
   :n                   :n
   :seed                :seed
   :user                :user
   :response_format     :response_format
   :parallel_tool_calls :parallel_tool_calls
   :frequency_penalty   :frequency_penalty
   :presence_penalty    :presence_penalty
   :stream              :stream})

(defn- wire-body
  "Project the request onto the fields the API actually accepts."
  [request]
  (reduce-kv (fn [m k v]
               (if-let [field (get wire-fields k)]
                 (assoc m field v)
                 m))
             {}
             request))

(defn- chat-with-completions-api [config base-url headers request]
  (let [url     (str base-url "/chat/completions")
        request (if-let [level (effort/effort->string (:effort request))]
                  (-> request (assoc :reasoning_effort level) (dissoc :effort))
                  (dissoc request :effort))
        resp    (llm-http/post-json! url headers (wire-body request) (shared/llm-http-opts config))]
    (if (:error resp)
      (api/normalize-error resp)
      (let [choice     (first (:choices resp))
            msg        (:message choice)
            tool-calls (extract-tool-calls (:tool_calls msg))]
        {:content     (or (:content msg) "")
         :model       (:model resp)
         :tool-calls  tool-calls
         :stop-reason (stop-reason (:finish_reason choice) tool-calls)
         :usage       (shared/parse-usage (:usage resp))
         :_headers    headers}))))

(defn- chat-stream-with-completions-api [config base-url headers request on-chunk]
  (let [url     (str base-url "/chat/completions")
        request (if-let [level (effort/effort->string (:effort request))]
                  (-> request (assoc :reasoning_effort level) (dissoc :effort))
                  (dissoc request :effort))
        body    (wire-body (assoc request :stream true))
        initial {:role "assistant" :content "" :model nil :usage {}}
        result  (llm-http/post-sse! url headers body
                                    (fn [chunk]
                                      (when-let [text (get-in chunk [:choices 0 :delta :content])]
                                        (on-chunk {:text-delta text})))
                                    process-sse-event initial (shared/llm-http-opts config))]
    (if (:error result)
      (api/normalize-error result)
      {:content     (:content result)
       :model       (:model result)
       :tool-calls  []
       :stop-reason (stop-reason (:finish-reason result) [])
       :usage       (shared/parse-usage (:usage result))
       :_headers    headers})))

(defn chat
  "Send a non-streaming Chat Completions request."
  [request provider-name cfg]
  (let [base-url (shared/provider-base-url cfg)
        auth-err (shared/missing-auth-error provider-name cfg)]
    (if auth-err
      auth-err
      (chat-with-completions-api cfg base-url (shared/auth-headers provider-name cfg) request))))

(defn chat-stream
  "Send a streaming Chat Completions request via SSE."
  [request on-chunk provider-name cfg]
  (let [base-url (shared/provider-base-url cfg)
        auth-err (shared/missing-auth-error provider-name cfg)]
    (if auth-err
      auth-err
      (chat-stream-with-completions-api cfg base-url (shared/auth-headers provider-name cfg) request on-chunk))))

(defn followup-messages
  "Build the next iteration's :messages vector for Chat Completions."
  [request response tool-calls tool-results]
  (shared/followup-messages request response tool-calls tool-results))

(deftype ChatCompletionsAPI [provider-name cfg]
  api/Api
  (chat [_ req] (chat req provider-name cfg))
  (chat-stream [_ req on-chunk] (chat-stream req on-chunk provider-name cfg))
  (followup-messages [_ req resp tcs trs] (followup-messages req resp tcs trs))
  (config [_] cfg)
  (display-name [_] provider-name)
  (format-tools [_ tools] (when (seq tools) (mapv api/wrapped-function-tool tools)))
  (build-prompt [_ opts] (prompt/build (assoc opts :filter-fn prompt/filter-messages-openai))))

(defn make [name config]
  (->ChatCompletionsAPI name config))
