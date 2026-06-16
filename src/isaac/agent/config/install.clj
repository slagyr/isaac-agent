(ns isaac.agent.config.install
  "Agent-side config install: ensures the session store only. Comm/service
   reconcile and berth activation are server-boot responsibilities (isaac-95lv)."
  (:require
    [isaac.session.store.spi :as store]))

(defn- ensure-store! [config]
  (when-not (store/registered-store)
    (when-let [root (:root config)]
      (store/register! config root))))

(defn install!
  "Commit an already-installed config snapshot into the agent nexus by
   ensuring the session store. The snapshot must already be committed
   (via load-config! or dangerously-install-config!).

   opts:
     :config - the committed config map (required)
   Returns {:config config}."
  [{:keys [config]}]
  (ensure-store! config)
  {:config config})