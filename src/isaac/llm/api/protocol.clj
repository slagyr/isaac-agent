(ns isaac.llm.api.protocol
  "Protocol for an Api adapter — the gateway to a thinking-engine
   (Anthropic, OpenAI, Ollama, Grover test stub).

   Implementations live alongside their wire code in isaac.llm.api.<name> namespaces.
   The `make` factory in isaac.llm.provider resolves a (name, config)
   pair to an Api instance — it lives there to avoid a cycle, since
   the impl namespaces all require this one for the protocol."
  (:require
    [c3kit.apron.schema :as schema]
    [clojure.string :as str]))

(defprotocol Api
  (chat
    [this request]
    "One-shot LLM call. Returns a provider-neutral response or error map.")

  (chat-stream
    [this request on-chunk]
    "Streaming LLM call. Calls on-chunk per text delta as it arrives.
     Returns the final accumulated response (same shape as chat).")

  (followup-messages
    [this request response tool-calls tool-results]
    "Build the next iteration's :messages vector for the tool loop in
     this api's wire format. Used by isaac.llm.tool-loop/run
     between chat iterations.")

  (config
    [this]
    "Return the bound provider's raw (kebab-case) config map. Used for
     introspection — e.g. `:stream-supports-tool-calls`. The wire
     format used for outbound calls is kept inside the deftype.")

  (display-name
    [this]
    "Name string of the provider that owns this api instance —
     `anthropic`, `chatgpt`, `grover:openai`, etc. Used for
     log lines and observability.")

  (build-prompt
    [this opts]
    "Build a prompt request map for this api from turn opts.
     opts keys: :boot-files :model :soul :transcript :tools :context-window.
     Returns a map with :model :messages and optionally :system :max-tokens :tools.")

  (format-tools
    [this tools]
    "Format tool definitions into this api's wire shape. Returns nil for empty/nil input."))

;; --- Provider-neutral response contract ---

(def prompt-scopes
  "How to read a usage map's :prompt-tokens.

   :request     — the size of the prompt this one request carried. This is the
                  figure the context gauge wants, and the only one it stamps.
   :running-sum — a total: a whole turn's requests, or a stateful chain billed
                  cumulatively. Not a prompt size; the gauge refuses it and
                  warns, because an adapter reporting one is an adapter bug.
   :unknown     — the adapter cannot measure this request's prompt. The gauge
                  leaves the last stamp alone and falls back to its own tally.

   Absent means :request — a stateless adapter reports per request by nature
   (isaac-dgod)."
  #{:request :running-sum :unknown})

(def usage
  {:name :usage :type :map
   :description "Token accounting for one request. The adapter owns the arithmetic."
   :schema {:prompt-tokens      {:type :long :validations [schema/required]}
            :prompt-scope       {:type :keyword
                                 :validations [{:validate #(or (nil? %) (prompt-scopes %))
                                                :message "is invalid"}]}
            :output-tokens      {:type :long :validations [schema/required]}
            :reasoning-tokens   {:type :long}
            :cache-read-tokens  {:type :long}
            :cache-write-tokens {:type :long}}})

(def tool-call
  {:name :tool-call :type :map
   :schema {:id              {:type :string :validations [schema/required]}
            :name            {:type :string :validations [schema/required]}
            :arguments       {:type :map :validations [schema/required]}
            :arguments-error {:type :string}}})

(def stop-reasons #{:end-turn :tool-use :max-tokens :cancelled :refused :other})

;; :summary is optional. Providers that stream thinking (claude-cli, Anthropic
;; extended thinking) can emit a reasoning block whose deltas carry no
;; summarizable text; a blank summary is metadata, never a failed turn
;; (isaac-ddls).
(def reasoning
  {:name :reasoning :type :map
   :schema {:summary {:type :string}}})

(def response
  {:name :api-response :type :map
   :schema {:content       {:type :string
                             :validations [{:validate some? :message "is required"}]}
            :tool-calls    {:type :seq :spec tool-call :validations [schema/required]}
            :stop-reason   {:type :keyword :validations [schema/required] :validate stop-reasons}
            :model         {:type :string :validations [schema/required]}
            :usage         (assoc usage :validations [schema/required])
            :reasoning     reasoning
            :response-id   {:type :string}
            :provider-data {:type :ignore}
            :_headers      {:type :ignore}}})

(def error-kinds
  #{:auth-missing :auth-failed :refresh-failed :connection-refused :timeout :stream-stalled
    :cancelled :context-overflow :rate-limited :api-error :llm-error :provider-contract
    :unknown-provider :unknown})

(def error
  {:name :api-error :type :map
   :schema {:error          {:type :keyword :validations [schema/required] :validate error-kinds}
            :message        {:type :string :validations [schema/required]}
            :status         {:type :long}
            :retry-after-ms {:type :long}
            :usage          usage
            :body           {:type :ignore}}})

(def error-response error)

(def stream-chunk
  {:name :stream-chunk :type :map
   :schema {:text-delta {:type :string} :reasoning-delta {:type :string}}})

(def turn-usage
  {:name :turn-usage :type :map
   :schema {:requests           {:type :long :validations [schema/required]}
            :prompt-tokens      {:type :long :validations [schema/required]}
            :output-tokens      {:type :long :validations [schema/required]}
            :reasoning-tokens   {:type :long}
            :cache-read-tokens  {:type :long}
            :cache-write-tokens {:type :long}}})

(def loop-result
  {:name :loop-result :type :map
   :schema {:response      response
            :tool-calls    {:type :seq :spec tool-call :validations [schema/required]}
            :usage         turn-usage
            :cancelled?    {:type :boolean}
            :loop-request? {:type :boolean}}})

(def unavailable
  {:name :unavailable :type :map
   :schema {:unavailable?   {:type :boolean :validations [schema/required]}
            :retry-after-ms {:type :long :validations [schema/required]}
            :reason         {:type :keyword :validations [schema/required]}
            :provider       {:type :string}}})

(defn error? [value]
  (some? (:error value)))

(defn validate-response [value]
  (schema/validate response value))

(defn validate-error [value]
  (schema/validate error value))

(defn error-message [value]
  (or (:message value)
      (let [body-error (get-in value [:body :error])]
        (cond
          (map? body-error) (or (:message body-error) (pr-str body-error))
          (string? body-error) body-error
          (:body value) (pr-str (:body value))))
      (when-let [kind (:error value)] (name kind))
      "provider error"))

(defn normalize-error [value]
  (let [status  (:status value)
        message (error-message value)
        lower   (str/lower-case message)
        kind    (cond
                  (= 429 status) :rate-limited
                  (and (contains? #{400 413} status)
                       (or (str/includes? lower "context")
                           (str/includes? lower "maximum prompt length")
                           (str/includes? lower "prompt is too long"))) :context-overflow
                  :else (:error value))
        retry   (or (:retry-after-ms value)
                    (some-> (or (:retry-after value)
                                (get-in value [:body :retry_after])
                                (get-in value [:body :retry-after]))
                            long
                            (* 1000)))]
    (cond-> (assoc value :error (or kind :unknown) :message message)
      retry (assoc :retry-after-ms retry))))

(declare ->api)

(defn resolve-api
  "Resolve a (name, config) to its api keyword, or nil if unknown.
   Expects `provider-config` to already include provider defaults — callers
   should run it through `isaac.llm.provider/normalize-pair` first."
  [provider provider-config]
  (some-> (or (:api provider-config)
              (cond
                (= provider "grover")                            "grover"
                (str/starts-with? (or provider "") "anthropic")  "messages"
                (= provider "ollama")                            "ollama"
                (= provider "claude")                            "claude-cli"
                :else                                            nil))
          ->api))

;; --- Registry ---
;;
;; Each provider implementation registers itself by api keyword. The factory
;; takes (name, raw-cfg) and returns an Api instance. Built-in providers
;; self-register at namespace load time; third parties do the same in their
;; own namespace.

(defonce ^:private -registry (atom {}))
(defonce ^:private -built-in-keys (atom #{}))

(defn- ->api [provider-key]
  (cond
    (keyword? provider-key) provider-key
    (string? provider-key)  (keyword provider-key)
    :else                   provider-key))

(defn register!
  "Register an Api factory under the given provider-key keyword.
   factory: (fn [name cfg] -> Api)
   Returns the provider-key keyword for chaining."
  [provider-key factory]
  (let [provider-key (->api provider-key)]
    (swap! -registry assoc provider-key factory)
    provider-key))

(defn- grover-test-registration-enabled? []
  (try
    ((requiring-resolve 'isaac.llm.api.grover/test-registration-enabled?))
    (catch Exception _ false)))

(defn register-api-entry!
  "Per-entry factory for the :isaac.agent/llm-api berth (phase 7 of
   the berth epic). Receives `[api-id entry]`; resolves the entry's
   symbol-valued :factory and registers it under api-id."
  [[api-id entry]]
  (when (or (not= api-id :grover)
            (grover-test-registration-enabled?))
    (register! api-id (some-> (:factory entry) requiring-resolve var-get))))

(defn mark-built-ins!
  "Snapshot the current registry as the built-in set. Called once after all
   built-in providers have registered so module registrations can be cleared
   separately between tests without disturbing the built-ins."
  []
  (reset! -built-in-keys (set (keys @-registry))))

(defn clear-module-registrations!
  "Remove all api factories that were registered after the built-in snapshot.
   Called by module-loader/clear-activations! between feature-test scenarios."
  []
  (swap! -registry #(select-keys % (seq @-built-in-keys))))

((requiring-resolve 'isaac.module.loader/register-handler!)
 :clear-registrations clear-module-registrations!)

(defn unregister!
  "Remove the factory registered for `provider-key`."
  [provider-key]
  (let [provider-key (->api provider-key)]
    (swap! -registry dissoc provider-key)
    provider-key))

(defn factory-for
  "Return the factory registered for `provider-key`, or nil if none."
  [provider-key]
  (get @-registry (->api provider-key)))

(defn registered-apis
  "Return the set of provider keywords that have a factory registered."
  []
  (set (keys @-registry)))

;; --- Tool Shape Helpers ---

(defn wrapped-function-tool
  "Standard OpenAI Chat-Completions / Ollama tool shape: `{:type \"function\"
   :function {:name ..., :description ..., :parameters ...}}`."
  [tool]
  {:type     "function"
   :function {:name        (:name tool)
              :description (:description tool)
              :parameters  (:parameters tool)}})

(defn flat-function-tool
  "OpenAI Responses-API tool shape: `{:type \"function\" :name ...,
   :description ..., :parameters ...}`."
  [tool]
  {:type        "function"
   :name        (:name tool)
   :description (:description tool)
   :parameters  (:parameters tool)})

;; --- Compaction Utilities ---

(defn- content-chars [value]
  (cond
    (string? value) (count value)
    (map? value) (+ (content-chars (:content value))
                    (content-chars (:text value))
                    (content-chars (:arguments value))
                    (content-chars (:messages value))
                    (content-chars (:tools value)))
    (sequential? value) (reduce + 0 (map content-chars value))
    :else 0))

(defn estimate-tokens
  "Estimate token count using content chars/4. Maps are measured from
  message/tool content, never from (str map)."
  [request]
  (let [chars (if (string? request)
                (count request)
                (content-chars request))]
    (max 1 (long (Math/ceil (/ (double (max 0 chars)) 4.0))))))

(defn build-summary-request
  "Build a compaction summary request for the given api instance. When `api`
   is nil (test fallback / api-less call sites), tools are emitted in the
   standard wrapped chat-completions shape."
  [api model system-prompt messages tool-defs]
  {:model    model
   :messages [{:role "system" :content system-prompt}
              {:role "user"   :content (pr-str messages)}]
   :tools    (if api
               (format-tools api tool-defs)
               (when (seq tool-defs) (mapv wrapped-function-tool tool-defs)))})
