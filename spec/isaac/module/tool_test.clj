(ns isaac.module.tool-test)

(declare handle)

(defn tool-spec [_cfg]
  {:description "Echo from module"
   :parameters  {:type "object"}
   :handler     handle})

(defn handle [args]
  {:result (str "module:" (:msg args))})
