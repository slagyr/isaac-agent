(ns isaac.agent.session.context-mode
  "Registered transformations of a turn's transcript before model delivery."
)

(defonce ^:private modes* (atom {}))

(defn register! [id {:keys [factory] :as entry}]
  (swap! modes* assoc (keyword id) (assoc entry :factory factory)))

(defn register-entry! [[id {:keys [factory] :as entry}]]
  (register! id (assoc entry :factory (some-> factory requiring-resolve var-get))))

(defn ensure-builtins! []
  (register! :full {:factory identity})
  (register! :reset {:factory #(if-let [current (last %)] [current] [])}))

(defn known [module-index]
  (ensure-builtins!)
  (->> (concat (keys @modes*)
               (mapcat #(keys (get-in % [:manifest :isaac.agent/context-mode])) (vals module-index)))
       (map name) distinct sort vec))

(defn requirement [mode module-index]
  (or (:requires (get @modes* mode))
      (some #(get-in % [:manifest :isaac.agent/context-mode mode :requires]) (vals module-index))))

(defn select-transcript [mode transcript]
  (ensure-builtins!)
  (let [factory (:factory (get @modes* (or mode :full)))]
    (if factory
      (factory transcript)
      (throw (ex-info (str "unknown context mode " mode) {:mode mode})))))

(defn prepare-turn! [mode session-store session-key input]
  (ensure-builtins!)
  (when-let [prepare (:prepare (get @modes* (or mode :full)))]
    (prepare session-store session-key input)))

(defn wait-for! [mode session-key]
  (ensure-builtins!)
  (when-let [observer (:wait-for (get @modes* (or mode :full)))]
    ((requiring-resolve 'isaac.agent.session.session-observer/await!) session-key observer)))
