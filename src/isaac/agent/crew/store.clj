(ns isaac.agent.crew.store
  (:require [isaac.agent.session.store.spi :as session-store]))

(defn tags-of [crew-cfg]
  (session-store/tags->set (:tags crew-cfg)))

(defn has-tag? [crew-cfg tag]
  (contains? (tags-of crew-cfg) tag))

(defn by-tags [crew-map tag-set]
  (into {}
        (filter (fn [[_ crew-cfg]]
                  (every? #(has-tag? crew-cfg %) tag-set)))
        crew-map))
