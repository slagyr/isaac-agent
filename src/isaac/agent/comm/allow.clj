(ns isaac.agent.comm.allow
  "Outbound recipient allow-list for comm slots. A slot's :comm/allow-to
   names the targets comm_send may address: missing = no filter (today's
   behavior), a list = only those targets, empty = fail-closed. Mirrors
   the inbound :imessage/allow-from contract.")

(defn record-target
  "The recipient a delivery record carries. Comms name it in their own
   keyword namespace (:imessage/target, :discord/target); the generic
   :target is optional."
  [record]
  (or (:target record)
      (some (fn [[k v]]
              (when (and (keyword? k) (= "target" (name k))) v))
            record)))

(defn enabled? [slot-cfg]
  (contains? slot-cfg :comm/allow-to))

(defn allowed?
  "True when `slot-cfg` permits a send of `record`."
  [slot-cfg record]
  (or (not (enabled? slot-cfg))
      (let [target (record-target record)]
        (boolean (and (some? target)
                      (some #(= (str target) (str %)) (:comm/allow-to slot-cfg)))))))
