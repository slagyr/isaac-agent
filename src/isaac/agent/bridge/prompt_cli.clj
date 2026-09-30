(ns isaac.agent.bridge.prompt-cli
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.agent.config.runtime :as runtime]
    [isaac.agent.bridge.core :as bridge]
    [isaac.agent.charge :as charge]
    [isaac.foundation.cli.api :as cli-api]
    [isaac.foundation.cli.host :as host]
    [isaac.foundation.cli.registry :as cli]
    [isaac.agent.comm.protocol :as comm]
    [isaac.agent.comm.render :as render]
    [isaac.foundation.config.api :as config]
    [isaac.agent.config.defaults :as defaults]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.config.root :as root]
    [isaac.agent.drive.observer :as observer]
    [isaac.agent.drive.turn :as single-turn]
    [isaac.foundation.fs :as fs]
    [isaac.agent.session.context :as session-ctx]
    [isaac.agent.frequencies :as session-frequencies]
    [isaac.agent.frequencies-cli :as frequencies-cli]
    [isaac.agent.session.policy :as policy]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.tool.builtin :as builtin]
    [isaac.agent.tool.memory :as memory]
    [isaac.agent.resource-pool :as pool]
    [isaac.agent.turn.queue :as turn-queue])
  (:import (clojure.lang ExceptionInfo)))

(defn- stderr-line! [text]
  (binding [*out* *err*]
    (println text)))

(defn- tool-icon [tool-name]
  (cond
    (= "fs__grep" tool-name) "🔍"
    (= "fs__read" tool-name) "📖"
    (or (= "fs__write" tool-name)
        (= "fs__edit" tool-name)) "✏️"
    (= "exec__run" tool-name) "⚙️"
    (= "web__fetch" tool-name) "🌐"
    (str/starts-with? tool-name "memory__") "💾"
    :else "🧰"))

(defn- tool-summary [tool-call]
  (or (get-in tool-call [:arguments :pattern])
      (get-in tool-call [:arguments :command])
      (get-in tool-call [:arguments :file_path])
      (get-in tool-call [:arguments :path])
      (some-> tool-call :arguments vals first)
      ""))

(defn- compaction-error-text [payload]
  (or (:message payload)
      (some-> (:error payload) name)
      (some-> (:error payload) str)
      "unknown error"))

(defn- bulletin-kind [bulletin]
  (or (:kind bulletin) (:kind (:payload bulletin))))

(defn- bulletin-payload [bulletin]
  (or (:payload bulletin) (dissoc bulletin :kind :event :session)))

(deftype PromptComm [text-atom live?])

(extend PromptComm
  comm/Comm
  (merge comm/defaults
         {:on-chatter
          (fn [this _ _ text]
            (let [plain (render/chunk-text text)]
              (swap! (.-text-atom this) str plain)
              (when (.-live? this)
                (print plain)
                (flush))))

          :on-tool-call
          (fn [_ _ tool-call]
            (stderr-line! (str (tool-icon (:name tool-call)) " " (:name tool-call)
                               (when-let [summary (not-empty (str (tool-summary tool-call)))]
                                 (str " " summary)))))

          :on-tool-result
          (fn [_ _ tool-call _]
            (stderr-line! (str "← " (:name tool-call))))

          :on-bulletin
          (fn [_ _ bulletin]
            (let [kind    (bulletin-kind bulletin)
                  payload (bulletin-payload bulletin)]
              (case kind
                :compaction/start
                (stderr-line! (str "🥬 compacting… " (:total-tokens payload)))
                :compaction/success
                (stderr-line! "✨ compacted")
                :compaction/failure
                (stderr-line! (str "🥀 compaction failed: " (compaction-error-text payload)))
                nil)))

          :send!
          (fn [_ _] {:ok false :transient? false})}))

(defn- make-prompt-comm
  ([] (make-prompt-comm false))
  ([live?]
   (let [text (atom "")]
     {:comm (->PromptComm text (boolean live?))
      :text text})))

