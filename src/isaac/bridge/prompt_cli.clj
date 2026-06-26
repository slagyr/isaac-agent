(ns isaac.bridge.prompt-cli
  (:require
    [isaac.cli.api :as cli-api]
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.cli :as tools-cli]
    [isaac.bridge.core :as bridge]
    [isaac.charge :as charge]
    [isaac.cli.registry :as cli]
    [isaac.comm.protocol :as comm]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.agent.config.runtime :as runtime]
    [isaac.fs :as fs]
    [isaac.drive.turn :as single-turn]
    [isaac.session.context :as session-ctx]
    [isaac.session.selector :as session-selector]
    [isaac.session.selector-cli :as selector-cli]
    [isaac.session.store.spi :as store]
    [isaac.tool.builtin :as builtin]))

(defn- stderr-line! [text]
  (binding [*out* *err*]
    (println text)))

(defn- tool-icon [tool-name]
  (cond
    (= "grep" tool-name) "🔍"
    (= "read" tool-name) "📖"
    (or (= "write" tool-name)
        (= "edit" tool-name)) "✏️"
    (= "exec" tool-name) "⚙️"
    (= "web_fetch" tool-name) "🌐"
    (str/starts-with? tool-name "memory_") "💾"
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

(deftype PromptComm [text-atom]
  comm/Comm
  (on-turn-start [_ _ _] nil)
  (on-text-chunk [_ _ text] (swap! text-atom str text))
  (on-tool-call [_ _ tool-call]
    (stderr-line! (str (tool-icon (:name tool-call)) " " (:name tool-call)
                       (when-let [summary (not-empty (str (tool-summary tool-call)))]
                         (str " " summary)))))
  (on-tool-cancel [_ _ _] nil)
  (on-tool-result [_ _ tool-call _]
    (stderr-line! (str "← " (:name tool-call))))
  (on-compaction-start [_ _ payload]
    (stderr-line! (str "🥬 compacting… " (:total-tokens payload))))
  (on-compaction-success [_ _ _]
    (stderr-line! "✨ compacted"))
  (on-compaction-failure [_ _ payload]
    (stderr-line! (str "🥀 compaction failed: " (compaction-error-text payload))))
  (on-compaction-disabled [_ _ payload]
    (stderr-line! (str "🪦 compaction disabled: " (name (:reason payload)))))
  (on-turn-end [_ _ _] nil))

(defn- make-prompt-comm []
  (let [text (atom "")]
    {:comm (->PromptComm text)
     :text text}))

(defn- root-of [opts]
  (root/default-root opts))

(defn- print-error! [message]
  (binding [*out* *err*]
    (println message)))

(defn- ensure-local-config! [opts]
  (let [result (loader/load-config-result {:root (root-of opts)
                                           :fs   (fs/instance)})]
    (when (:missing-config? result)
      (print-error! (get-in result [:errors 0 :value]))
      false)))

(defn- resolve-target [opts session-store]
  (session-selector/resolve-session-targets (selector-cli/build-select opts) session-store))

(defn- ensure-session! [target override opts cfg session-store]
  (if (:create? target)
    (let [identity    (or (:create-identity target) {})
          create-opts (merge {:cwd           (System/getProperty "user.dir")
                              :config        cfg
                              :origin        {:kind :cli}
                              :session-store session-store}
                             identity
                             (select-keys override [:model :crew :effort :context-mode]))
          entry       (session-ctx/create-with-resolved-behavior!
                        (:session-key target) create-opts)]
      (:id entry))
    (:session-key target)))

(defn run [opts]
  (if-not (:message opts)
    (do (println "Error: -m/--message is required")
        1)
    (let [validation-errors (selector-cli/validate-select-options opts)]
      (if (seq validation-errors)
        (do (doseq [error validation-errors] (print-error! error)) 1)
        (if (= false (ensure-local-config! opts))
          1
          (let [root          (root-of opts)
                cfg           (loader/load-config! root (fs/instance) "prompt-cli")
                _             (runtime/install! {:config cfg})
                session-store (store/registered-store)
                override      (selector-cli/build-override opts)
                target        (resolve-target opts session-store)]
            (if (:error target)
              (do (print-error! (:message target)) 1)
              (let [session-key (ensure-session! target override opts cfg session-store)
                    session     (store/get-session session-store session-key)
                    {:keys [comm text]} (make-prompt-comm)]
                (builtin/register-all!)
                (let [result (bridge/dispatch!
                               (charge/build {:session-key    session-key
                                              :input          (:message opts)
                                              :config         cfg
                                              :crew           (or (:crew override) (:crew session))
                                              :model-override (or (:model override) (:model opts))
                                              :origin         {:kind :cli}
                                              :comm           comm}))]
                  (if (or (:error result) (get-in result [:response :error]))
                    (do
                      (binding [*out* *err*]
                        (println (single-turn/error-message result)))
                      1)
                    (do
                      (if (:json opts)
                        (println (json/generate-string {:session  session-key
                                                          :response @text}))
                        (println @text))
                      0)))))))))))

(def option-spec
  (concat
    [["-m" "--message TEXT" "Message to send (required)"]
     ["-j" "--json" "Output result as JSON"]
     ["-h" "--help" "Show help"]]
    selector-cli/select-option-spec
    selector-cli/override-option-spec))

(defn- parse-option-map [raw-args]
  (let [{:keys [arguments options errors]} (tools-cli/parse-opts raw-args option-spec)
        options (cond-> options
                        (and (nil? (:message options)) (seq arguments))
                        (assoc :message (str/join " " arguments))
                        (:create options)
                        (update :create selector-cli/parse-create))]
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