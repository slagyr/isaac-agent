(ns isaac.agent.config.check-contributions
  "Canonical :isaac.config/check contribution map for the server module.
   resources/isaac-manifest.edn must stay aligned with this data.")

(def server
  {:comm-reserved-schema    {:fn 'isaac.agent.config.checks/check-comm-reserved-schema}
   :default-frequencies     {:fn 'isaac.agent.config.checks/check-default-frequencies}
   :comm-types              {:fn 'isaac.agent.config.checks/check-comm-types}
   :crew-broad-directories  {:fn 'isaac.agent.config.checks/check-crew-broad-directories}
   :crew-model-aliases      {:fn 'isaac.agent.config.checks/check-crew-model-aliases}
   :manifest-refs           {:fn 'isaac.agent.config.checks/check-manifest-refs}
   :resource-pools          {:fn 'isaac.agent.config.checks/check-resource-pools}
   :resolved-providers      {:fn 'isaac.agent.config.checks/check-resolved-providers}
   :session-policy          {:fn 'isaac.agent.config.checks/check-session-policy}
   :tool-allow-tokens       {:fn 'isaac.agent.config.checks/check-tool-allow-tokens}
   :retired-cycle-limit     {:fn 'isaac.agent.config.checks/check-retired-cycle-limit}})
