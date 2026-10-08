(ns isaac.agent.system-sections
  "Provider-neutral, ordered sections of a turn's cached system prefix."
  (:require
    [isaac.agent.session.context :as context]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.logger :as log]
    [isaac.foundation.module.loader :as module-loader]))

(defn register-entry!
  "Berth activation validates the factory; the turn reads the current module index."
  [[_ {:keys [factory]}]]
  (requiring-resolve factory))

(defn- builtins []
  [[:boot-files 100 #(when-let [text (context/read-boot-files (:cwd %) (:fs %))] {:text text})]
   [:rules 200 #(when-let [text (context/read-rules-text (:config %) (:root %) (:cwd %) (:fs %))] {:text text})]
   [:skill-menu 300 #(when-let [disclosure (context/read-skill-disclosure (:config %) (:root %) (:cwd %) (:fs %))]
                       {:text (:menu-text disclosure) :tools (:tool-names disclosure)})]])

(defn- entries [module-index]
  (concat (for [[id order f] (builtins)] {:id id :order order :factory f :module :isaac.agent})
          (for [[module {:keys [manifest]}] module-index
                [id {:keys [factory order]}] (:isaac.agent/system-sections manifest)]
            {:id id :order (or order 500) :factory factory :module module})))

(defn resolve-sections
  "Call each contributor once with crew, cwd, config, root and fs. Bad contributors are skipped."
  [{:keys [module-index] :as facts}]
  (let [facts (assoc facts :fs (or (:fs facts) (fs/instance)))]
    (->> (entries module-index)
         (sort-by (juxt :order (comp str :id)))
         (keep (fn [{:keys [id factory module]}]
                 (let [work (future
                              (try
                                (when (and (not= module :isaac.agent) module-index)
                                  (module-loader/activate! module module-index))
                                {:result ((if (symbol? factory) (deref (requiring-resolve factory)) factory) facts)}
                                (catch Throwable e {:error e})))
                       outcome (deref work 500 ::timed-out)]
                   (when (= ::timed-out outcome)
                     (future-cancel work))
                   (if (or (= ::timed-out outcome) (:error outcome))
                     (do (log/warn :system-section/failed :section id :module (name module)
                                   :error (if (= ::timed-out outcome) "timeout" (.getMessage (:error outcome))))
                         nil)
                     (when-let [result (:result outcome)]
                       (assoc result :id id))))))
         vec)))
