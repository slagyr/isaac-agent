(ns isaac.agent.tool.registry
  (:require
    [clojure.string :as str]
    [isaac.agent.bridge.cancellation :as bridge]
    [isaac.agent.bridge.suspend :as suspend]
    [isaac.agent.config.defaults :as defaults]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.logger :as log]
    [isaac.foundation.module.loader :as module-loader]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.tool.fs-bounds :as fs-bounds]
    [isaac.agent.tool.names :as names]
    [isaac.agent.tool.output-cap :as output-cap])
  (:import (java.security MessageDigest)
           (java.util.concurrent ExecutionException)))

;; region ----- State -----

(defn- registry-atom []
  (or (nexus/get :tool-registry)
      (let [registry* (atom {})]
        (nexus/register! [:tool-registry] registry*)
        registry*)))

(defn- allowed-tool? [allowed-tools name]
  (names/allowed? allowed-tools name))

;; endregion ^^^^^ State ^^^^^

;; region ----- Registration -----

(defn register! [{:keys [name] :as tool}]
  (swap! (registry-atom) assoc name tool))

(defn register-tool-entry!
  "Per-entry factory for the :isaac.agent/tools berth (phase 6 of
   the berth epic). The berth processor passes `[tool-id entry-map]`
   for :map-shaped contributions; this fn resolves the entry's
   :factory symbol, applies the user-config slot for the tool, and
   installs the resulting spec into the registry (with :name set
   from tool-id)."
  [[tool-id entry]]
  (let [tool-name (or (names/wire-name tool-id) (name tool-id))
        factory   (some-> (:factory entry) requiring-resolve var-get)
        user-cfg  (or (module-loader/user-config :tools tool-name) {})
        spec      (factory user-cfg)]
    (register! (assoc spec :name tool-name))))

(defn unregister! [name]
  (swap! (registry-atom) dissoc name))

(defn clear! []
  (reset! (registry-atom) {}))

(defn lookup [name]
  (get @(registry-atom) name))

(defn- activate-tool-module! [module-index name]
  ;; Phase 6 (isaac-w7o5): tool installation is a berth-side concern.
  ;; After activating the providing module (for its bootstrap + non-tool
  ;; extensions), call the berth's per-entry factory directly so the
  ;; single tool lands in the registry without paying for a full
  ;; process-manifest-berths! sweep.
  (let [berth-key (or (names/config-token name) (keyword name))]
    (when-let [module-id (or (module-loader/supporting-module-id module-index :isaac.agent/tools berth-key)
                             (module-loader/supporting-module-id module-index :isaac.agent/tools name))]
      (module-loader/activate! module-id module-index)
      (let [entry (or (get-in module-index [module-id :manifest :isaac.agent/tools berth-key])
                      (get-in module-index [module-id :manifest :isaac.agent/tools (keyword name)]))]
        (when entry
          (register-tool-entry! [berth-key entry])))
      (lookup name))))

(defn- tool-providers
  "[[provider-id ensure-sym] ...] contributed to the :isaac.agent/tool-providers
   berth across `module-index`."
  [module-index]
  (for [[_ entry] module-index
        [provider-id {:keys [ensure!]}] (get-in entry [:manifest :isaac.agent/tool-providers])
        :when ensure!]
    [provider-id ensure!]))

(defn- token-namespace [token]
  (some-> (names/config-token token) namespace))

