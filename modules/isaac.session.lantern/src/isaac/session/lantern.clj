(ns isaac.session.lantern)

(defn porthole [transcript]
  (let [entries (filter #(= "message" (:type %)) transcript)
        current (last entries)
        previous (last (filter #(= "assistant" (get-in % [:message :role])) (butlast entries)))]
    (vec (remove nil? [previous current]))))
