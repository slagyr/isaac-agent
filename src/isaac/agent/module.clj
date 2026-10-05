(ns isaac.agent.module
  "The isaac-agent module: the turn loop — session, bridge, comm, llm, tools,
   slash, providers — plus isaac.agent.api. Its berths and builtin contributions are
   declared in the manifest; this factory just yields the module instance."
  (:require
    [isaac.agent.config.checks]
    [isaac.foundation.module.protocol :as module]
    [isaac.agent.turn.cli]))

(defn create-module []
  (module/module))
