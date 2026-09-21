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

(defn- merge-tool-call-fragment
  "Fold one `delta.tool_calls` fragment into the calls accumulated so far.
   OpenAI streams a tool call in pieces: the opening fragment carries the id,
   type and function name, and later fragments extend `function.arguments` a
   few characters at a time. Every fragment names its `index`, because a model
   may open several calls at once and their fragments interleave — so they
   merge by index, never by blind concatenation (isaac-zg3t)."
  [fragments fragment]
  (let [index (or (:index fragment) 0)
        args  (get-in fragment [:function :arguments])]
    (assoc fragments index
           (cond-> (or (get fragments index) {:index index})
             (:id fragment)   (assoc :id (:id fragment))
             (:type fragment) (assoc :type (:type fragment))
             (get-in fragment [:function :name])
             (assoc-in [:function :name] (get-in fragment [:function :name]))
             (some? args)
             (update-in [:function :arguments] str args)))))

(defn process-sse-event
  "Accumulate an OpenAI Chat Completions SSE event into the running state.
   Tool-call fragments land in :tool-call-fragments, a map of index to the
   partially assembled call; a sorted map keeps them in the order the model
   opened them."
  [data accumulated]
  (let [delta (get-in data [:choices 0 :delta])]
    (cond-> accumulated
      (:content delta) (update :content str (:content delta))

      (seq (:tool_calls delta))
      (update :tool-call-fragments
              #(reduce merge-tool-call-fragment (or % (sorted-map)) (:tool_calls delta)))

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
                                      ;; isaac-zg3t: reasoning_content is the
                                      ;; thinking GLM and friends stream beside
                                      ;; the answer. Surfaced as reckoning, the
                                      ;; same disposition the ollama, messages
                                      ;; and responses adapters already take.
                                      (when-let [reasoning (get-in chunk [:choices 0 :delta :reasoning_content])]
                                        (on-chunk {:reasoning-delta reasoning}))
                                      (when-let [text (get-in chunk [:choices 0 :delta :content])]
                                        (on-chunk {:text-delta text})))
                                    process-sse-event initial (shared/llm-http-opts config))]
    (if (:error result)
      (api/normalize-error result)
      ;; Both paths run the same extractor, so a tool call has the same id,
      ;; name and parsed arguments however it arrived (isaac-zg3t).
      (let [tool-calls (extract-tool-calls (vals (:tool-call-fragments result)))]
        {:content     (:content result)
         :model       (:model result)
         :tool-calls  tool-calls
         :stop-reason (stop-reason (:finish-reason result) tool-calls)
         :usage       (shared/parse-usage (:usage result))
         :_headers    headers}))))

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
