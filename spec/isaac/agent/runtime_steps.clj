(ns isaac.agent.runtime-steps
  "Extends the foundation 'the Isaac runner is started' step (which boots
   the real isaac.runner — scheduler + components — the way `isaac server`
   does) so isaac-agent's own features get the real Agent module in the
   boot's :module-index.

   Without this, the foundation step's :module-index carries only
   :isaac.foundation (plus whatever a scenario injects via the
   :inject-module-index fixture hook), so none of :isaac.agent's
   :isaac/component contributions (:agent-lifecycle, :comm-delivery,
   :turn-queue) ever get instantiated when an Agent feature boots the real
   runner — there is nothing wrong to log; the component simply never
   exists in that module-index (isaac-2lc4).

   Advises the step function itself (rather than a before-scenario hook)
   because Background steps such as 'default Grover setup' call
   isaac.foundation.root-steps/initialize-root!, which (g/reset!)s all
   scenario state — a before-scenario hook's :server-config write would be
   wiped out before 'Given the Isaac runner is started' ever runs."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [gherclj.core :as g :refer [helper!]]
    [isaac.component.runtime-steps :as runtime-steps]))

(helper! isaac.agent.runtime-steps)

(def ^:private agent-manifest
  (delay (edn/read-string (slurp (io/resource "isaac-manifest.edn")))))

(defn- with-agent-module-injected! []
  (g/update! :server-config
             (fn [cfg]
               (update (or cfg {}) :inject-module-index
                       (fn [injected]
                         (assoc (or injected {})
                                :isaac.agent {:coord {} :manifest @agent-manifest :path nil}))))))

(alter-var-root #'runtime-steps/isaac-runner-is-started
  (fn [orig]
    (fn []
      (with-agent-module-injected!)
      (orig))))
