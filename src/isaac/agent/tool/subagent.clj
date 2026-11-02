(ns isaac.agent.tool.subagent
  "Spawn a child session and deliver its final turn to its parent."
  (:require
    [clojure.string :as str]
    [isaac.agent.config.defaults :as defaults]
    [isaac.agent.drive.observer :as observer]
    [isaac.agent.session.context :as context]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.tool.fs-bounds :as bounds]
    [isaac.foundation.config.loader :as loader]))

(defn description []
  (let [aliases (->> (keys (:models (loader/snapshot "subagent model aliases")))
                     (map name) sort (str/join ", "))]
    (str "Start a child turn in a separate session and return its handle immediately. "
         "Configured model aliases: " aliases)))

(defn- deliver! [parent child crew model {:keys [content result request-id]}]
  (let [submit! (requiring-resolve 'isaac.agent.turn.submit/submit!)
        cycles  (max 1 (count (:cycle-usages result)))
        usage   (:usage result)
        tokens  (+ (or (:prompt-tokens usage) 0) (or (:output-tokens usage) 0))
        stats   (str "crew: " crew " model: " model " cycles: " cycles
                     " tokens: " tokens " session: " child
                     (when (= :cycle-limit (:ended-by result)) " exhausted: true"))]
    (submit! {:frequencies {:session [parent] :create :never}
              :origin {:kind :subagent :session child}
              :from {:kind :subagent :id child}
              :id (str "subagent-" child "-" request-id)
              :prompt (str (or content (:message result) "") "\n" stats)})))

(defn observer-factory []
  (fn [[parent child crew model]]
    (reify observer/TurnObserver
      (on-turn-started [_ _])
      (on-turn-ended [_ ctx _] (deliver! parent child crew model ctx))
      (on-turn-died [_ ctx reason]
        (deliver! parent child crew model (assoc ctx :content (str "Child failed: " reason)))))))

(defn spawn-tool [raw-args]
  (let [args    (bounds/string-key-map raw-args)
        cfg     (loader/snapshot "subagent spawn")
        ss      (bounds/session-store args)
        parent  (store/get-session ss (get args "session_key"))
        crew    (or (get args "crew") (:crew parent) (defaults/crew-id cfg))
        model   (get args "model")
        effort  (when-let [n (get args "effort")]
                  (if (string? n) (parse-long n) n))]
    (cond
      (nil? parent) {:isError true :error "parent session not found"}
      (:parent parent) {:isError true :error "subagents cannot spawn children"}
      (not (contains? (:crew cfg) crew)) {:isError true :error (str "unknown crew: " crew)}
      (and model (not (contains? (:models cfg) model)))
      {:isError true :error (str "unknown model: " model "; available: "
                                  (str/join ", " (sort (map name (keys (:models cfg))))))}
      :else
      (let [child (store/mint-name (bounds/root args))
            opts  (cond-> {:config cfg :session-store ss :crew crew :parent (:id parent)
                           :cwd (:cwd parent) :origin {:kind :subagent}}
                    model (assoc :model model)
                    (some? effort) (assoc :effort effort))]
        (context/create-with-resolved-behavior! child opts)
        (observer/ensure-builtins!)
        (let [submit! (requiring-resolve 'isaac.agent.turn.submit/submit!)
              turn    (submit! {:frequencies {:session [child] :create :never}
                                :prompt (get args "prompt")
                                :crew crew
                                :origin {:kind :subagent :parent (:id parent)}
                                :observers [[:subagent (:id parent) child crew
                                             (or model (get-in cfg [:crew crew :model])
                                                 (defaults/model-id cfg))]]
                                :effort effort})]
          {:result (str "{\"handle\":\"" (:id turn) "\",\"session\":\"" child "\"}")})))))
