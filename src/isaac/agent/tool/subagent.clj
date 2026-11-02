(ns isaac.agent.tool.subagent
  "Spawn a child session and deliver its final turn to its parent."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.agent.bridge.cancellation :as cancellation]
    [isaac.agent.config.defaults :as defaults]
    [isaac.agent.drive.observer :as observer]
    [isaac.agent.session.context :as context]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.tool.fs-bounds :as bounds]
    [isaac.agent.turn.queue :as queue]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.logger :as log]))

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
      (on-turn-started [_ _]
        (log/info :subagent/started :parent parent :session child :crew crew :model model))
      (on-turn-ended [_ ctx outcome]
        (let [result  (:result ctx)
              cycles  (max 1 (count (:cycle-usages result)))
              usage   (:usage result)
              tokens  (+ (or (:prompt-tokens usage) 0) (or (:output-tokens usage) 0))
              outcome (if (= :cancelled (:ended-by result)) :cancelled
                          (if (= :ok outcome) :ok :failed))]
          (queue/update-turn! (:request-id ctx) {:cycles cycles :tokens tokens
                                                  :outcome outcome})
          (log/info :subagent/ended :parent parent :session child :crew crew :model model
                    :cycles cycles :tokens tokens :outcome (name outcome))
          (deliver! parent child crew model
                    (cond-> ctx (= :cancelled outcome) (assoc :content "Child cancelled.")))))
      (on-turn-died [_ ctx reason]
        (queue/update-turn! (:request-id ctx) {:outcome :failed})
        (log/info :subagent/ended :parent parent :session child :crew crew :model model
                  :cycles 0 :tokens 0 :outcome "failed")
        (deliver! parent child crew model (assoc ctx :content (str "Child failed: " reason)))))))

(defn- children [parent]
  (->> (queue/all-turns)
       (filter #(and (= :subagent (get-in % [:origin :kind]))
                     (= parent (get-in % [:origin :parent]))))))

(defn- unfinished? [turn]
  (not= :finished (:state turn)))

(defn- child-state [turn]
  (if (= :finished (:state turn))
    (case (:outcome turn) :cancelled "cancelled" :ok "done" "failed")
    (if (= :running (:state turn)) "running" "queued")))

(defn- child-view [ss turn]
  (let [session (store/get-session ss (:session turn))
        model   (or (:model session) (some #(when (= :subagent (first %)) (nth % 4)) (:observers turn)))]
    (cond-> {:handle (:id turn) :crew (:crew session) :model model
             :state (child-state turn) :cycles (or (:cycles turn) 0)}
      (and (:started-at turn) (:finished-at turn))
      (assoc :elapsed (max 0 (- (.toEpochMilli (java.time.Instant/parse (:finished-at turn)))
                                (.toEpochMilli (java.time.Instant/parse (:started-at turn)))))))))

(defn list-tool [raw-args]
  (let [args (bounds/string-key-map raw-args)
        ss   (bounds/session-store args)]
    (binding [queue/*root* (bounds/root args)]
      {:result (json/generate-string
                 {:children (mapv #(child-view ss %) (children (get args "session_key")))})})))

(defn cancel-tool [raw-args]
  (let [args   (bounds/string-key-map raw-args)
        parent (get args "session_key")
        handle (get args "handle")
        ss     (bounds/session-store args)]
    (binding [queue/*root* (bounds/root args)]
      (if-let [turn (some #(when (= handle (:id %)) %) (children parent))]
        (case (:state turn)
          :finished {:isError true :error "child already finished"}
          (:queued :held :waiting-session)
          (do (queue/update-turn! handle {:state :finished :outcome :cancelled})
              (deliver! parent (:session turn) (:crew (store/get-session ss (:session turn)))
                        (:model (store/get-session ss (:session turn)))
                        {:request-id handle :content "Child cancelled." :result {}})
              {:result "Child cancelled."})
          (do (cancellation/cancel! (:session turn)) {:result "Child cancellation requested."}))
        {:isError true :error "child handle not found"}))))

(defonce ^:private parent-locks (atom {}))

(defn- parent-lock [root parent]
  (get (swap! parent-locks update [root parent] #(or % (Object.))) [root parent]))

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
      (binding [queue/*root* (bounds/root args)]
        (locking (parent-lock (bounds/root args) (:id parent))
          (let [limit (or (get-in cfg [:crew crew :max-subagents])
                          (get-in cfg [:defaults :crew :max-subagents]) 4)
                running (filter unfinished? (children (:id parent)))]
            (if (>= (count running) limit)
              {:isError true :error (str "subagent limit reached (" limit " running): "
                                          (str/join ", " (map :id running)))}
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
                {:result (json/generate-string {:handle (:id turn) :session child})})))))))))
