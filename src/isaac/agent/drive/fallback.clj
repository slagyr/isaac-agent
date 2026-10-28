(ns isaac.agent.drive.fallback
  (:require
    [clojure.string :as str]
    [isaac.agent.config.resolve :as config-resolve]
    [isaac.agent.drive.request :as turn-request]
    [isaac.agent.llm.api.protocol :as api]
    [isaac.agent.session.context :as session-ctx]))

(defn search
  "First link for which engage! returns a request, plus the links still waiting.
   engage! is called as (engage! link) and returns a request or nil."
  [links engage!]
  (loop [links links]
    (when-let [link (first links)]
      (if-let [req (engage! link)]
        [link req (rest links)]
        (recur (rest links))))))

(defn chain-link [cfg crew-id model-id root session-key]
  (let [resolved (config-resolve/resolve-crew-context cfg crew-id {:model-override model-id})
        window   (:context-window resolved)
        provider (turn-request/augment-provider root (:provider resolved) session-key window
                                   (select-keys (or (:model-cfg resolved) {})
                                                [:thinking-budget-max :think-mode :stateful :vision]))]
    {:id             model-id
     :model          (:model resolved)
     :model-cfg      (:model-cfg resolved)
     :context-window window
     :provider       provider
     :provider-name  (when provider (api/display-name provider))}))

(defn fallback-links [ctx session-key]
  (let [charge (:charge ctx)
        cfg    (:config charge)
        root   (:root ctx)
        crew   (:crew charge)]
    (mapv #(chain-link cfg crew % root session-key)
          (->> (get-in charge [:crew-cfg :model-fallback])
               (map session-ctx/normalize-model-ref)
               (remove str/blank?)))))