(def ^:private usage-fields
  [:requests :prompt-tokens :output-tokens :total-tokens
   :cache-read-tokens :cache-write-tokens :reasoning-tokens :unsupported-requests])

(defn- turn-usage
  "What the turn cost, as the caller should see it. The drive already totals
   every request; this only adds the total the caller reads first."
  [result]
  (when-let [u (:usage result)]
    (let [total (+ (or (:prompt-tokens u) 0) (or (:output-tokens u) 0))]
      (not-empty (into {} (keep (fn [k]
                                  (when-let [v (get (assoc u :total-tokens total) k)]
                                    [k v])))
                       usage-fields)))))

(defn- usage-line [usage]
  (str "usage: " (str/join " " (map (fn [[k v]] (str (name k) "=" v)) usage))))

(defn- report-usage!
  "Opt-in, per caller. A human asking a question over a comm must never get a
   token bill appended to the answer, so nothing prints unless --usage asked
   (isaac-5nx5)."
  [result]
  (when-let [usage (turn-usage result)]
    (stderr-line! (usage-line usage))))

(defn- root-of [opts]
  (root/default-root opts))

(defn- print-error! [message]
  (binding [*out* *err*]
    (println message)))

(defn- load-result [opts]
  (or (:load-result opts)
      (when (contains? opts :config)
        {:config (:config opts)})
      (loader/load-config-result {:root (root-of opts)
                                  :fs   (fs/instance)})))

(defn- ensure-local-config! [opts]
  (let [result (load-result opts)]
    (when (:missing-config? result)
      (print-error! (get-in result [:errors 0 :value]))
      false)))

(defn- install-config! [opts]
  (if (contains? opts :config)
    (config/dangerously-install-config! (:config opts) "prompt-cli")
    (loader/load-config! (root-of opts) (fs/instance) "prompt-cli")))

(defn- episode-crew-id [opts override cfg]
  (or (:with-crew override)
      (:crew opts)
      (defaults/crew-id cfg)))

(defn- prompt-policy [opts override cfg session-store]
  (policy/for-request {:crew          (episode-crew-id opts override cfg)
                       :config        cfg
                       :session-store session-store}))

