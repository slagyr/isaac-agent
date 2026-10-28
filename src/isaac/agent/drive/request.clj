(ns isaac.agent.drive.request
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.agent.drive.tools-config :as tools-config]
    [isaac.agent.llm.api.protocol :as api]
    [isaac.agent.llm.provider :as llm-provider]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.system-sections :as system-sections]
    [isaac.foundation.logger :as log]
    [isaac.foundation.nexus :as nexus]))

(defn build-chat-request [p {:keys [boot-files crew effort guidance model nonce origin preamble rules-text session-name skill-menu-text soul transcript tools sections]}]
  (let [prompt-out (api/build-prompt p {:boot-files      boot-files
                                        :sections        sections
                                        :crew            crew
                                        :guidance        guidance
                                        :model           model
                                        :nonce           nonce
                                        :origin          origin
                                        :preamble        preamble
                                        :rules-text      rules-text
                                        :session-name    session-name
                                        :skill-menu-text skill-menu-text
                                        :soul            soul
                                        :transcript      transcript
                                        :tools           tools})
        stateful   (get (api/config p) :stateful)]
    (cond-> {:model (:model prompt-out) :messages (:messages prompt-out)}
            (:system prompt-out) (assoc :system (:system prompt-out))
            (:max-tokens prompt-out) (assoc :max-tokens (:max-tokens prompt-out))
            (:tools prompt-out) (assoc :tools (:tools prompt-out))
            (some? effort) (assoc :effort effort)
            (some? stateful) (assoc :stateful stateful)
            session-name (assoc :session-key session-name))))



(defn augment-provider
  "Wrap an upstream Api with per-turn runtime values (root,
   session-key, context-window, and model-cfg overrides) merged into
   its config. Returns a new Api instance — the upstream one is unchanged."
  [root p session-key context-window model-cfg-overrides]
  (when p
    (let [cfg (merge (or (api/config p) {})
                     model-cfg-overrides
                     {:root           root
                      :session-key    session-key
                      :context-window context-window})]
      (llm-provider/make-provider (api/display-name p) cfg))))

(def turn-schema
  {:name   :turn
   :type   :map
   :schema {:charge          {:type :ignore :description "Resolved charge — the comm-supplied inputs"}
            :session-store   {:type :ignore :description "Session store backing this turn (derived from charge or root)"}
            :root            {:type :string :description "Isaac state directory"}
            :effort          {:type :long :description "Per-turn effort budget, nil when model disallows effort"}
            :allowed-tools   {:type :ignore :description "Set of tool keywords allowed for this turn's crew"}
            :boot-files      {:type :ignore :description "Boot-file contents read from the discovered project root"}
            :rules-text      {:type :ignore :description "Always-on prepared rule bodies read from global/project roots"}
            :sections        {:type :ignore :description "Ordered system prompt sections"}
            :skill-menu-text {:type :ignore :description "Advertised skill descriptions injected into the cached system prompt"}
            :provider        {:type :ignore :description "Tools-augmented LLM provider for this turn"}}})

(defn build-turn
  "Wraps a resolved charge with per-turn derived state. Charge already holds
   the resolved behavior (model, provider, soul, compaction, effort, etc.),
   so this only computes the genuinely per-turn fields and the
   tools-augmented provider that drive needs."
  [charge]
  (let [{:keys [session-key crew crew-members context-window
                model model-cfg provider]} charge
        root             (or (nexus/get :root) (get-in charge [:config :root]))
        session-store*   (or (:session-store charge) (nexus/get-in [:sessions :store]))
        sess             (or (:session-store charge)
                             (or (:session-store charge) (store/registered-store))
                             (when session-store* session-store*))
        session          (when sess (store/get-session sess session-key))
        charge           (assoc charge :cwd (or (:cwd charge) (:cwd session)))
        sections         (system-sections/resolve-sections {:crew crew :session-key session-key :cwd (:cwd charge)
                                                             :config (:config charge) :root root
                                                             :module-index (:module-index charge)})
        crew-cfg         (get crew-members crew)
        recall-tools     nil
        allowed-tools    (tools-config/merge-allowed-tools (tools-config/allowed-tool-names crew-members crew (:config charge))
                                              (concat (mapcat :tools sections) recall-tools))
        boot-files       (:text (first (filter #(= :boot-files (:id %)) sections)))
        rules-text       (:text (first (filter #(= :rules (:id %)) sections)))
        augmented        (augment-provider root provider session-key context-window
                                           (select-keys (or model-cfg {})
                                                        [:thinking-budget-max :think-mode :stateful :vision]))]
    (log/debug :turn/context-resolved
               :session session-key
               :crew crew
               :model model
               :provider (some-> provider api/display-name)
               :effort (:effort charge)
               :context-window context-window
               :crew-keys (vec (keys crew-members))
               :crew-cfg-keys (some-> (:crew-cfg charge) keys vec)
               :allowed-tools-count (count allowed-tools)
               :allowed-tools (some-> allowed-tools sort vec)
               :cwd (:cwd charge))
    (schema/conform! turn-schema
                     {:charge          charge
                      ;; convenience accessors for storage helpers — same value, derived via session-store helper
                      :session-store   session-store*
                      :root            root
                      :effort          (when (get (or model-cfg {}) :allows-effort true)
                                         (:effort charge))
                      :allowed-tools   allowed-tools
                      :boot-files      boot-files
                      :rules-text      rules-text
                      :skill-menu-text (:text (first (filter #(= :skill-menu (:id %)) sections)))
                      :sections        sections
                      :provider        augmented})))
