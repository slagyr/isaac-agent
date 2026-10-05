(ns isaac.agent.prompt.template
  (:require
    [isaac.foundation.template :as template]))

(defn render
  "Render prompt placeholders; missing bindings are kept by default."
  [text vars & {:keys [on-missing] :or {on-missing :keep}}]
  (template/render text vars {:on-missing on-missing}))
