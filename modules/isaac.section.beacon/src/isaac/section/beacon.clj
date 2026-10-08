(ns isaac.section.beacon)
(defn section [{:keys [crew]}]
  {:text (str "Beacon lit for " crew ".") :tools #{"beacon__ping"}})
(defn ping-tool [_]
  {:description "Ping the beacon" :parameters {:type "object" :properties {}}
   :handler (fn [_] "pong")})
