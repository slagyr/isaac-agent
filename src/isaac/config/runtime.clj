(ns isaac.config.runtime
  "Agent-side companion to isaac.config.loader. Ensures the session store
   when a committed config is installed. Server-only lifecycle (reconcile,
   berth activation, hot reload) lives in isaac-server's config.runtime.

   Each fn delegates to its source at call time, so `with-redefs` on the
   underlying fn still takes effect for callers through this API."
  (:require
    [isaac.config.install :as install]))

(defn install!
  "Ensures the session store for a committed config snapshot. opts keys:
   :config (required). Returns {:config config}."
  [opts]
  (install/install! opts))