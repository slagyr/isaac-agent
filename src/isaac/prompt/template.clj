(ns isaac.prompt.template
  (:require
    [clojure.string :as str]))

(def ^:private placeholder-re
  #"\{\{(\w+)\}\}")

(defn- var-present? [vars word]
  (let [k (keyword word)]
    (or (contains? vars k)
        (contains? vars word)
        (contains? vars (name k)))))

(defn- var-value [vars word]
  (let [k (keyword word)]
    (or (get vars k)
        (get vars word)
        (get vars (name k)))))

(defn- missing-replacement [policy placeholder]
  (case policy
    :empty  ""
    :marker "(missing)"
    :keep   placeholder
    ""))

(defn render
  "Replace Mustache-lite `{{word}}` placeholders in `template` using `vars`.
   `on-missing` controls placeholders with no binding:
   `:keep` leaves them as-is, `:empty` -> \"\", `:marker` -> \"(missing)\"."
  [template vars & {:keys [on-missing] :or {on-missing :keep}}]
  (str/replace (or template "") placeholder-re
               (fn [[placeholder word]]
                 (if (var-present? vars word)
                   (str (var-value vars word))
                   (missing-replacement on-missing placeholder)))))