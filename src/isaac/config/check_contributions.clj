(ns isaac.config.check-contributions
  "Canonical :isaac.config/check contribution map for the server module.
   resources/isaac-manifest.edn must stay aligned with this data.")

(def server
  {:comm-reserved-schema {:fn 'isaac.config.checks/check-comm-reserved-schema}
   :manifest-refs         {:fn 'isaac.config.checks/check-manifest-refs}
   :resolved-providers    {:fn 'isaac.config.checks/check-resolved-providers}})