(defn- namespace-registered? [ns-str]
  (some #(= ns-str (token-namespace %)) (keys @(registry-atom))))

(defn- ensure-namespace!
  "Ask each tool provider to register the tools it owns under `ns-str`
   (isaac-vadd: dynamic namespaces such as config-declared MCP servers).
   A provider returns the wire names it registered, or nil to decline.
   Returns the first provider's names, or nil when every provider declines."
  [module-index ns-str]
  (some (fn [[provider-id sym]]
          (try
            (when-let [ensure! (some-> sym requiring-resolve var-get)]
              (seq (ensure! ns-str module-index)))
            (catch Throwable e
              (log/error :tool/provider-failed :provider provider-id :ns ns-str :error (.getMessage e))
              nil)))
        (tool-providers module-index)))

(defn- activate-missing-tool! [module-index name]
  (or (activate-tool-module! module-index name)
      (when-let [ns-str (token-namespace name)]
        (when (ensure-namespace! module-index ns-str)
          (lookup name)))))

(defn ensure-policy-tools!
  "Make every tool a policy names available before it is matched
   (isaac-vadd). A ns/* glob asks the tool providers for its namespace
   when nothing is registered there; an exact token activates its
   :isaac.agent/tools module or asks the providers. No-op without a
   module index."
  [module-index tokens]
  (when module-index
    (doseq [token tokens]
      (if (names/glob-token? token)
        (when-let [ns-str (token-namespace token)]
          (when-not (namespace-registered? ns-str)
            (ensure-namespace! module-index ns-str)))
        (let [wire (or (names/wire-name token) (str token))]
          (when-not (lookup wire)
            (activate-missing-tool! module-index wire)))))))

(defn all-tools
  "With no args, returns every registered tool.
   With an allowed-tools collection, returns only the tools in the allow list.
   A nil allowed-tools with the 1-arity denies every tool (default-deny)."
  ([]
   (vec (vals @(registry-atom))))
  ([allowed-tools]
   (->> (vals @(registry-atom))
         (filter #(allowed-tool? allowed-tools (:name %)))
         vec)))

;; endregion ^^^^^ Registration ^^^^^

;; region ----- Execution -----

(defn- result-metadata [result]
  {:result-chars (count (str result))
   :result-type  (cond
                   (string? result) :string
                   (map? result)    :map
                   (vector? result) :vector
                   (sequential? result) :seq
                   (nil? result)    :nil
                   :else            :other)})

(defn- log-arguments [arguments]
  (into {}
        (keep (fn [[k v]]
                (let [kw (if (string? k) (keyword k) k)]
                  (when (not= kw :session_key)
                    [kw v]))))
        arguments))

(defn- session-key-of [arguments]
  (or (get arguments "session_key")
      (get arguments :session_key)))

(defn- tool-cwd [arguments]
  (fs-bounds/session-workdir (session-key-of arguments)))

(defn- snapshot-caps []
  (let [cfg (or (loader/snapshot "tool output caps — ambient fallback when caller passes no caps") {})]
    {:max-lines  (:max-lines (defaults/tool-caps cfg))
     :max-bytes  (:max-bytes (defaults/tool-caps cfg))
     :timeout-ms (defaults/tool-timeout-ms cfg)}))

;; caps is {:max-lines _ :max-bytes _ :timeout-ms _} resolved from config at
;; the turn boundary and threaded in as a value; nil falls back to the
;; ambient snapshot.
(defn- cap-output [caps s]
  (let [caps*     (or caps (snapshot-caps))
        max-lines (or (:max-lines caps*) output-cap/default-max-output-lines)
        max-bytes (or (:max-bytes caps*) output-cap/default-max-output-bytes)]
    (output-cap/cap-result (str s) max-lines max-bytes)))

;; region ----- Tool-call deadline (isaac-4g2k) -----

(def default-timeout-ms
  "Hard-coded fallback deadline (ms) for a tool call when nothing else
   declares one: not the tool, not the crew, not :defaults :tools :timeout-ms."
  60000)

(defn- declared-timeout-ms
  "A tool's own :timeout-ms — a number, or a fn (or #'var) of the raw call
   arguments (exec__run computes its own `timeout` argument plus a safety
   margin)."
  [tool arguments]
  (let [t (:timeout-ms tool)]
    (cond
      (number? t) t
      (ifn? t)    (t arguments)
      :else       nil)))

(defn- effective-timeout-ms
  "Highest priority first: the tool's own declared default, then the
   config-resolved value threaded in via caps (crew override, else the
   global default), then the registry's hard-coded fallback."
  [tool arguments caps]
  (or (declared-timeout-ms tool arguments)
      (:timeout-ms caps)
      default-timeout-ms))

(defn- await-tool-call
  "Waits for `fut` up to `timeout-ms`, also watching turn cancellation for
   `session-key`. Returns {:outcome :done :value v}, {:outcome :timed-out},
   or {:outcome :cancelled}.

   Suspend (isaac-2xj5) reuses the same cancellation path to let a tool's own
   cooperative handling run (exec kills its process), but deliberately lets a
   stray tool keep running past its cap so the turn marker can be stamped
   :unclean — a session-suspended cancel is not a reason for THIS generic
   wrapper to abandon early; only a real user/turn cancel is.

   On a non-:done outcome the handler is abandoned: `future-cancel` is a
   best-effort interrupt of its thread, but a blocked call that ignores
   interrupt (a dataless iCloud file read, for example) keeps running
   regardless — the turn moves on without waiting for it."
  [fut timeout-ms session-key]
  (let [cancelled? (atom false)
        deadline   (+ (System/currentTimeMillis) (max 0 (long timeout-ms)))]
    (bridge/on-cancel! session-key
                      #(when-not (suspend/session-suspended? session-key)
                         (reset! cancelled? true)))
    (loop []
      (cond
        (realized? fut)
        {:outcome :done :value @fut}

        @cancelled?
        (do (future-cancel fut) {:outcome :cancelled})

        (>= (System/currentTimeMillis) deadline)
        (do (future-cancel fut) {:outcome :timed-out})

        :else
        (do (Thread/sleep 5)
            (recur))))))

;; endregion ^^^^^ Tool-call deadline (isaac-4g2k) ^^^^^

(defn- unknown-tool-error [name]
  (log/error :tool/execute-failed :tool name :error (str "unknown tool: " name))
  {:isError true :error (str "unknown tool: " name)})

(defn- sha-256 [s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn- arg [arguments k]
  (or (get arguments k)
      (get arguments (keyword k))
      (get arguments (name k))))

(defn- cache-key [name arguments]
  (case name
    "fs__read"
    [:read (arg arguments "file_path") (arg arguments "offset") (arg arguments "limit")]

    "fs__grep"
    [:grep (arg arguments "pattern") (arg arguments "path") (arg arguments "glob") (arg arguments "include")]

    "prompt__load"
    [:prompt (arg arguments "name") (arg arguments "kind") (arg arguments "resource")]

    nil))

(defn- invalidate-file! [cache file-path]
  (when (and cache file-path)
    (swap! cache (fn [m]
                   (into {}
                         (remove (fn [[k _]]
                                   (and (vector? k)
                                        (contains? #{:read :grep} (first k))
                                        (or (= file-path (second k))
                                            (and (= :grep (first k))
                                                 (let [path (nth k 2)]
                                                   (or (nil? path) (= "." path) (str/starts-with? (str file-path) (str path)))))))))
                         m)))))

(defn- invalidate-on-edit! [name arguments cache]
  (when cache
    (case name
      ("fs__edit" "fs__write")
      (invalidate-file! cache (arg arguments "file_path"))

      "fs__multi_edit"
      (doseq [entry (or (arg arguments "edits") [])]
        (invalidate-file! cache (arg entry "file_path")))

      nil)))

(defn- cache-stub [name arguments cycle]
  (if (= "prompt__load" name)
    (str (arg arguments "name") " already in context since cycle " cycle)
    (str "unchanged since cycle " cycle " — already in your context")))

(defn- maybe-cache-hit [name arguments result cache cycle]
  (let [key (cache-key name arguments)]
    (if (or (nil? cache) (nil? key) (:isError result) (nil? (:result result)) (map? (:result result)))
      result
      (let [hash (sha-256 (:result result))
            prior (get @cache key)]
        (if (and prior (= hash (:hash prior)))
          (do
            (log/info :tool/cache-hit :tool name :cycle (:cycle prior))
            (assoc result :result (cache-stub name arguments (:cycle prior))))
          (do
            (swap! cache assoc key {:hash hash :cycle (or cycle 1)})
            result))))))

(defn clear-window-cache!
  "Empty the per-window tool cache. Called on compaction and at turn end."
  [cache]
  (when cache
    (reset! cache {})))

(defn- builtin-tool? [tool]
  (:builtin? tool))

(defn- handler-arguments [tool arguments]
  (if (builtin-tool? tool)
    arguments
    (dissoc arguments "crew" "session_key" "state_dir" :crew :session_key :state_dir)))

(defn- run-handler-result [name caps cwd log-args value]
  ;; Post-processing shared by the synchronous and future-backed call paths:
  ;; isError / nil / {:error :cancelled} pass through verbatim; everything
  ;; else gets capped and logged.
  (cond
    (:isError value)
    (do (log/error :tool/execute-failed :tool name :arguments log-args :cwd cwd :error (:error value))
        value)

    (nil? value)
    (do (log/error :tool/execute-failed :tool name :arguments log-args :cwd cwd :error "tool returned nil")
        {:isError true :error "tool returned nil"})

    (and (map? value) (= :cancelled (:error value)))
    value

    (and (map? value) (= "image" (get-in value [:result :type])))
    value

    (and (map? value) (contains? value :result))
    (let [capped (cap-output caps (:result value))]
      (log/debug :tool/result (assoc (result-metadata capped) :tool name :cwd cwd))
      (assoc value :result capped))

    :else
    (let [capped (cap-output caps value)]
      (log/debug :tool/result (assoc (result-metadata capped) :tool name :cwd cwd))
      {:result capped})))

(defn- run-handler [name arguments caps]
  (if-let [tool (lookup name)]
    (let [cwd         (tool-cwd arguments)
          log-args    (log-arguments arguments)
          caps*       (or caps (snapshot-caps))
          timeout-ms  (effective-timeout-ms tool arguments caps*)
          session-key (session-key-of arguments)]
      (log/debug :tool/start :tool name :arguments log-args :cwd cwd :timeout-ms timeout-ms)
      (try
        (let [fut                     (future ((:handler tool) (handler-arguments tool arguments)))
              {:keys [outcome value]} (await-tool-call fut timeout-ms session-key)]
          (case outcome
            :timed-out
            (do (log/warn :tool/timed-out :tool name :timeout-ms timeout-ms)
                (log/warn :tool/abandoned :tool name :reason :timed-out)
                {:isError true :error (str "timed out after " timeout-ms "ms")})

            :cancelled
            (do (log/warn :tool/abandoned :tool name :reason :cancelled)
                {:error :cancelled})

            :done
            (run-handler-result name caps* cwd log-args value)))
        (catch ExecutionException e
          (let [cause (or (.getCause e) e)]
            (log/error :tool/execute-failed :tool name :arguments log-args :cwd cwd :error (.getMessage cause))
            {:isError true :error (.getMessage cause)}))
        (catch Exception e
          (log/error :tool/execute-failed :tool name :arguments log-args :cwd cwd :error (.getMessage e))
          {:isError true :error (.getMessage e)})))
    (unknown-tool-error name)))

(defn execute
  ([name arguments]
   (run-handler name arguments nil))
  ([name arguments allowed-tools]
   (execute name arguments allowed-tools nil nil nil nil))
  ([name arguments allowed-tools module-index]
   (execute name arguments allowed-tools module-index nil nil nil))
  ([name arguments allowed-tools module-index caps]
   (execute name arguments allowed-tools module-index caps nil nil))
  ([name arguments allowed-tools module-index caps cache cycle]
   (if (allowed-tool? allowed-tools name)
     (do
       (when (and module-index (not (lookup name)))
         (activate-missing-tool! module-index name))
       (invalidate-on-edit! name arguments cache)
       (let [raw (run-handler name arguments caps)]
         (maybe-cache-hit name arguments raw cache cycle)))
     (unknown-tool-error name))))

(defn present-result
  "Normalize a raw tool execution result to the string payload sent back to the model."
  [{:keys [result error isError] :as raw-result}]
  (cond
    (string? raw-result) raw-result
    isError              (str "Error: " error)
    (contains? raw-result :result) result
    (contains? raw-result :error)  (str "Error: " error)
    :else                (str raw-result)))

(defn tool-fn
  "Returns a function compatible with chat-with-tools that dispatches to the registry."
  ([]
   (fn [name arguments]
     (present-result (execute name arguments))))
  ([allowed-tools]
   (fn [name arguments]
      (present-result (execute name arguments allowed-tools))))
  ([allowed-tools module-index]
   (fn [name arguments]
     (present-result (execute name arguments allowed-tools module-index))))
  ([allowed-tools module-index caps]
   (fn [name arguments]
     (present-result (execute name arguments allowed-tools module-index caps)))))

;; endregion ^^^^^ Execution ^^^^^

;; region ----- Prompt Definitions -----

(defn tool-definitions
  "Returns tool definitions suitable for inclusion in an LLM prompt (no handler fn)."
  ([]
   (mapv #(dissoc % :handler) (all-tools)))
  ([allowed-tools]
   (mapv #(dissoc % :handler) (all-tools allowed-tools)))
  ([allowed-tools module-index]
   (ensure-policy-tools! module-index allowed-tools)
   (mapv #(dissoc % :handler) (all-tools allowed-tools))))

;; endregion ^^^^^ Prompt Definitions ^^^^^
