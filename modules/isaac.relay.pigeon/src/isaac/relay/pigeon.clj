(ns isaac.relay.pigeon
  (:refer-clojure :exclude [send])
  (:require
    [isaac.agent.turn.submit :as submit]))

(defn send [args]
  (let [{:keys [session input id for]} (into {} (map (fn [[k v]] [(keyword k) v]) args))]
  (submit/submit! (cond-> {:frequencies {:session session} :prompt input :id id}
                    for (assoc :from {:kind :cli} :for {:kind :contact :id for})))
    "Sent."))

(defn send-tool [_]
  {:description "Send a pigeon turn"
   :parameters {:type "object"
                :properties {:session {:type "string"} :input {:type "string"}
                             :id {:type "string"} :for {:type "string"}}
                :required ["session" "input" "id"]}
   :handler send})
