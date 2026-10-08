(ns isaac.agent.slash.prompt
  (:require
    [isaac.agent.prompt.catalog :as catalog]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]))

(defn- catalog-opts [ctx]
  (let [root (or (get-in ctx [:config :root]) (:root ctx) (nexus/get :root) (loader/root))
        fs*  (or (:fs ctx) (nexus/get :fs) (fs/instance))]
    (when (and root fs*)
      {:config (or (:config ctx) (loader/snapshot "slash prompt provider"))
       :cwd (:cwd ctx) :root root :fs fs*})))

(defn provider []
  {:commands (fn [ctx]
               (if-let [opts (catalog-opts ctx)]
                 (->> (catalog/resolve-catalog opts) :commands vals
                      (map #(select-keys % [:name :description :params])))
                 []))
   :handle (fn [name _session-key input ctx]
             (when-let [opts (catalog-opts ctx)]
               (catalog/resolve-command-prompt opts name (:args input))))})
