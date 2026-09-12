(ns isaac.config.agent-steps
  (:require
    [gherclj.core :as g]
    [isaac.config.api :as config]
    [isaac.config.cli.common :as config-cli]
    [isaac.foundation.cli-steps :as cli-steps]
    [isaac.startup.config-cache :as config-cache]))

(def ^:private load-resolved config/load-resolved)

(defn- capture-load-result [& args]
  (let [result (apply load-resolved args)]
    (g/assoc! :loaded-config-result result)
    result))

;; Every `isaac is run with` feature invocation models a separate production CLI
;; process. Force config subcommands to read the scenario's current mem-fs instead
;; of reusing main/run's process-threaded config or a prior invocation's cache.
(cli-steps/register-isaac-run-wrapper!
  (fn [thunk]
    (with-redefs-fn {#'config/load-resolved          capture-load-result
                     #'config-cli/threaded-config    (constantly nil)
                     #'config-cache/read-pre-sub     (constantly nil)}
      thunk)))
