(ns isaac.llm.api.ollama
  (:require
    [isaac.llm.api.protocol :as api]
    [isaac.llm.followup :as followup]
    [isaac.llm.http :as llm-http]
    [isaac.llm.prompt.builder :as prompt]))

;; region ----- Effort Translation -----

(defn- effort->think [effort think-mode]
  (case (or think-mode :bool)
    :bool   (when (some? effort) (pos? effort))
    :levels (cond
              (or (nil? effort) (zero? effort)) nil
              (<= 1 effort 3)                   "low"
              (<= 4 effort 6)                   "medium"
              :else                             "high")))

;; endregion ^^^^^ Effort Translation ^^^^^

;; region ----- Public API -----

(def ^:private default-headers {"Content-Type" "application/json"})

(def ^:private default-timeout 300000)

(defn- http-opts [cfg]
  (cond-> {:timeout (or (:timeout cfg) default-timeout)}
    (:session-key cfg)            (assoc :session-key (:session-key cfg))
    (:simulate-provider cfg)      (assoc :simulate-provider (:simulate-provider cfg))
    (:stream-idle-timeout-ms cfg) (assoc :stream-idle-timeout-ms (:stream-idle-timeout-ms cfg))
    (:retry-after-ms cfg)         (assoc :retry-after-ms (:retry-after-ms cfg))))

(defn- parse-tool-calls [tool-calls]
  (mapv (fn [tool-call]
          {:id        (or (:id tool-call) (str (java.util.UUID/randomUUID)))
           :name      (get-in tool-call [:function :name])
           :arguments (or (get-in tool-call [:function :arguments]) {})})
        (or tool-calls [])))

(defn- stop-reason [wire tool-calls]
  (cond
    (seq tool-calls) :tool-use
    (= "stop" wire) :end-turn
    (= "length" wire) :max-tokens
    :else :other))

(defn- normalize-response [response]
  (if (:error response)
    (api/normalize-error response)
    (let [tool-calls (parse-tool-calls (get-in response [:message :tool_calls]))]
      {:content     (let [content (get-in response [:message :content])]
                       (if (vector? content) (apply str content) (or content "")))
       :model       (:model response)
       :tool-calls  tool-calls
       :stop-reason (stop-reason (or (:wire-stop-reason response) (:done_reason response)) tool-calls)
       :usage       {:prompt-tokens (or (:prompt_eval_count response) 0)
                     :output-tokens (or (:eval_count response) 0)}})))

(def ^:private wire-fields
  "Top-level fields Ollama's native /api/chat accepts. Isaac's own keys —
   :session-key, :provider, :root, :context-window, :stateful — must not reach
   the wire; :session-key is session metadata leaving the machine (isaac-uxe1).

   :max-tokens is absent because it is not a top-level Ollama field: its
   equivalent is options.num_predict, and wiring Isaac's budget into :options
   belongs with isaac-lrqo (which covers options.num_ctx). It was being sent as
   an unknown field and silently ignored, so dropping it changes nothing."
  #{:model :messages :tools :stream :think :format :options :keep_alive})

(defn- wire-body
  "Project the request onto the fields Ollama actually accepts."
  [request]
  (select-keys request wire-fields))

(defn chat
  "Send a chat request to Ollama. Returns the parsed response or error map."
  [request provider-name cfg]
  (let [url   (str (or (:base-url cfg) "http://localhost:11434") "/api/chat")
        think (effort->think (:effort request) (:think-mode cfg))
        body  (cond-> (-> request wire-body (assoc :stream false))
                (some? think) (assoc :think think))]
    (normalize-response (llm-http/post-json! url default-headers body (http-opts cfg)))))

(defn- fold-chunk
  "Accumulate one NDJSON chunk into the response being built. Ollama spreads a
   reply across chunks: text arrives as deltas, a tool call arrives whole in a
   chunk of its own, and the counts land on the last one. Keeping only the last
   chunk — which is what the reader returns — drops every tool call the model
   made (isaac-ncrz)."
  [acc chunk]
  (cond-> acc
    (seq (get-in chunk [:message :content]))
    (update-in [:message :content] str (get-in chunk [:message :content]))

    (seq (get-in chunk [:message :tool_calls]))
    (update-in [:message :tool_calls] (fnil into []) (get-in chunk [:message :tool_calls]))

    (seq (get-in chunk [:message :thinking]))
    (update-in [:message :thinking] str (get-in chunk [:message :thinking]))

    ;; the closing chunk carries the model, stop reason and token counts
    (:done chunk)
    (merge (dissoc chunk :message))))

(defn chat-stream
  "Send a streaming chat request to Ollama. Calls on-chunk for each chunk.
   Returns the whole response, folded from every chunk, or an error map."
  [request on-chunk provider-name cfg]
  (let [url    (str (or (:base-url cfg) "http://localhost:11434") "/api/chat")
        think  (effort->think (:effort request) (:think-mode cfg))
        body   (cond-> (-> request wire-body (assoc :stream true))
                 (some? think) (assoc :think think))
        folded (atom {:message {:content ""}})
        final  (llm-http/post-ndjson-stream!
                 url default-headers body
                 (fn [chunk]
                   (swap! folded fold-chunk chunk)
                   (when-let [text (get-in chunk [:message :content])]
                     (on-chunk {:text-delta text}))
                   (when-let [reasoning (or (:thinking chunk)
                                            (get-in chunk [:message :thinking]))]
                     (on-chunk {:reasoning-delta reasoning})))
                 (http-opts cfg))]
    (normalize-response
      (if (:error final)
        final
        (merge @folded (select-keys final [:model :done_reason :wire-stop-reason
                                           :prompt_eval_count :eval_count]))))))

;; endregion ^^^^^ Public API ^^^^^

;; region ----- Tool Call Loop -----

(defn followup-messages
  "Build the next iteration's :messages vector for Ollama's /api/chat.
   Assistant message carries the raw tool_calls; tool responses are role=tool."
  [request response tool-calls tool-results]
  (followup/raw-tool-call-followup-messages
    request
    {:role       "assistant"
     :content    (:content response)
     :tool_calls (mapv (fn [tool-call]
                         {:id       (:id tool-call)
                          :type     "function"
                          :function {:name (:name tool-call) :arguments (:arguments tool-call)}})
                       tool-calls)}
    tool-calls
    tool-results))

(deftype OllamaAPI [provider-name cfg]
  api/Api
  (chat [_ req] (chat req provider-name cfg))
  (chat-stream [_ req on-chunk] (chat-stream req on-chunk provider-name cfg))
  (followup-messages [_ req resp tcs trs] (followup-messages req resp tcs trs))
  (config [_] cfg)
  (display-name [_] provider-name)
  (format-tools [_ tools] (when (seq tools) (mapv api/wrapped-function-tool tools)))
  (build-prompt [_ opts] (prompt/build opts)))

(defn make [name cfg]
  (->OllamaAPI name cfg))

;; endregion ^^^^^ Tool Call Loop ^^^^^
