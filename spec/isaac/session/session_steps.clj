(ns isaac.session.session-steps
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.config.resolve :as resolve]
    [isaac.config.runtime :as runtime]
    [isaac.foundation.root-steps :as froot]
    [isaac.foundation.fs-steps :as ffs]
    [isaac.drive.dispatch :as drive-dispatch]
    [isaac.step-tables :as match]
    [isaac.fs :as fs]
    [isaac.drive.turn :as single-turn]
    [isaac.llm.provider :as llm-provider]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.http]
    [isaac.llm.api.messages :as messages-api]
    [isaac.llm.prompt.builder :as prompt]
    [isaac.llm.tool-loop :as tool-loop]
    [isaac.session.compaction :as session-compaction]
    [isaac.bridge.cancellation :as bridge-cancel]
    [isaac.bridge.core :as bridge]
    [isaac.session.context :as session-ctx]
    [isaac.logger :as log]
    [isaac.comm.memory :as memory-comm]
    [isaac.comm.registry :as comm-registry]
    [isaac.comm.factory :as comm-factory]
    [isaac.slash.registry :as slash-registry]
    [isaac.session.store.spi :as store]
    [isaac.session.store.sidecar :as sidecar-store]
    [isaac.session.store.memory :as memory-store]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory]
    [isaac.spec-helper :as helper]
    [isaac.tool.registry :as tool-registry]))

(helper! isaac.session.session-steps)