(defn- resolve-target [opts _override cfg session-store]
  (let [frequencies (frequencies-cli/build-frequencies opts)]
    (session-frequencies/resolve-session-targets
      frequencies session-store cfg
      (if (:session frequencies) #{} (set (store/in-flight-sessions session-store))))))

(defn- refuse-crew-collision!
  "Refuse --session when an explicit --crew disagrees with the stored crew.
   Missing --crew keeps the stored identity. --with-crew is a turn override,
   not a reassignment."
  [session-store session-key requested-crew]
  (when (and requested-crew session-key session-store)
    (when-let [existing (store/get-session session-store session-key)]
      (let [have (str (:crew existing))
            want (str requested-crew)]
        (when (not= have want)
          (throw (ex-info (str "session " session-key " belongs to crew " have)
                          {:reason :crew-collision :id session-key
                           :crew have :wanted-crew want})))))))

(defn- ensure-session! [target override opts cfg session-store]
  (let [crew-id (episode-crew-id opts override cfg)
        sess    (prompt-policy opts override cfg session-store)
        cwd     (host/cwd)]
    (cond
      (:session-key target)
      (do
        (refuse-crew-collision! session-store (:session-key target) (:crew opts))
        (policy/refuse-policy-mismatch! session-store (:session-key target) (policy/policy-name (get-in cfg [:crew crew-id])))
        (when (and sess (nil? (policy/get-session sess (:session-key target)))
                   (or (:create? target) (:session opts)))
          (policy/open-session! sess (:session-key target)
                                (merge {:cwd           cwd
                                        :config        cfg
                                        :origin        {:kind :cli}
                                        :crew          crew-id
                                        :session-policy (policy/policy-name (get-in cfg [:crew crew-id]))
                                        :session-store session-store}
                                       (or (:create-identity target) {})
                                       (session-frequencies/behavioral-override override))))
        (:session-key target))

      (:create? target)
      (let [identity    (or (:create-identity target) {})
            create-opts (merge {:cwd           cwd
                                :config        cfg
                                :origin        {:kind :cli}
                                :session-store session-store
                                :crew          crew-id
                                :session-policy (policy/policy-name (get-in cfg [:crew crew-id]))}
                               identity
                               (session-frequencies/behavioral-override override))
            ;; The agent names the session when the caller and the policy both
            ;; have none; a policy only ever takes the id it is handed.
            session-key (or (:session-key target)
                            (when sess (store/mint-name (loader/root))))]
        (if (and sess session-key)
          (do
            (policy/open-session! sess session-key create-opts)
            session-key)
          (let [entry (session-ctx/create-with-resolved-behavior!
                        session-key create-opts)]
            (:id entry))))

      :else
      (or (when sess (policy/default-session sess crew-id {:cwd cwd :origin {:kind :cli}}))
          (:session-key target)))))

(defn- dispatch-prompt! [opts cfg session-store session-key session comm text]
  (let [obs-refs  (mapv observer/parse-ref (or (:observer opts) []))
        ts-refs   (mapv keyword (or (:pool opts) []))
        override  (frequencies-cli/build-override opts)
        obs-check (when (seq obs-refs) (observer/resolve-submitted obs-refs))
        ts-check  (when (seq ts-refs) (pool/resolve-submitted cfg ts-refs))]
    (cond
      (:error obs-check) (do (print-error! (:message obs-check)) 1)
      (:error ts-check) (do (print-error! (:message ts-check)) 1)
      :else
      (do
        (host/ensure-runtime! {:install! builtin/register-all!})
        (let [root* (root-of opts)
              accepted (binding [turn-queue/*root* root*]
                         (turn-queue/enqueue! (cond-> {:session session-key :input (:message opts)
                                                       :state (if (:queue opts) :queued :running)
                                                       :origin {:kind :cli}}
                                                (:key opts) (assoc :key (:key opts)))))
              result (when-not (or (:queue opts) (:already-accepted? accepted))
                       (bridge/dispatch!
                       (assoc (charge/build (cond-> {:session-key           session-key
                                                     :input                 (:message opts)
                                                     :config                cfg
                                                     :crew                  (or (:with-crew override) (:crew session))
                                                     :model-override        (or (:with-model override) (:model opts))
                                                     :context-mode-override (:with-context-mode override)
                                                     :origin                {:kind :cli}
                                                     :comm                  comm
                                                     :session-store         session-store}
                                                    (seq obs-refs) (assoc :observers obs-refs)
                                                    (seq ts-refs) (assoc :resource-pools ts-refs)))
                              :root (root-of opts)
                              :session-store session-store
                              :now (memory/now)
                              :turn-id (:id accepted))))]
          (when (and (not (:queue opts)) (not (:already-accepted? accepted)) (not (:held result))
                     (not= :waiting-session (:reason result)))
            (binding [turn-queue/*root* root*]
              (turn-queue/update-turn! (:id accepted) {:state :finished
                                                       :outcome (if (or (:error result) (:unavailable? result)
                                                                        (get-in result [:response :error])) :error :ok)})))
          (cond
            (:already-accepted? accepted)
            (do (println (str "already accepted: " (:id accepted))) 0)

            (:queue opts)
            (do (println (str "queued: " (:id accepted))) 0)

            (= :waiting-session (:reason result))
            (do (println (str "held: " (:held-id result))) 0)

            (:held result)
            (do
              (println (str "held: " (:id result)
                            " (" (or (:message result)
                                     (str (or (first (:resource-pools result)) "resource pool")
                                          " held"))
                            ")"))
              0)

            (or (:error result)
                (:unavailable? result)
                (get-in result [:response :error]))
            (do
              (binding [*out* *err*]
                (println (single-turn/error-message result)))
              1)

            :else
            (do
              (when (and (:usage opts) (not (:json opts)))
                (report-usage! result))
              (cond
                (:json opts)
                (println (json/generate-string (cond-> {:session  session-key
                                                        :response @text}
                                                       (:usage opts) (assoc :usage (turn-usage result)))))

                (seq obs-refs)
                (when-not (str/ends-with? (or @text "") "\n")
                  (println))

                :else
                (println @text))
              0)))))))

(defn run [opts]
  (if-not (:message opts)
    (do (println "Error: -m/--message is required")
        1)
    (let [validation-errors (frequencies-cli/validate-frequencies-options opts)]
      (if (seq validation-errors)
        (do (doseq [error validation-errors] (print-error! error)) 1)
        (if (= false (ensure-local-config! opts))
          1
          (let [root     (root-of opts)
                loaded*  (atom nil)
                _        (host/ensure-runtime!
                           {:install!
                            (fn []
                              (let [cfg (install-config! opts)]
                                (reset! loaded* cfg)
                                (runtime/install! {:config cfg})))})
                cfg           (or @loaded* (loader/snapshot "prompt-cli") (install-config! opts))
                session-store (store/registered-store)
                override      (frequencies-cli/build-override opts)
                target        (resolve-target opts override cfg session-store)]
            (cond
              (:error target)
              (do (print-error! (:message target)) 1)

              (:busy? target)
              (let [refs (mapv keyword (or (:pool opts) []))
                    check (pool/resolve-submitted cfg refs)]
                (if (:error check)
                  (do (print-error! (:message check)) 1)
                  (let [accepted (binding [turn-queue/*root* root]
                                   (turn-queue/enqueue! {:input (:message opts)
                                                         :frequencies (frequencies-cli/build-frequencies opts)
                                                         :resource-pools refs
                                                         :origin {:kind :cli} :state :held}))]
                    (println (str "held: " (:id accepted)))
                    0)))

              :else
              (try
                (let [session-key (ensure-session! target override opts cfg session-store)
                      session     (or (when-let [sess (prompt-policy opts override cfg session-store)]
                                        (policy/get-session sess session-key))
                                      (store/get-session session-store session-key))
                      {:keys [comm text]} (make-prompt-comm (seq (:observer opts)))]
                  (dispatch-prompt! opts cfg session-store session-key session comm text))
                (catch ExceptionInfo e
                  (if (contains? #{:crew-collision :session-policy-mismatch} (:reason (ex-data e)))
                    (do (print-error! (ex-message e)) 1)
                    (throw e)))))))))))

(def option-spec
  (concat
    [["-m" "--message TEXT" "Message to send (required)"]
     [nil "--queue" "Accept without running"]
     [nil "--key KEY" "Idempotency key"]
     ["-j" "--json" "Output result as JSON"]
     [nil "--usage" "Report this turn's token usage (stderr, or in the JSON result)"]
     [nil "--observer REF" "Submit a turn observer (repeatable); e.g. lookout or foreman:bean-work/bn-7"
      :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
     [nil "--pool NAME" "Lease a named resource pool (repeatable)"
      :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
     ["-h" "--help" "Show help"]]
    frequencies-cli/frequencies-option-spec
    frequencies-cli/override-option-spec))

(defn- parse-option-map [raw-args]
  (let [{:keys [arguments options errors]} (tools-cli/parse-opts raw-args option-spec)
        options (cond-> options
                        (and (nil? (:message options)) (seq arguments))
                        (assoc :message (str/join " " arguments))
                        (:create options)
                        (update :create frequencies-cli/parse-create))]
    {:options   (->> options
                     (remove (comp nil? val))
                     (into {}))
     :arguments arguments
     :errors    errors}))

(defn run-fn [{:keys [_raw-args] :as opts}]
  (let [{:keys [options errors]} (parse-option-map (or _raw-args []))]
    (cond
      (:help options)
      (do
        (println (cli/command-help (cli/get-command "prompt")))
        0)

      (seq errors)
      (do
        (doseq [error errors]
          (println error))
        1)

      :else
      (run (merge (dissoc opts :_raw-args) options)))))

;; ----- :isaac/cli berth implementation -----

(defmethod cli-api/run :prompt [_id opts]
  (run-fn opts))

(defmethod cli-api/option-spec :prompt [_id]
  option-spec)
