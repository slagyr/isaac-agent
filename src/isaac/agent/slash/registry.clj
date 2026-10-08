(ns isaac.agent.slash.registry
  (:require
    [isaac.agent.slash.builtin :as builtin]
    [isaac.agent.slash.prompt :as prompt]
    [isaac.foundation.module.loader :as module-loader]))

(defonce ^:private providers* (atom {}))

(defn register! [{:keys [id] :as provider}]
  (swap! providers* assoc id provider)
  id)

(defn unregister! [id]
  (swap! providers* dissoc id))

(defn clear! []
  (reset! providers* {})
  (module-loader/deactivate-foundation!))

(defn register-slash-entry! [[provider-id entry]]
  (let [factory (some-> (:factory entry) requiring-resolve var-get)]
    (register! (assoc (factory) :id provider-id :rank (or (:rank entry) 500)))))

(defn- activate-all! [module-index]
  (builtin/ensure-registered!)
  (register! (assoc (prompt/provider) :id :prompt-templates :rank 900))
  (doseq [[module-id entry] module-index
          :let [contribs (get-in entry [:manifest :isaac.agent/slash-commands])]
          :when (seq contribs)]
    (module-loader/activate! module-id module-index)
    (doseq [pair contribs]
      (register-slash-entry! pair))))

(defn- candidates [module-index ctx]
  (activate-all! module-index)
  (for [{:keys [commands rank] :as provider} (vals @providers*)
        command (commands ctx)]
    (assoc command :provider provider :rank (or (:rank command) rank))))

(defn lookup
  ([name] (lookup name nil nil))
  ([name module-index] (lookup name module-index nil))
  ([name module-index ctx]
   (first (sort-by :rank (filter #(= (str name) (:name %)) (candidates module-index ctx))))))

(defn answer [name session-key input module-index ctx]
  (->> (candidates module-index ctx)
       (filter #(= (str name) (:name %)))
       (sort-by :rank)
       (some (fn [{:keys [provider]}]
               ((:handle provider) name session-key input ctx)))))

(defn all-commands
  ([] (all-commands nil nil))
  ([module-index] (all-commands module-index nil))
  ([module-index ctx]
   (->> (candidates module-index ctx)
        (sort-by :rank)
        (reduce (fn [seen command] (update seen (:name command) #(or % command))) {})
        vals
        (map #(dissoc % :provider :rank))
        (sort-by :name)
        vec)))