(g/before-scenario g/reset!)
(g/before-scenario #(config/dangerously-install-config! nil "spec"))
(g/before-scenario module-loader/clear-activations!)
(g/before-scenario slash-registry/clear!)

;; Capture the real `sidecar-store/create-store` once at load time so we can
;; always restore it after a scenario, regardless of any in-scenario rewrites.
;; Storing in gherclj's per-scenario state was unsafe: scenarios that called
;; `initialize-root!` more than once recaptured the already-stubbed var
;; and then "restored" the stub.
(defonce ^:private real-sidecar-create-store
  (var-get #'sidecar-store/create-store))

(g/after-scenario
  (fn []
    (alter-var-root #'sidecar-store/create-store (constantly real-sidecar-create-store))))

;; The foundation root setup (isaac.foundation.root-steps/initialize-root!)
;; runs the foundation-grade reset; register the server-side teardown so
;; 'an empty Isaac root at' still resets runtime state and installs the
;; in-memory session store.
(froot/register-root-setup-hook!
  (fn [abs-dir]
    (grover/reset-queue!)
    (drive-dispatch/clear-last-request!)
    (bridge-cancel/clear!)
    (reset! comm-registry/*registry* (comm-registry/fresh-registry))
    (when-let [ns-obj (find-ns 'isaac.comm.telly)]
      (remove-ns (ns-name ns-obj))
      ;; clear require bookkeeping and the comm defmethod so the next
      ;; scenario exercises a genuine fresh load (FAIL_ON_LOAD etc.)
      (let [loaded-libs (var-get #'clojure.core/*loaded-libs*)]
        (dosync (alter loaded-libs disj 'isaac.comm.telly)))
      (remove-method comm-factory/create :telly))
    (tool-registry/clear!)
    (single-turn/clear-async-compactions!)
    (let [mem-store (memory-store/create-store abs-dir)]
      (store/register-store! mem-store)
      (alter-var-root #'sidecar-store/create-store (constantly (fn [& _] mem-store))))))

;; region ----- Helpers -----


(defn- root-dir []
  (or (g/get :runtime-root-dir)
      (g/get :root)))

(defn- home-dir []
  (or (g/get :root)
      (some-> (g/get :runtime-root-dir) fs/parent)))

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (mem-fs)}
    (f)))

(defn- commit-feature-config!
  "Load the feature's on-disk config and commit it as the snapshot, so the
   in-flight readers (resolve-behavior etc.) see the current config. Uses the
   non-validating loader + dangerously-install-config! (rather than load-config!)
   because feature fixtures are intentionally partial and need not pass full
   production validation."
  []
  (when-let [root (root-dir)]
    (config/dangerously-install-config!
      (:config (loader/load-config-result {:root root :fs (mem-fs)}))
      "feature: session behavior config")))

(defn- invalidate-feature-config! []
  (g/dissoc! :feature-config))

(defn- notify-config-change! [path]
  (invalidate-feature-config!)
  (when-let [source (g/get :config-change-source)]
    (runtime/notify-path! source path)))

(defn- with-current-time [f]
  (if-let [current-time (g/get :current-time)]
    (binding [memory/*now* current-time]
      (f))
    (f)))

(defn- session-store []
  (or (store/registered-store)
      (sidecar-store/create-store (root-dir))))

(defn- open-session [session-name]
  (when-let [entry (store/get-session (session-store) session-name)]
    (log/info :session/opened :sessionId (:id entry))
    entry))

(defn- list-sessions
  ([]
   (store/list-sessions (session-store)))
  ([crew-id]
   (store/list-sessions-by-agent (session-store) crew-id)))

(defn- most-recent-session []
  (store/most-recent-session (session-store)))

(defn- get-session [session-key]
  (store/get-session (session-store) session-key))

(defn- get-transcript [session-key]
  (store/get-transcript (session-store) session-key))

(defn- get-active-transcript [session-key]
  (store/active-transcript (session-store) session-key))

(defn- open-session! [session-name opts]
  (store/open-session! (session-store) session-name opts))

(defn- update-session! [session-key updates]
  (store/update-session! (session-store) session-key updates))

(defn- append-message! [session-key message]
  (store/append-message! (session-store) session-key message))

(defn- append-error! [session-key error-entry]
  (store/append-error! (session-store) session-key error-entry))

(defn- append-compaction! [session-key compaction]
  (store/append-compaction! (session-store) session-key compaction))

(defn- splice-compaction! [session-key compaction]
  (store/splice-compaction! (session-store) session-key compaction))

(defn- current-session []
  (with-feature-fs
    #(or (when-let [id (g/get :current-key)]
           (get-session id))
         (first (list-sessions)))))

(defn- current-key []
  (or (g/get :current-key)
      (:id (current-session))))

(declare unquote-string)

(declare current-model-config)
(declare current-agent-config)

(defn- current-provider []
  (or (:provider (current-session))
      (:provider (current-model-config))))

(defn- parse-behavior-value [field value]
  (let [value (some-> value unquote-string)]
    (when-not (str/blank? (or value ""))
      (case field
        "compaction"        (edn/read-string value)
        "context-mode"      (edn/read-string value)
        "effort"            (parse-long value)
        "history-retention" (edn/read-string value)
        value))))

(defn- loaded-config []
  (or (g/get :feature-config)
      (let [fs*         (mem-fs)
            load!       #(with-feature-fs (fn [] (:config (loader/load-config-result {:root (root-dir) :fs fs*}))))
            entity-dir? #(with-feature-fs (fn [] (seq (fs/children fs* (str (root-dir) "/config/" %)))))
            cfg         (load!)
            cfg         (if (and (or (entity-dir? "crew") (entity-dir? "models") (entity-dir? "providers"))
                                 (empty? (or (:crew cfg) {}))
                                 (empty? (or (:models cfg) {}))
                                 (empty? (or (:providers cfg) {})))
                          (load!)
                          cfg)]
        (g/assoc! :feature-config cfg)
        cfg)))

(defn- merged-agents []
  (or (:crew (loaded-config)) {}))

(defn- loaded-models []
  (or (:models (loaded-config)) {}))

(defn- provider-config []
  (let [provider-name (current-provider)
        base-name     (first (str/split (str provider-name) #":"))
        agent-cfg     (current-agent-config)
        model-cfg     (current-model-config)]
    (merge (or (get (g/get :provider-configs) provider-name)
               (get (g/get :provider-configs) base-name)
               (resolve/resolve-provider (loaded-config) provider-name))
           (select-keys model-cfg [:enforce-context-window]))))

(defn- current-agent-config []
  (let [agent-id (or (:crew (current-session)) (:agent (current-session)) "main")]
    (get (merged-agents) agent-id)))

(defn- crew-config-path [crew-id]
  (str (root-dir) "/config/crew/" crew-id ".edn"))

(defn- configured-crew-ids []
  (with-feature-fs
    (fn []
      (let [dir (str (root-dir) "/config/crew")]
        (->> (or (fs/children (mem-fs) dir) [])
             (filter #(str/ends-with? % ".edn"))
             (map #(subs % 0 (- (count %) 4)))
             sort
             vec)))))

(defn- active-crew-id []
  (or (:crew (current-session))
      (:agent (current-session))
      (when (= 1 (count (configured-crew-ids)))
        (first (configured-crew-ids)))
      (get-in (loaded-config) [:defaults :crew])
      "main"))

(defn- update-crew-config! [crew-id f]
  (with-feature-fs
    (fn []
      (let [path    (crew-config-path crew-id)
            fs*     (mem-fs)
            current (if (fs/exists? fs* path) (edn/read-string (fs/slurp fs* path)) {})
            updated (f current)]
        (fs/mkdirs fs* (fs/parent path))
        (fs/spit   fs* path (pr-str updated))
        (invalidate-feature-config!)))))

(defn- current-model-config []
  (let [models    (loaded-models)
        session   (current-session)
        agent     (current-agent-config)
        defaults  (:defaults (loaded-config))
        model-id  (or (:model session) (:model agent) (:model defaults))]
    (or (get models model-id)
        (some (fn [[_ cfg]] (when (= model-id (:model cfg)) cfg)) models)
        (first (filter #(= model-id (:model %)) (vals models))))))

(defn- parse-model-content [content]
  (let [trimmed (when (string? content) (str/trim content))]
    (if (and (string? trimmed)
             (str/starts-with? trimmed "[")
             (str/ends-with? trimmed "]"))
      (try
        (let [parsed (edn/read-string trimmed)]
          (if (vector? parsed) parsed content))
        (catch Exception _
          content))
      (some-> content (str/replace "\\n" "\n")))))

(defn- last-llm-request
  "The captured LLM request for prompt assertions. The `the user sends`
   path stores it in :llm-request, but the `isaac is run with` (CLI) path
   drives prompt-cli/run without a postflight to capture it. Fall back to
   the process-global last request recorded by drive.dispatch so CLI-run
   scenarios still see the request the agent actually sent."
  []
  (or (g/get :llm-request)
      (drive-dispatch/last-request)))

(defn- prompt-tools []
  (vec (or (:tools (last-llm-request)) [])))

(defn- prompt-tool-name [tool]
  (or (:name tool)
      (get-in tool [:function :name])))

 (def ^:private queued-response-headers
   #{"model"
     "type"
     "content"
     "tool_call"
     "arguments"
     "wait"
     "usage.input_tokens"
     "usage.output_tokens"
     "usage.cache_creation_input_tokens"
    "usage.output_tokens_details.reasoning_tokens"
    "usage.input_tokens_details.cached_tokens"
    "reasoning.effort"
    "reasoning.summary"})

(defn- header-row? [row]
  (and (= "model" (first row))
       (every? queued-response-headers row)))

(defn- queued-response-row->map [headers row]
  (let [m                 (zipmap headers row)
         tool-name         (or (get m "tool_call") (get m "tool"))
         arguments         (get m "arguments")
         cache-write       (some-> (get m "usage.cache_creation_input_tokens") not-empty parse-long)
         input-tokens      (some-> (get m "usage.input_tokens") not-empty parse-long)
         output-tokens     (some-> (get m "usage.output_tokens") not-empty parse-long)
         reasoning-tokens  (some-> (get m "usage.output_tokens_details.reasoning_tokens") not-empty parse-long)
         cached-tokens     (some-> (get m "usage.input_tokens_details.cached_tokens") not-empty parse-long)
         reasoning-effort  (some-> (get m "reasoning.effort") not-empty)
         reasoning-summary (some-> (get m "reasoning.summary") not-empty)
         wait?             (= "true" (some-> (get m "wait") not-empty str/lower-case))]
    (cond-> {}
      (some? (get m "type"))
      (assoc :type (get m "type"))

      (or (some? (get m "content"))
          (and (not (str/blank? arguments)) (str/blank? tool-name)))
      (assoc :content (parse-model-content (or (get m "content") arguments)))

      (get m "model")
      (assoc :model (let [v (get m "model")] (when-not (str/blank? v) v)))

      (some? (when-not (str/blank? tool-name) tool-name))
      (assoc :tool_call tool-name)

      (and (not (str/blank? tool-name))
           (not (str/blank? arguments)))
      (assoc :arguments (json/parse-string arguments true))

      (or input-tokens output-tokens)
      (assoc :usage {:input_tokens  (or input-tokens 0)
                     :output_tokens (or output-tokens 0)})

      reasoning-tokens
      (assoc-in [:usage :output_tokens_details :reasoning_tokens] reasoning-tokens)

       cached-tokens
       (assoc-in [:usage :input_tokens_details :cached_tokens] cached-tokens)

       cache-write
       (assoc-in [:usage :cache_creation_input_tokens] cache-write)

       wait?
       (assoc :wait true)

       (or reasoning-effort reasoning-summary)
       (assoc :reasoning (cond-> {}
                           reasoning-effort  (assoc :effort reasoning-effort)
                           reasoning-summary (assoc :summary reasoning-summary))))))

(defn- queued-responses [table]
  (loop [headers   (:headers table)
         rows      (:rows table)
         responses []]
    (if-let [row (first rows)]
      (if (header-row? row)
        (recur row (rest rows) responses)
        (recur headers (rest rows) (conj responses (queued-response-row->map headers row))))
      responses)))

(defn- unquote-string [s]
  (if (and (string? s) (<= 2 (count s)) (str/starts-with? s "\"") (str/ends-with? s "\""))
    (subs s 1 (dec (count s)))
    s))

(defn- record-turn-result! [{:keys [output request result]}]
  (let [outbound-requests (or (seq (isaac.llm.http/outbound-requests))
                              (seq (grover/provider-requests)))
        outbound-requests (some-> outbound-requests vec)
        grover-request    (some-> (grover/last-request) (hash-map :body))
        tool-result       (some->> (g/get :channel-events)
                                   deref
                                   (filter #(= "tool-result" (:event %)))
                                   last
                                   :result)
        event-text        (->> (or (some-> (g/get :channel-events) deref) [])
                               (filter #(= "text-chunk" (:event %)))
                               (map :text)
                               (clojure.string/join))
        full-output       (str output event-text)]
    (g/assoc! :dispatch-result result)
    (g/assoc! :llm-result result)
    (g/assoc! :llm-request request)
    (g/assoc! :provider-request (or (last outbound-requests)
                                    (grover/last-provider-request)
                                    grover-request))
    (g/assoc! :outbound-http-requests outbound-requests)
    (g/assoc! :outbound-http-request (or (first outbound-requests)
                                         (grover/last-provider-request)
                                         grover-request))
    (g/assoc! :tool-result tool-result)
    (g/assoc! :output full-output)
    result))

(defn- complete-turn! [turn-result]
  (g/dissoc! :turn-future)
  (record-turn-result! turn-result))

(defn await-turn! []
  (when-let [turn-future (g/get :turn-future)]
    (let [result (deref turn-future 30000 ::timeout)]
      (when (= ::timeout result)
        (throw (ex-info "turn did not complete within 30 seconds" {})))
      (complete-turn! result))))

(defn- await-acp-turn! []
  (when-let [turn-future (g/get :acp-turn-future)]
    (let [result (deref turn-future 2000 ::timeout)]
      (when (= ::timeout result)
        (throw (ex-info "ACP turn did not complete within 2 seconds" {})))
      (g/dissoc! :acp-turn-future)
      result)))

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Given: Infrastructure -----


;; initialize-root! ("an empty Isaac root at" etc.), empty-state,
;; empty-state-directory, in-memory-state moved to
;; isaac.foundation.root-steps. The server-side teardown that initialize-root!
;; runs is registered via froot/register-root-setup-hook! above.

(defn- write-grover-defaults! []
  (let [root (str (root-dir) "/config")
        fs*  (mem-fs)]
    (fs/mkdirs fs* root)
    (fs/spit   fs* (str root "/isaac.edn")
                    (pr-str {:defaults {:crew "main" :model "grover"}}))
    (fs/mkdirs fs* (str root "/models"))
    (fs/mkdirs fs* (str root "/providers"))
    (fs/mkdirs fs* (str root "/crew"))
    (fs/spit   fs* (str root "/models/grover.edn")
                    (pr-str {:model "echo" :provider :grover :context-window 32768}))
    (fs/spit   fs* (str root "/providers/grover.edn")
                    (pr-str {}))
    (fs/spit   fs* (str root "/crew/main.edn")
                    (pr-str {:model :grover :soul "You are Atticus."}))
    (invalidate-feature-config!)))

(defn default-grover-setup []
  (froot/initialize-root! "target/test-state" true)
  (with-feature-fs write-grover-defaults!))

(defn default-grover-setup-in [dir]
  (froot/initialize-root! dir true)
  (with-feature-fs write-grover-defaults!))

(defn crew-has-tools [table]
  (let [tools (mapv (fn [row]
                      (let [t (zipmap (:headers table) row)]
                        {:name        (get t "name")
                         :description (get t "description")
                         :parameters  (json/parse-string (get t "parameters") true)}))
                    (:rows table))
        allow   (mapv (comp keyword :name) tools)
        crew-id (active-crew-id)]
    (g/assoc! :tools tools)
    (doseq [tool tools]
      (when-not (tool-registry/lookup (:name tool))
        (tool-registry/register! (assoc tool :handler (fn [_] {:result "ok"})))))
    (update-crew-config! crew-id #(assoc % :tools {:allow allow}))))

(defn- config-applied-value [v]
  (cond
    (re-matches #"-?\d+" v)                 (parse-long v)
    (= "true" (str/lower-case v))           true
    (= "false" (str/lower-case v))          false
    (or (str/starts-with? v "[")
        (str/starts-with? v "{")
        (str/starts-with? v ":")
        (str/starts-with? v "\""))          (try (edn/read-string v)
                                                 (catch Exception _ v))
    :else                                   v))

(defn- persist-config-entry! [k v]
  (when-let [root (root-dir)]
    (with-feature-fs
      (fn []
        (let [path    (str root "/config/isaac.edn")
              fs*     (mem-fs)
              current (if (fs/exists? fs* path) (edn/read-string (fs/slurp fs* path)) {})
              kpath   (mapv keyword (str/split k #"\."))
              updated (assoc-in current kpath (config-applied-value v))]
          (fs/mkdirs fs* (fs/parent path))
          (fs/spit   fs* path (pr-str updated))
          (invalidate-feature-config!))))))

(defn config-applied
  "Background step `config:`/`And config:` — applies a table of harness
   settings. `log.output` routes the in-memory logger so `the log has
   entries matching:` can read structured entries. `bind-server-port` is
   a server-only concern and is ignored here. Every other dotted key
   (e.g. `prompt-paths`, `crew.main.soul`) is persisted into the on-disk
   `config/isaac.edn` so config-reading steps (the catalog resolver,
   loaded-config-has, …) observe it. The carve dropped this step
   definition, so features whose Background is `config: | … |` silently
   ran without it; `default-grover-setup` happens to set :memory logging
   as a side effect, which is why grover-backed log features still passed."
  [table]
  ;; gherclj headerless tables put the lone key/value pair in :headers
  ;; (e.g. `| prompt-paths | [...] |`), while tables with an explicit
  ;; `| key | value |` header carry the pairs in :rows. Treat the
  ;; :headers as a data pair unless it's the literal key/value header.
  (let [header     (:headers table)
        header-pair (when (and (= 2 (count header))
                               (not (and (= "key" (first header))
                                         (= "value" (second header)))))
                      [header])]
    (doseq [[k v] (map (fn [row] [(first row) (second row)])
                       (concat header-pair (:rows table)))]
      (cond
      ;; gherclj passes a flattened key/value table; a stray header row
      ;; ("key"/"value") can sneak in when two tables are concatenated.
      (or (str/blank? (str k)) (= "key" k))
      nil

      (= "log.output" k)
      (do (log/set-output! (keyword v))
          (log/clear-entries!))

      (= "bind-server-port" k)
      nil

      :else
      (persist-config-entry! k v)))))

;; Agent-local hot-reload wiring. The server-tier `the Isaac server is
;; started` step (which owns the async config watcher loop) lives in
;; isaac-server, off the agent's classpath. We register a single
;; foundation post-write hook at load time that — when a scenario has
;; "started the server" by setting :config-change-source — drives a
;; synchronous reload through the agent's own runtime/reload! whenever a
;; config file is written. This exercises the reload / parse-rejection /
;; validation-rejection paths without booting a full server.
(ffs/register-post-write-hook!
  (fn [path]
    (when-let [source (g/get :config-change-source)]
      (let [root (g/get :server-root)
            fs*  (mem-fs)]
        (nexus/-with-nested-nexus {:fs fs*}
          (runtime/notify-path! source path)
          (loop []
            (when-let [rel (runtime/poll! source)]
              (let [comm-reg @comm-registry/*registry*]
                (runtime/reload! {:root          root
                                  :fs            fs*
                                  :old-config    (loader/snapshot "feature: reload old-config")
                                  :comm-registry comm-reg
                                  :registries    [comm-reg]
                                  :host          {:module-index (module-loader/builtin-index)}
                                  :path          rel}))
              (recur))))))))

(defn server-started
  "Agent-local stand-in for the server-tier `the Isaac server is started`
   Background step. Commits the on-disk config as the live snapshot and
   arms the load-time post-write hook by storing a memory change-source as
   :config-change-source. Reload itself is driven synchronously by that
   hook (see above) on each subsequent config write."
  []
  (let [root (root-dir)
        fs*  (mem-fs)]
    ;; Commit the starting config so rejected reloads leave the prior
    ;; snapshot in place for `the loaded config has:` to read.
    (config/dangerously-install-config!
      (:config (loader/load-config-result {:root root :fs fs*}))
      "feature: Isaac server started")
    (g/assoc! :server-root root)
    (g/assoc! :config-change-source (runtime/memory-source root))))

(defn crew-tool-allow [crew-id tools-str]
  (with-feature-fs
    (fn []
      (let [allow (->> (str/split tools-str #",")
                       (map str/trim)
                       (remove str/blank?)
                       (mapv keyword))]
        (update-crew-config! crew-id #(assoc % :tools {:allow allow}))))))

(defn ollama-server-running []
  (g/update! :provider-configs
             (fn [m] (assoc (or m {}) "ollama" {:base-url "http://localhost:11434"}))))

(defn ollama-model-available [_model]
  nil)

(defn ollama-server-not-running []
  (g/update! :provider-configs
             (fn [m] (assoc (or m {}) "ollama" {:base-url "http://localhost:99999"}))))

(defn responses-queued [table]
  (grover/reset-queue!)
  (let [responses (queued-responses table)]
    (grover/enqueue! responses)))

(defn tool-loop-max-is [n]
  (g/assoc! :tool-loop-max-loops n))

(defn llm-response-delayed [_seconds]
  (grover/enable-delay!))

;; endregion ^^^^^ Given: Infrastructure ^^^^^

;; region ----- Given: Sessions & Transcripts -----

(defn- format-iso [instant]
  (.format (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss")
           (.atOffset instant java.time.ZoneOffset/UTC)))

(defn- create-session-from-row! [row-map]
  (with-feature-fs
    (fn []
      (commit-feature-config!)
      (let [name        (get row-map "name")
            agent       (or (get row-map "crew")
                            (get row-map "agent")
                            (let [prefix (first (str/split name #"-" 2))]
                              (when (contains? (merged-agents) prefix) prefix)))
            origin-kind (get row-map "origin.kind")
            origin-name (get row-map "origin.name")
            origin      (when origin-kind
                          (cond-> {:kind origin-kind}
                            origin-name (assoc :name origin-name)))
            history-retention (some-> (get row-map "history-retention") keyword)
            tags        (some-> (get row-map "tags") edn/read-string)
            entry       (or (open-session name)
                            (open-session! name {:crew agent :agent agent :cwd (root-dir)
                                                 :nonce (get row-map "nonce")
                                                 :tags tags
                                                 :history-retention history-retention
                                                  :origin origin}))
            compaction  (cond-> {}
                           (get row-map "compaction.strategy")  (assoc :strategy (keyword (get row-map "compaction.strategy")))
                           (get row-map "compaction.threshold") (assoc :threshold (Double/parseDouble (get row-map "compaction.threshold")))
                           (get row-map "compaction.head")      (assoc :head (Double/parseDouble (get row-map "compaction.head")))
                           (get row-map "compaction.consecutive-failures")
                           (assoc :consecutive-failures (parse-long (get row-map "compaction.consecutive-failures")))
                           (or (get row-map "compaction.async?")
                               (get row-map "compaction.async"))
                           (assoc :async? (= "true" (or (get row-map "compaction.async?")
                                                         (get row-map "compaction.async")))))
            now-str     (when-let [t (g/get :current-time)] (format-iso t))
             updates     (cond-> {}
                           (or (get row-map "updated-at") now-str) (assoc :updated-at (or (get row-map "updated-at") now-str))
                           (or (get row-map "createdAt") now-str) (assoc :createdAt (or (get row-map "createdAt") now-str))
                          (get row-map "model")        (assoc :model (get row-map "model"))
                          (get row-map "cwd")          (assoc :cwd (let [cwd (get row-map "cwd")]
                                                                      (if (str/starts-with? cwd "/")
                                                                        cwd
                                                                        (str (root-dir) "/" cwd))))
                           (get row-map "total-tokens")  (assoc :total-tokens (parse-long (get row-map "total-tokens")))
                           (get row-map "last-input-tokens") (assoc :last-input-tokens (parse-long (get row-map "last-input-tokens")))
                            (get row-map "input-tokens")  (assoc :input-tokens (parse-long (get row-map "input-tokens")))
                            (get row-map "output-tokens") (assoc :output-tokens (parse-long (get row-map "output-tokens")))
                            (get row-map "compaction-count") (assoc :compaction-count (parse-long (get row-map "compaction-count")))
                            (get row-map "compaction-disabled") (assoc :compaction-disabled (= "true" (get row-map "compaction-disabled")))
                            (get row-map "nonce") (assoc :nonce (get row-map "nonce"))
                            tags (assoc :tags tags)
                            history-retention (assoc :history-retention history-retention)
                            (seq compaction) (assoc :compaction compaction))]
        (let [updates (cond-> updates
                        (and (contains? updates :total-tokens)
                             (not (contains? updates :last-input-tokens)))
                        (assoc :last-input-tokens (:total-tokens updates)))]
         (when (seq updates)
           (update-session! (:id entry) updates))
         (g/assoc! :current-key (:id entry))
         entry)))))

(defn sessions-exist [table]
  (doseq [row (:rows table)]
    (create-session-from-row! (zipmap (:headers table) row))))

(defn session-exists-quoted [session-name]
  (g/should-not-be-nil (with-feature-fs #(get-session session-name))))

(defn session-exists [session-name]
  (when-not (with-feature-fs #(get-session session-name))
    (with-feature-fs #(open-session! session-name {:cwd (root-dir)})))
  (g/should-not-be-nil (with-feature-fs #(get-session session-name))))

(defn session-does-not-exist [session-name]
  (g/should-be-nil (with-feature-fs #(get-session session-name))))

(defn session-is-in-flight [session-name]
  (g/should (store/mark-in-flight! (session-store) session-name)))

(defn session-matches [key-str table]
  (await-turn!)
  (let [session (with-feature-fs #(get-session key-str))
        result  (match/match-object table session)]
    (g/should= [] (:failures result))))

(defn session-has-effort [session-name effort-str]
  (with-feature-fs
    (fn []
      (let [session (get-session session-name)
            n       (parse-long effort-str)]
        (update-session! (:id session) {:effort n})))))


(defn- append-transcript-entry! [key-str row-map]
  (with-feature-fs
    (fn []
      (let [entry-type (get row-map "type" "message")]
        (case entry-type
          "compaction"
          (append-compaction! key-str
                              {:summary          (get row-map "summary")
                               :firstKeptEntryId (get row-map "firstKeptEntryId")
                               :tokensBefore     (some-> (get row-map "tokensBefore") parse-long)})

          "toolCall"
          (append-message! key-str
                           {:role    "assistant"
                            :content [{:type      "toolCall"
                                       :id        (or (get row-map "id") (str (java.util.UUID/randomUUID)))
                                       :name      (get row-map "name")
                                       :arguments (or (some-> (get row-map "arguments") (json/parse-string true)) {})}]})

          "toolResult"
          (append-message! key-str
                           {:role       "toolResult"
                            :toolCallId (or (get row-map "id")
                                            (get row-map "message.id"))
                            :content    (get row-map "message.content")
                            :isError    (= "true" (get row-map "isError"))})

          ;; default: message
          (append-message! key-str
                           (cond-> {:role    (get row-map "message.role")
                                    :content (get row-map "message.content")}
                             (get row-map "message.id")
                             (assoc :id (get row-map "message.id"))

                             (get row-map "message.toolCallId")
                             (assoc :toolCallId (get row-map "message.toolCallId"))

                             (get row-map "tokens")
                             (assoc :tokens (parse-long (get row-map "tokens")))

                             (get row-map "message.model")
                             (assoc :model (get row-map "message.model"))

                             (get row-map "message.provider")
                             (assoc :provider (get row-map "message.provider"))

                             (get row-map "message.crew")
                             (assoc :crew (get row-map "message.crew"))

                             (get row-map "message.api")
                             (assoc :api (get row-map "message.api"))

                             (get row-map "message.stopReason")
                             (assoc :stopReason (get row-map "message.stopReason"))

                             (or (get row-map "message.usage.input")
                                 (get row-map "message.usage.output"))
                             (assoc :usage (cond-> {}
                                             (get row-map "message.usage.input")
                                             (assoc :input (parse-long (get row-map "message.usage.input")))
                                             (get row-map "message.usage.output")
                                             (assoc :output (parse-long (get row-map "message.usage.output")))))

                             (get row-map "message.channel")
                             (assoc :channel (get row-map "message.channel"))

                             (get row-map "message.to")
                             (assoc :to (get row-map "message.to")))))))))

(defn session-has-transcript [key-str table]
  (g/assoc! :current-key key-str)
  (doseq [row (:rows table)]
    (let [row-map (zipmap (:headers table) row)]
      (append-transcript-entry! key-str row-map))))

(defn session-has-error-entry [key-str content]
  (with-feature-fs
    #(append-error! key-str {:content (unquote-string content)
                             :error   ":llm-error"})))

;; endregion ^^^^^ Given: Sessions & Transcripts ^^^^^

;; region ----- When -----

(defn session-created-randomly []
  (let [entry (with-feature-fs #(open-session! nil {:cwd (root-dir)}))]
    (g/assoc! :current-key (:id entry))))

(defn session-created-without-name []
  (let [entry (with-feature-fs #(open-session! nil {:cwd (root-dir)}))]
    (g/assoc! :last-session entry)
    (g/assoc! :current-key (:id entry))))

(defn session-created-with-name-quoted [session-name]
  (try
    (let [entry (with-feature-fs #(open-session! session-name {:cwd (root-dir)}))]
      (g/assoc! :current-key (:id entry))
      (g/dissoc! :error))
    (catch clojure.lang.ExceptionInfo e
      (g/assoc! :error (.getMessage e)))))

(defn session-created-named [session-name]
  (let [entry (with-feature-fs #(open-session! session-name {:cwd (root-dir)}))]
    (g/assoc! :last-session entry)
    (g/assoc! :current-key (:id entry))))

(defn session-created-with-explicit-behavior [session-name field value]
  (let [name  (unquote-string session-name)
        value (parse-behavior-value field value)
        opts  (cond-> {}
                (some? value) (assoc (keyword field) value))
        entry (with-feature-fs #(do (commit-feature-config!)
                                    (session-ctx/create-with-resolved-behavior! name opts)))]
    (g/assoc! :last-session entry)
    (g/assoc! :current-key (:id entry))))

(defn session-exists-with-behavior [session-name field value]
  (let [name  (unquote-string session-name)
        value (parse-behavior-value field value)]
    (with-feature-fs
      (fn []
        (commit-feature-config!)
        (let [entry (or (get-session name)
                        (session-ctx/create-with-resolved-behavior! name {}))]
          (when (some? value)
            (update-session! (:id entry) {(keyword field) value}))
          (g/assoc! :current-key (:id entry)))))))

(defn resolved-behavior-matches [session-name table]
  (let [behavior (with-feature-fs #(do (commit-feature-config!)
                                       (session-ctx/resolve-behavior (unquote-string session-name))))
        result   (match/match-object table behavior)]
    (g/should= [] (:failures result))))

(defn resolved-behavior-has [session-name field value]
  (let [behavior (with-feature-fs #(do (commit-feature-config!)
                                       (session-ctx/resolve-behavior (unquote-string session-name))))
        expected (cond-> (parse-behavior-value field value)
                   (= "compaction" field) ((fn [compaction]
                                              (cond-> compaction
                                                (contains? compaction :head) (update :head double)
                                                (contains? compaction :threshold) (update :threshold double)))))
        actual   (get behavior (keyword field))]
    (if (= "compaction" field)
      (g/should= (merge actual expected) actual)
      (g/should= expected actual))))

(defn session-opened [session-name]
  (let [name  (unquote-string session-name)
        entry (with-feature-fs #(open-session name))]
    (g/assoc! :current-key (:id entry))))

(defn entries-appended [key-str table]
  (g/assoc! :current-key key-str)
  (doseq [row (:rows table)]
    (let [row-map (zipmap (:headers table) row)]
      (append-transcript-entry! key-str row-map))))

(defn compaction-spliced-into-session [key-str table]
  (g/assoc! :current-key key-str)
  (let [row-map         (into {}
                              (map (fn [row]
                                     (let [m (zipmap (:headers table) row)]
                                       [(get m "key") (get m "value")]))
                                   (:rows table)))
        first-kept-idx  (some-> (get row-map "firstKeptIndex") not-empty parse-long)
        compacted-idxs  (some-> (get row-map "compactedIndexes")
                                edn/read-string
                                ((fn [parsed]
                                   (cond
                                     (nil? parsed)                 []
                                     (number? parsed)              [parsed]
                                     (and (= 1 (count parsed))
                                          (sequential? (first parsed))) (vec (first parsed))
                                     :else                         (vec parsed)))))
        tokens-before   (some-> (get row-map "tokensBefore") not-empty parse-long)]
    (with-feature-fs
      (fn []
        (let [transcript    (->> (get-transcript key-str)
                                 (remove #(= "session" (:type %)))
                                 vec)
              first-kept-id (when (some? first-kept-idx)
                              (:id (nth transcript first-kept-idx nil)))
              compacted-ids (mapv (fn [idx]
                                    (or (:id (nth transcript idx nil))
                                        (throw (ex-info "invalid compacted index"
                                                        {:index idx :session key-str}))))
                                  compacted-idxs)]
          (splice-compaction! key-str
                              {:summary           (get row-map "summary")
                               :firstKeptEntryId  first-kept-id
                               :tokensBefore      tokens-before
                               :compactedEntryIds compacted-ids}))))))

(defn user-sends-on-session [content key-str]
  (g/assoc! :current-key key-str)
  (grover/clear-provider-requests!)
  (isaac.llm.http/clear-outbound-requests!)
  (drive-dispatch/clear-last-request!)
  (let [cfg           (loader/normalize-config (loaded-config))
        _             (config/dangerously-install-config! cfg "spec")
        agent-cfg     (current-agent-config)
        model-cfg     (current-model-config)
        provider-name (:provider model-cfg)
        max-loops     (g/get :tool-loop-max-loops)
        events        (atom [])
        channel       (memory-comm/channel events)
        p-cfg         (provider-config)
        send-opts     {:model          (:model model-cfg)
                       :soul           (:soul agent-cfg)
                       :provider       (when provider-name
                                         (llm-provider/make-provider provider-name p-cfg))
                       :context-window (:context-window model-cfg)
                       :origin         {:kind :cli}
                       :comm           channel}]
    (g/assoc! :channel-events events)
    (g/assoc! :memory-comm-events @events)
    (let [existing-turn-future (g/get :turn-future)
          turn-future          (future
                        (let [result (atom nil)
                              output (with-out-str
                                        (with-feature-fs
                                          (fn []
                                            (with-current-time
                                              (fn []
                                                (try
                                                  (reset! result ((fn []
                                                                    (let [request (assoc send-opts :session-key key-str :input content)]
                                                                      (if max-loops
                                                                        (with-redefs [tool-loop/default-max-loops max-loops]
                                                                          (bridge/dispatch! (root-dir) request))
                                                                        (bridge/dispatch! (root-dir) request))))))
                                                  (catch Exception e
                                                    (reset! result {:error :exception :message (.getMessage e)}))))))))]
                          {:output  output
                            :request (or (drive-dispatch/last-request)
                                         (grover/last-request))
                           :result  @result}))]
      (let [result (deref turn-future 50 ::pending)]
        (if (= ::pending result)
          (g/assoc! :turn-future turn-future)
          (do
            (when existing-turn-future
              (g/assoc! :turn-future existing-turn-future))
            (record-turn-result! result)))))
    (g/assoc! :memory-comm-events @events)))

(defn turn-ends-on-session [key-str]
  (when-let [turn-future (g/get :turn-future)]
    (helper/await-condition #(or (realized? turn-future)
                                 (grover/waiting? key-str)))
    (when (and (not (realized? turn-future))
               (grover/waiting? key-str))
      (grover/release-wait! key-str))
    (await-turn!)))

(defn session-in-flight-status [key-str expected]
  (g/should= (= "true" expected) (store/in-flight? (session-store) key-str)))

(defn dispatch-refused-with-reason [reason]
  (g/should= {:dispatched? false :reason (keyword reason)}
             (select-keys (or (g/get :dispatch-result) {}) [:dispatched? :reason])))

(defn turn-cancelled [key-str]
  (bridge-cancel/cancel! key-str)
  (await-turn!))

(defn turn-cancelled-after-n-tool-calls [key-str n]
  (helper/await-condition
    (fn []
      (<= n (->> @(g/get :channel-events)
                 (filter (fn [e] (= "tool-call" (:event e))))
                 count)))
    5000)
  (bridge-cancel/cancel! key-str)
  (await-turn!))

(defn async-compaction-completes [key-str]
  (await-turn!)
  (single-turn/await-async-compaction! key-str))

(defn prompt-built-for-provider [key-str provider]
  (g/assoc! :current-key key-str)
  (with-feature-fs
    (fn []
      (let [session    (get-session key-str)
            agent-id   (or (:crew session) (:agent session) "main")
            cfg        (loaded-config)
            model-cfg  (current-model-config)
            ctx        (assoc (resolve/resolve-crew-context cfg agent-id)
                              :boot-files (session-ctx/read-boot-files (:cwd session)))
            provider'  (unquote-string provider)
            openai?    (or (str/includes? provider' "openai") (str/includes? provider' "grok"))
            builder    (if (str/includes? provider' "anthropic")
                         messages-api/build
                         prompt/build)
            prompt-msg (builder {:model      (:model model-cfg)
                                 :boot-files (:boot-files ctx)
                                 :nonce      (:nonce session)
                                 :soul       (:soul ctx)
                                 :filter-fn  (when openai? prompt/filter-messages-openai)
                                 :transcript (get-transcript key-str)})]
        (g/assoc! :built-prompt prompt-msg)))))

;; file-exists-with ("the file X exists with:") moved to isaac.foundation.fs-steps.

;; module-manifest-exists ("a module manifest ...:") moved to
;; isaac.foundation.root-steps.

(defn given-file-contains [path content]
  (with-feature-fs
    (fn []
      (let [abs-path (if (str/starts-with? path "/")
                       path
                       (str (System/getProperty "user.dir") "/" path))
            fs*      (mem-fs)]
        (fs/mkdirs fs* (fs/parent abs-path))
        (fs/spit   fs* abs-path content)
        (notify-config-change! abs-path)))))

(defn then-file-contains [path content]
  (with-feature-fs
    (fn []
      (let [root-name (.getName (io/file (root-dir)))
            abs-path  (cond
                        (str/starts-with? path "/") path
                        (str/starts-with? path (str root-name "/")) (str (root-dir) "/" (subs path (inc (count root-name))))
                        :else (str (root-dir) "/" path))]
        (g/should (str/includes? (or (fs/slurp (mem-fs) abs-path) "") content))))))

(defn crew-has-file [crew-id filename content]
  (with-feature-fs
    (fn []
      (let [quarters (str (root-dir) "/crew/" crew-id)
            path     (str quarters "/" filename)
            fs*      (mem-fs)]
        (fs/mkdirs fs* quarters)
        (fs/spit   fs* path content)))))

(defn crew-has-quarters [crew-id]
  (with-feature-fs
    (fn []
      (fs/mkdirs (mem-fs) (str (root-dir) "/crew/" crew-id)))))

;; endregion ^^^^^ When ^^^^^

;; region ----- Then -----

(defn error-contains-quoted [expected]
  (g/should (str/includes? (or (g/get :error) "") expected)))

(defn session-count-is [n]
  (let [n (if (string? n) (parse-long n) n)]
    (g/should= n (count (with-feature-fs #(list-sessions))))))

(defn- session-match-entry [entry]
  (assoc entry
         :crew (or (:crew entry) (:agent entry))
         :file (str "sessions/" (:session-file entry))))

(defn- transcript-match-entry [entry include-compaction-message?]
  (cond-> entry
    (and include-compaction-message? (= "compaction" (:type entry)))
    (assoc :message {:content (:summary entry)})

    (= "toolResult" (get-in entry [:message :role]))
    (update-in [:message :content]
               #(-> (or % "")
                    (str/replace #"^Error:\s*" "")
                    (str/replace #"^path outside allowed directories:.*$" "path outside allowed directories")))))

(defn- normalize-transcript-table [table]
  (let [col-rename {"role" "message.role" "content-matcher" "message.content"}
        headers    (mapv #(get col-rename % %) (:headers table))
        table      (assoc table :headers headers)]
    (update table :rows
            (fn [rows]
              (mapv (fn [row]
                      (let [row-map (zipmap headers row)]
                        (mapv (fn [header cell]
                                (cond
                                  (and (= "message" (get row-map "type"))
                                       (= "message.content" header)
                                       (str/blank? cell))
                                  "#*"

                                  (and (= "message.content" header)
                                       (re-matches #"contains \"(.+)\"" cell))
                                  (let [[_ s] (re-matches #"contains \"(.+)\"" cell)]
                                    (str "#\".*\\Q" s "\\E.*\""))

                                  :else cell))
                              headers
                              row)))
                    rows)))))

(defn- transcript-match-result [table transcript]
  (let [expected-count (count (:rows table))]
    (if (= 1 expected-count)
      (let [row            (first (:rows table))
            row-map        (zipmap (:headers table) row)
            explicit-pairs (->> row-map
                                (remove (fn [[header cell]]
                                          (or (= "#index" header)
                                              (str/blank? cell))))
                                vec)
            role           (some-> (get row-map "message.role") not-empty)
            type           (some-> (get row-map "type") not-empty)
            candidates     (cond->> transcript
                             role (filter #(= role (get-in % [:message :role])))
                             type (filter #(= type (:type %)))
                             true vec)
            candidates     (if (seq candidates) candidates transcript)
            vtable         {:rows (mapv (fn [[header cell]] [header cell]) explicit-pairs)}
            result         (or (some (fn [entry]
                                       (let [match-result (match/match-object vtable entry)]
                                         (when (empty? (:failures match-result)) match-result)))
                                     candidates)
                               (match/match-object vtable (first candidates)))]
        {:captures (:captures result)
         :failures (:failures result)
         :pass?    (empty? (:failures result))})
      (let [direct (match/match-entries table transcript)]
        (if (empty? (:failures direct))
          direct
          (or (some (fn [start]
                      (let [window (subvec transcript start (min (count transcript) (+ start expected-count)))
                            result (match/match-entries table window)]
                        (when (empty? (:failures result)) result)))
                    (range (count transcript)))
              direct))))))

(defn sessions-match [table]
  (let [listing (mapv session-match-entry (with-feature-fs #(list-sessions)))
        result  (match/match-entries table listing)]
    (g/should= [] (:failures result))))

(defn session-file-is-quoted [expected-path]
  (let [entry (current-session)]
    (g/should= expected-path (str "sessions/" (:session-file entry)))))

(defn most-recent-session-is [session-name]
  (let [expected (unquote-string session-name)
        entry     (with-feature-fs #(most-recent-session))]
    (g/should= expected (:id entry))))

(defn- session-transcript-count* [transcript-fn key-str n]
  (let [transcript (with-feature-fs #(transcript-fn key-str))]
    (g/should= (parse-long n) (count transcript))))

(defn session-transcript-count [key-str n]
  (session-transcript-count* get-transcript key-str n))

(defn session-active-transcript-count [key-str n]
  (session-transcript-count* get-active-transcript key-str n))

(defn async-compaction-in-flight [key-str]
  (await-turn!)
  (g/should (single-turn/async-compaction-in-flight? key-str)))

(defn- session-transcript-matching* [transcript-fn key-str table]
  (await-turn!)
  (await-acp-turn!)
  (let [table (normalize-transcript-table table)
        transcript (with-feature-fs #(transcript-fn key-str))
         explicit-idx? (some #(contains? % "#index") (map #(zipmap (:headers table) %) (:rows table)))
         wants-session? (some #(= "session" (get % "type")) (map #(zipmap (:headers table) %) (:rows table)))
         include-compaction-message? (not (some #{"summary"} (:headers table)))
         transcript (if (or explicit-idx? wants-session?)
                      transcript
                      (vec (remove #(= "session" (:type %)) transcript)))
         transcript   (mapv #(transcript-match-entry % include-compaction-message?) transcript)
          result     (if explicit-idx?
                       (match/match-entries table transcript)
                       (transcript-match-result table transcript))]
     (g/should= [] (:failures result))))

(defn session-transcript-matching [key-str table]
  (session-transcript-matching* get-transcript key-str table))

(defn session-active-transcript-matching [key-str table]
  (session-transcript-matching* get-active-transcript key-str table))

(defn session-transcript-not-matching [key-str table]
  (await-turn!)
  (await-acp-turn!)
  (let [table                  (normalize-transcript-table table)
        transcript             (with-feature-fs #(get-transcript key-str))
        explicit-idx?          (some #(contains? % "#index") (map #(zipmap (:headers table) %) (:rows table)))
        wants-session?         (some #(= "session" (get % "type")) (map #(zipmap (:headers table) %) (:rows table)))
        include-compaction?    (not (some #{"summary"} (:headers table)))
        transcript             (if (or explicit-idx? wants-session?)
                                 transcript
                                 (vec (remove #(= "session" (:type %)) transcript)))
        transcript             (mapv #(transcript-match-entry % include-compaction?) transcript)
        result                 (if explicit-idx?
                                 (match/match-entries table transcript)
                                 (transcript-match-result table transcript))]
    (g/should-not (empty? (:failures result)))))

(defn compaction-defaults [table]
  (let [rows (map #(zipmap (:headers table) %) (:rows table))]
    (doseq [row rows]
      (let [window (parse-long (get row "context-window"))]
        (g/should= (Double/parseDouble (get row "threshold")) (session-ctx/default-threshold window))
        (g/should= (Double/parseDouble (get row "head")) (session-ctx/default-head window))))))

(defn- build-session-prompt [content key-str & {:keys [guidance origin]}]
  (append-message! key-str {:role "user" :content content})
  (let [transcript (get-transcript key-str)
        session    (get-session key-str)
        agent-id   (or (:crew session) (:agent session) "main")
        cfg        (loaded-config)
        agents     (merged-agents)
        models     (loaded-models)
        agent-cfg  (get agents agent-id)
        model-cfg  (get models (:model agent-cfg))
        tools      (g/get :tools)
        provider'  (name (or (:provider model-cfg) ""))
        openai?    (or (str/includes? provider' "openai") (str/includes? provider' "grok"))
        builder    (if (str/includes? provider' "anthropic")
                     messages-api/build
                     prompt/build)
        skill-disclosure (session-ctx/read-skill-disclosure cfg (root-dir) (:cwd session) (mem-fs))
        ctx        (assoc (resolve/resolve-crew-context cfg agent-id)
                          :boot-files      (session-ctx/read-boot-files (:cwd session))
                          :rules-text      (session-ctx/read-rules-text cfg (root-dir) (:cwd session) (mem-fs))
                          :skill-menu-text (:menu-text skill-disclosure))]
    (builder {:boot-files     (:boot-files ctx)
              :context-window (:context-window model-cfg)
              :filter-fn      (when openai? prompt/filter-messages-openai)
              :guidance       guidance
              :model          (:model model-cfg)
              :nonce          (:nonce session)
              :origin         origin
              :rules-text     (:rules-text ctx)
              :skill-menu-text (:skill-menu-text ctx)
              :soul           (:soul ctx)
              :tools          tools
              :transcript     transcript})))

(defn prompt-on-session-matches [content key-str table]
  (g/assoc! :current-key key-str)
  (with-feature-fs
    (fn []
      (let [result (match/match-object table (build-session-prompt content key-str))]
        (g/should= [] (:failures result))))))

(defn prompt-on-session-with-framing-matches [content key-str origin-edn guidance table]
  (g/assoc! :current-key key-str)
  (with-feature-fs
    (fn []
      (let [origin (edn/read-string origin-edn)
            result (match/match-object table (build-session-prompt content key-str :guidance guidance :origin origin))]
        (g/should= [] (:failures result))))))

(defn session-sidecars-exist-for [table]
  (let [sidecars  (with-feature-fs #(or (fs/children (mem-fs) (str (root-dir) "/sessions")) []))
        actual    (->> sidecars
                       (filter #(str/ends-with? % ".edn"))
                       (remove #(= "index.edn" %))
                       (map #(subs % 0 (- (count %) (count ".edn"))))
                       set)
        expected   (set (map first (:rows table)))]
    (g/should= expected actual)))

(defn system-prompt-contains [text]
  (let [prompt (get-in (last-llm-request) [:messages 0 :content])]
    (g/should (str/includes? (or prompt "") text))))

(defn system-prompt-not-contains [text]
  (let [prompt (get-in (last-llm-request) [:messages 0 :content])]
    (g/should-not (str/includes? (or prompt "") text))))

(defn last-compaction-request-input-contains [text]
  (let [content (get-in (session-compaction/last-compaction-request) [:messages 1 :content])]
    (g/should-not-be-nil content)
    (g/should (str/includes? content text))))

(defn compaction-request-matches [table]
  (let [request (session-compaction/last-compaction-request)
        result  (match/match-object table request)]
    (g/should= [] (:failures result))))

(defn last-llm-request-matches [table]
  (await-turn!)
  (let [request (g/get :llm-request)
        result  (match/match-object table request)]
    (g/should= [] (:failures result))))

(defn last-llm-request-has-no-effort []
  (await-turn!)
  (g/should-not (contains? (g/get :llm-request) :effort)))

(defn turn-result-is [expected]
  (await-turn!)
  (g/should= (unquote-string expected)
             (or (:stopReason (g/get :llm-result))
                 (some-> (g/get :llm-result) :error name))))

(defn session-has-no-role [key-str role]
  (let [entries (with-feature-fs #(get-transcript key-str))
        role    (unquote-string role)]
    (g/should-not (some #(= role (get-in % [:message :role])) entries))))

(defn prompt-has-tool-count [n]
  (let [n (if (string? n) (parse-long n) n)]
    (g/should= n (count (prompt-tools)))))

(defn prompt-has-tools [table]
  (let [actual (set (map prompt-tool-name (prompt-tools)))
        expected (set (map first (:rows table)))]
    (g/should= expected actual)))

(defn prompt-does-not-have-tools [table]
  (let [actual (set (map prompt-tool-name (prompt-tools)))
        disallowed (set (map first (:rows table)))]
    (g/should-not (seq (set/intersection actual disallowed)))))

(defn prompt-messages-contain-tool-call [table]
  (let [messages (:messages (g/get :built-prompt))
        tc-msg   (first (filter #(contains? % :tool_calls) messages))
        result   (match/match-object table tc-msg)]
    (g/should= [] (:failures result))))

(defn prompt-messages-contain-tool-result [table]
  (let [messages (:messages (g/get :built-prompt))
        tr-msg   (first (filter #(= "tool" (:role %)) messages))
        result   (match/match-object table tr-msg)]
    (g/should= [] (:failures result))))

(defn prompt-messages-do-not-contain-key [key-name]
  (let [messages (:messages (g/get :built-prompt))
        kw       (keyword (unquote-string key-name))]
    (g/should-not (some #(contains? % kw) messages))))

(defn prompt-messages-do-not-contain-role [role]
  (let [role     (unquote-string role)
        messages (:messages (g/get :built-prompt))]
    (g/should-not (some #(= role (:role %)) messages))))

(defn tool-loop-request-contains [table]
  (with-feature-fs
    (fn []
      (let [key-str       (current-key)
            session       (get-session key-str)
            transcript    (get-transcript key-str)
            agent-id      (or (:crew session) (:agent session) "main")
            cfg           (loaded-config)
            model-cfg     (current-model-config)
            ctx           (assoc (resolve/resolve-crew-context cfg agent-id)
                                 :boot-files (session-ctx/read-boot-files (:cwd session)))
            provider-name (or (some (fn [[name cfg]]
                                      (when (contains? #{"chat-completions" "responses"} (:api cfg))
                                        name))
                                    (g/get :provider-configs))
                              (current-provider))
            provider-cfg  (get (g/get :provider-configs) provider-name)
            built-request (single-turn/build-chat-request
                            (llm-provider/make-provider provider-name provider-cfg)
                            {:boot-files (:boot-files ctx)
                             :model      (:model model-cfg)
                             :nonce      (:nonce session)
                             :soul       (:soul ctx)
                             :transcript transcript})
            result        (match/match-entries table (:messages built-request))]
        (g/should= [] (:failures result))))))

;; region ----- Routing -----

;; "an (empty) Isaac root at" / "an empty Isaac state directory" routing
;; moved to isaac.foundation.root-steps.

(defgiven "the Isaac server is started" isaac.session.session-steps/server-started
  "Agent-local stand-in for the server-tier step: commits the current
   on-disk config as the snapshot and arms synchronous hot reload so
   later config writes drive runtime/reload!.")

(defgiven "config:" isaac.session.session-steps/config-applied
  "Applies a key/value table of harness settings. Supports log.output
   (routes the in-memory logger). Lets log-assertion features that don't
   run 'default Grover setup' still capture structured log entries.")

(defgiven "default Grover setup" isaac.session.session-steps/default-grover-setup
  "One-line Background: in-memory state dir at target/test-state plus
   grover provider, echo model, main crew with soul 'You are Atticus.'
   on disk. Use as the baseline for any feature that just needs a
   working crew/model combo; override pieces afterward as needed.")

(defgiven "default Grover setup in {dir:string}" isaac.session.session-steps/default-grover-setup-in
  "Same as 'default Grover setup' but at a custom root path.")

(defgiven "the crew member has tools:" isaac.session.session-steps/crew-has-tools
  "Registers the listed tools with the tool-registry and sets each
   crew member's :tools.allow to the names. Tools not already registered
   get a no-op handler. Table columns: name, description, parameters
   (JSON). Applies to ALL crew in the :crew atom, not just one.")

(defgiven "the crew {crew-id:string} allows tools: {tools:string}" isaac.session.session-steps/crew-tool-allow
  "Patches :tools.allow on an existing crew config. Comma-separated tool
   names; no need to repeat model/soul fields already set by default Grover setup.")

(defgiven "the Ollama server is running" isaac.session.session-steps/ollama-server-running
  "Sets the test 'ollama' provider-config to localhost:11434. Does not
   actually start ollama — assumes a real server is reachable for
   integration tests (or grover is acting as one in the test double).")

(defgiven "model {model:string} is available in Ollama" isaac.session.session-steps/ollama-model-available)

(defgiven "the Ollama server is not running" isaac.session.session-steps/ollama-server-not-running
  "Sets the 'ollama' provider-config to an unreachable port (99999) so
   provider calls fail with connection-refused. Used to test
   connection-failure handling.")

(defgiven "the following model responses are queued:" isaac.session.session-steps/responses-queued
  "Clears and re-populates the grover response queue. Each table row is
   one chunk/event the mock will emit in order. Columns: 'type' (text /
   tool_call / error), 'content' or 'tool_call' + 'arguments', 'model'.
   For streaming, enqueue multiple rows; they come out as distinct
   chunks.")

(defgiven "the tool loop max is {n:int}" isaac.session.session-steps/tool-loop-max-is)

(defgiven "crew {crew:string} has quarters" isaac.session.session-steps/crew-has-quarters)

(defgiven "the LLM response is delayed by {int} seconds" isaac.session.session-steps/llm-response-delayed)

(defgiven "the following sessions exist:" isaac.session.session-steps/sessions-exist
  "Creates sessions on disk via the file-backed SessionStore (NOT the :crew
    test atom). Columns: name (session key), optionally crew/agent,
    cwd, updated-at, total-tokens, input-tokens, output-tokens,
    compaction-count, compaction.strategy/threshold/tail/async?. Writes
    the transcript directory and session index.")

(defgiven #"session \"([^\"]+)\" is in flight" isaac.session.session-steps/session-is-in-flight)

(defwhen #"a session \"([^\"]+)\" is created with explicit ([^ ]+) \"([^\"]*)\"" isaac.session.session-steps/session-created-with-explicit-behavior)

(defgiven #"a session \"([^\"]+)\" exists with ([^ ]+) \"([^\"]*)\"" isaac.session.session-steps/session-exists-with-behavior)

(defthen #"the session \"([^\"]+)\" exists" isaac.session.session-steps/session-exists-quoted)

(defthen #"session \"([^\"]+)\" exists" isaac.session.session-steps/session-exists)

(defthen #"session \"([^\"]+)\" does not exist" isaac.session.session-steps/session-does-not-exist)

(defthen "session {key:string} matches:" isaac.session.session-steps/session-matches)

(defthen "the resolved behavior for {string} matches:" isaac.session.session-steps/resolved-behavior-matches)

(defthen #"the resolved behavior for \"([^\"]+)\" has ([^ ]+) \"([^\"]*)\"" isaac.session.session-steps/resolved-behavior-has)

(defgiven "session {key:string} has transcript:" isaac.session.session-steps/session-has-transcript
  "Appends transcript entries to an existing session. The 'type' column
   picks the entry kind: message (default, role+content), compaction
   (summary+firstKeptEntryId+tokensBefore), toolCall (name+arguments+id),
   toolResult (id+content+isError). Additional columns populate optional
   fields (message.model, message.usage.input, etc.).")

(defgiven #"session \"([^\"]+)\" has an error entry \"([^\"]+)\"" isaac.session.session-steps/session-has-error-entry)

(defwhen "a session is created with a random name" isaac.session.session-steps/session-created-randomly)

(defwhen "a session is created without a name" isaac.session.session-steps/session-created-without-name)

(defwhen #"a session is created with name \"([^\"]+)\"" isaac.session.session-steps/session-created-with-name-quoted)

(defwhen #"a session is created named \"([^\"]+)\"" isaac.session.session-steps/session-created-named)

(defwhen "session {string} is opened" isaac.session.session-steps/session-opened)

(defwhen "entries are appended to session {key:string}:" isaac.session.session-steps/entries-appended)

(defwhen "compaction is spliced into session {key:string} with:" isaac.session.session-steps/compaction-spliced-into-session
  "Calls the file-backed SessionStore splice directly using transcript indexes from the
   current session. Use in storage-level scenarios that need to exercise the
   exact splice path without running a full turn.")

(defwhen #"the user sends \"(.+)\" on session \"([^\"]+)\"$" isaac.session.session-steps/user-sends-on-session
  "Drives a full turn via single-turn/run-turn! (in-memory,
   bypasses ACP/HTTP). Runs in a background future; waits 50ms and calls
   complete-turn! if done. Captures :llm-request (grover/last-request),
   :llm-result, :output. Use 'await-turn!' or a later step to force
   completion for async compaction scenarios.")

(defwhen #"the turn ends on session \"([^\"]+)\"" isaac.session.session-steps/turn-ends-on-session)

(defwhen #"^the turn is cancelled on session \"([^\"]+)\"$" isaac.session.session-steps/turn-cancelled
  "Cancels the running turn via bridge/cancel! and awaits the turn future.")

(defwhen "the turn is cancelled on session {key:string} after {n:int} tool call" isaac.session.session-steps/turn-cancelled-after-n-tool-calls
  "Waits for n tool-result events then cancels, used to test mid-loop cancellation.")

(defwhen "the turn is cancelled on session {key:string} after {n:int} tool calls" isaac.session.session-steps/turn-cancelled-after-n-tool-calls
  "Waits for n tool-result events then cancels, used to test mid-loop cancellation.")

(defwhen #"the async compaction for session \"([^\"]+)\" completes" isaac.session.session-steps/async-compaction-completes)

(defwhen #"the prompt for session \"([^\"]+)\" is built for provider \"([^\"]+)\"" isaac.session.session-steps/prompt-built-for-provider
  "Synthetically builds a prompt for an existing session + provider
   (anthropic or prompt/build fallback) and stores it in :built-prompt.
   Does NOT actually run a turn — no LLM is called, no transcript is
   mutated. Use for asserting prompt shape on its own.")


(defgiven #"file \"([^\"]+)\" contains \"([^\"]*)\"" isaac.session.session-steps/given-file-contains)

(defthen #"the file \"([^\"]+)\" contains \"([^\"]*)\"" isaac.session.session-steps/then-file-contains)

(defgiven #"crew \"([^\"]+)\" has file \"([^\"]+)\" with \"([^\"]+)\"" isaac.session.session-steps/crew-has-file)

(defthen #"the error contains \"([^\"]+)\"" isaac.session.session-steps/error-contains-quoted)

(defthen "the session count is {int}" isaac.session.session-steps/session-count-is)

(defthen "the following sessions match:" isaac.session.session-steps/sessions-match)

(defthen #"the session file is \"([^\"]+)\"" isaac.session.session-steps/session-file-is-quoted)

(defthen "the most recent session is {string}" isaac.session.session-steps/most-recent-session-is)

(defthen #"session \"([^\"]+)\" has (\d+) transcript entr(?:y|ies)" isaac.session.session-steps/session-transcript-count)

(defthen #"session \"([^\"]+)\" has (\d+) active transcript entr(?:y|ies)" isaac.session.session-steps/session-active-transcript-count)

(defthen #"an async compaction for session \"([^\"]+)\" is in flight" isaac.session.session-steps/async-compaction-in-flight)

(defthen #"session \"([^\"]+)\" in-flight status is (true|false)" isaac.session.session-steps/session-in-flight-status)

(defthen #"dispatch is refused with reason \"([^\"]+)\"" isaac.session.session-steps/dispatch-refused-with-reason)

(defthen "session {key:string} has transcript matching:" isaac.session.session-steps/session-transcript-matching
  "Awaits both the in-memory turn-future AND any ACP turn, then matches
   table rows against the transcript. By default skips 'session' header
   entries and uses a column-aware matcher that includes compaction
    summaries unless a 'summary' column is present. Use '#index' in any
     row to force strict positional match.")

(defthen "session {key:string} has active transcript matching:" isaac.session.session-steps/session-active-transcript-matching
  "Matches against the LLM-visible transcript view after any effective
   history offset is applied. Use this when retained history should stay
   on disk but be hidden from the turn path.")

(defthen "session {key:string} has transcript not matching:" isaac.session.session-steps/session-transcript-not-matching)

(defthen "the compaction defaults are:" isaac.session.session-steps/compaction-defaults)

(defthen #"the prompt \"([^\"]+)\" on session \"([^\"]+)\" matches:" isaac.session.session-steps/prompt-on-session-matches
  "Appends a synthetic user message with the given content, rebuilds the
   prompt in-process (via loaded-config + :crew + :models atoms), and
   matches against the table. Does NOT route through production turn
   code — any hot-reload or comm-layer logic is bypassed. Use
   'the system prompt contains' after a real 'the user sends' for
   end-to-end assertions instead.")

(defthen #"the prompt \"([^\"]+)\" on session \"([^\"]+)\" with origin (.+) and guidance \"([^\"]+)\" matches:"
  isaac.session.session-steps/prompt-on-session-with-framing-matches)

(defthen "the session sidecars exist for:" isaac.session.session-steps/session-sidecars-exist-for)

(defthen #"the system prompt contains \"([^\"]+)\"" isaac.session.session-steps/system-prompt-contains
  "Reads :llm-request captured by complete-turn! after a real turn
   (either 'the user sends' or 'isaac is run with'). Asserts the first
   message's content (the system prompt) contains the given substring.
   Use this for end-to-end prompt assertions — unlike
   'the prompt ... matches:', which builds synthetically.")

(defthen #"the system prompt does not contain \"([^\"]+)\"" isaac.session.session-steps/system-prompt-not-contains)

(defthen "the turn result is {string}" isaac.session.session-steps/turn-result-is)

(defthen #"session \"([^\"]+)\" has no transcript entries with role \"([^\"]+)\"" isaac.session.session-steps/session-has-no-role)

(defthen "the prompt has {int} tools" isaac.session.session-steps/prompt-has-tool-count)

(defthen "the prompt has tools:" isaac.session.session-steps/prompt-has-tools
  "Reads :llm-request from complete-turn! capture. Asserts the set of
   tool names in the request equals the set in the table's first column.
   Exact set equality — use 'the prompt does not have tools:' to check
   specific exclusions.")

(defthen "the prompt does not have tools:" isaac.session.session-steps/prompt-does-not-have-tools)

(defthen "the prompt messages contain a tool call with:" isaac.session.session-steps/prompt-messages-contain-tool-call
  "Reads :built-prompt (from 'the prompt for session X is built for
   provider Y'). Finds the first message with :tool_calls and matches
   against the table. Pair with the prompt-built-for-provider step.")

(defthen "the prompt messages contain a tool result with:" isaac.session.session-steps/prompt-messages-contain-tool-result)

(defthen #"the prompt messages do not contain key \"([^\"]+)\"" isaac.session.session-steps/prompt-messages-do-not-contain-key)

(defthen #"the prompt messages do not contain role \"([^\"]+)\"" isaac.session.session-steps/prompt-messages-do-not-contain-role)

(defthen "the tool loop request contains messages with:" isaac.session.session-steps/tool-loop-request-contains)

(defn use-file-session-store []
  (alter-var-root #'sidecar-store/create-store (constantly real-sidecar-create-store))
  (store/register-store! (with-feature-fs #(sidecar-store/create-store (root-dir)))))

(defgiven "the session store uses the file implementation" isaac.session.session-steps/use-file-session-store
  "Restores the real file-backed SessionStore for this scenario. Use in scenarios
   that explicitly test sidecar-store behavior such as sidecar files on disk.")

(defthen #"the last compaction request input contains \"([^\"]+)\"" isaac.session.session-steps/last-compaction-request-input-contains)

(defthen "the compaction request matches:" isaac.session.session-steps/compaction-request-matches)

(defgiven "the session {name:string} has effort {effort:string}" isaac.session.session-steps/session-has-effort
  "Updates the named session's :effort field to the given integer. Use in scenarios
   that test session-level effort override without running an /effort command.")

(defthen "the last LLM request matches:" isaac.session.session-steps/last-llm-request-matches
  "Awaits the turn, then matches the Clojure LLM request map (pre-API, as captured
   by grover/last-request) against the table using the match DSL. Use this for
   API-agnostic effort assertions; for wire-shape assertions use
   'the last outbound HTTP request matches:'.")

(defthen "the last LLM request has no effort" isaac.session.session-steps/last-llm-request-has-no-effort
  "Awaits the turn, then asserts that the LLM request map has no :effort key.")

;; endregion ^^^^^ Routing ^^^^^

;; endregion ^^^^^ Then ^^^^^
