(ns isaac.agent.module
  "The isaac-agent module: the turn loop — session, bridge, comm, llm, tools,
   slash, providers — plus isaac.api. Its berths and builtin contributions are
   declared in the manifest; this factory just yields the module instance."
  (:require
    [isaac.module.protocol :as module]))

(defn create-module []
  (module/module))
