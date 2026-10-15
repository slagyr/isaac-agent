(ns isaac.section.lantern)

(defn section [{:keys [session-key]}]
  {:text (str "Lantern lit on " session-key ".")})
