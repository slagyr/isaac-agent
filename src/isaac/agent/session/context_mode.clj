(ns isaac.agent.session.context-mode
  "Registered transformations of a turn's transcript before model delivery.")

(defonce ^:private modes* (atom {}))

(defn register! [id {:keys [factory] :as entry}]
  (swap! modes* update (keyword id) merge (assoc entry :factory factory)))

(defn- resolve-fn [v]
  (cond
    (fn? v) v
    (var? v) (var-get v)
    (symbol? v) (some-> v requiring-resolve var-get)
    :else v))

(defn register-entry! [[id {:keys [factory prepare wrap-input] :as entry}]]
  (register! id (cond-> (assoc entry :factory (resolve-fn factory))
                  prepare (assoc :prepare (resolve-fn prepare))
                  wrap-input (assoc :wrap-input (resolve-fn wrap-input)))))

(defn identity-transcript [transcript] transcript)
(defn reset-transcript [transcript] (if-let [current (last transcript)] [current] []))

(defn ensure-builtins! []
  (swap! modes*
    (fn [m]
      (cond-> m
        (not (contains? m :full)) (assoc :full {:factory identity-transcript})
        (not (contains? m :reset)) (assoc :reset {:factory reset-transcript})))))

(defn wrap-input [mode input]
  (ensure-builtins!)
  (if-let [wrap (:wrap-input (get @modes* (or mode :full)))]
    (wrap input)
    input))

(defn known [module-index]
  (ensure-builtins!)
  (->> (concat (keys @modes*)
               (mapcat #(keys (get-in % [:manifest :isaac.agent/context-mode])) (vals module-index)))
       (map name) distinct sort vec))

(defn requirement [mode module-index]
  (or (:requires (get @modes* mode))
      (some #(get-in % [:manifest :isaac.agent/context-mode mode :requires]) (vals module-index))))

(defn- contributed-factory [mode module-index]
  (some->> (vals module-index)
           (some #(get-in % [:manifest :isaac.agent/context-mode mode :factory]))
           requiring-resolve var-get))

(defn select-transcript
  ([mode transcript] (select-transcript mode transcript nil))
  ([mode transcript module-index]
   (ensure-builtins!)
   (let [factory (or (:factory (get @modes* (or mode :full)))
                     (contributed-factory mode module-index))]
     (if factory
       (factory transcript)
       (throw (ex-info (str "unknown context mode " mode) {:mode mode}))))))

(defn prepare-turn! [mode session-store session-key input]
  (ensure-builtins!)
  (when-let [prepare (:prepare (get @modes* (or mode :full)))]
    (prepare session-store session-key input)))

(defn wait-for! [mode session-key]
  (ensure-builtins!)
  (when-let [observer (:wait-for (get @modes* (or mode :full)))]
    ((requiring-resolve 'isaac.agent.session.session-observer/await!) session-key observer)))
