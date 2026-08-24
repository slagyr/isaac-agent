(ns isaac.drive.observer
  "Named turn-observer registry plus the built-in :lookout narrator.

  Observers witness a turn's lifecycle. REGISTERED observers see every turn;
  SUBMITTED refs ride a single request (name or [name params]). Unknown names
  refuse before dispatch. A throwing observer is isolated — log and continue."
  (:require
    [clojure.string :as str]
    [isaac.logger :as log]))

(defprotocol TurnObserver
  (on-turn-started [this ctx])
  (on-turn-ended [this ctx outcome])
  (on-turn-died [this ctx reason]))

(defonce ^:private factories* (atom {}))
(defonce ^:private ambient* (atom []))

(defn register!
  "Register an observer factory under `name` (keyword). The factory is
   (fn [params] observer) — params is nil for a name-only ref."
  [name factory]
  (swap! factories* assoc (keyword name) factory)
  name)

(defn unregister! [name]
  (swap! factories* dissoc (keyword name)))

(defn attach!
  "Attach an observer instance so it witnesses every turn (ambient)."
  [observer]
  (swap! ambient* conj observer)
  observer)

(defn detach! [observer]
  (swap! ambient* (fn [obs] (vec (remove #(identical? observer %) obs)))))

(defn ambient
  "Observers attached to every turn, independent of submitted refs."
  []
  @ambient*)

(defn clear-ambient! []
  (reset! ambient* []))

(defn clear! []
  (reset! factories* {})
  (clear-ambient!))

(defn resolve
  "Resolve a registered factory by name and invoke it with no params.
   Returns nil when the name is unknown."
  [name]
  (when-let [factory (get @factories* (keyword name))]
    (factory nil)))

(defn- ref-name [obs-ref]
  (if (sequential? obs-ref)
    (first obs-ref)
    obs-ref))

(defn- ref-params [obs-ref]
  (when (sequential? obs-ref)
    (vec (rest obs-ref))))

(defn resolve-ref
  "Resolve a submitted observer ref — a name keyword or [name & params].
   Returns nil when the name is unknown."
  [obs-ref]
  (when-let [factory (get @factories* (keyword (ref-name obs-ref)))]
    (factory (ref-params obs-ref))))

(defn parse-ref
  "Parse a CLI --observer value: `lookout` or `foreman:bean-work/bn-7`."
  [s]
  (let [s (str s)]
    (if-let [colon (str/index-of s ":")]
      (let [name   (keyword (subs s 0 colon))
            params (str/split (subs s (inc colon)) #"/")]
        (into [name] params))
      (keyword s))))

(defn outcome
  "Classify a turn result for observers. Success is :ok. Failures are
   {:kind :error :reason <message>}."
  [result]
  (if (or (:error result)
          (:unavailable? result)
          (get-in result [:response :error]))
    {:kind   :error
     :reason (or (:message result)
                 (some-> (:error result) name)
                 (some-> (get-in result [:response :error]) str)
                 "unknown")}
    :ok))

(defn- invoke-one [obs method ctx extra]
  (try
    (case method
      :on-turn-started (on-turn-started obs ctx)
      :on-turn-ended   (on-turn-ended obs ctx extra)
      :on-turn-died    (on-turn-died obs ctx extra))
    (catch Throwable t
      (log/warn :turn/observer-failed
                :method method
                :error (.getMessage t)
                :ex-class (.getName (class t))))))

(defn for-turn
  "Merge ambient observers with submitted ones for this turn.
   Ambient first, then submitted. Zero ambient is identity."
  [submitted]
  (into (vec (ambient)) submitted))

(defn notify!
  "Fire `method` on every observer. Extra is the outcome (ended) or reason (died).
   Isolated: a throwing observer is logged and the rest still run."
  ([observers method ctx]
   (notify! observers method ctx nil))
  ([observers method ctx extra]
   (doseq [obs observers]
     (invoke-one obs method ctx extra))))

(defn lookout
  "Built-in observer: narrates turn started / turn ended (ok|error ...) on stdout."
  ([] (lookout nil))
  ([_params]
   (reify TurnObserver
     (on-turn-started [_ _ctx]
       (println "turn started"))
     (on-turn-ended [_ _ctx outcome]
       (if (= :ok outcome)
         (println "turn ended (ok)")
         (let [reason (or (:reason outcome) "unknown")]
           (println (str "turn ended (error " reason ")")))))
     (on-turn-died [_ _ctx reason]
       (println (str "turn ended (error " reason ")"))))))

(defn ensure-builtins!
  "Register agent-owned observers. Safe to call repeatedly."
  []
  (register! :lookout lookout))

(defn unknown-observer-message [obs-ref]
  (str "unknown observer: " (name (ref-name obs-ref))))

(defn resolve-submitted
  "Resolve a seq of submitted refs. Returns {:observers [...]} or
   {:error :unknown-observer :message ... :ref ...}."
  [refs]
  (ensure-builtins!)
  (loop [remaining refs
         acc       []]
    (if-let [obs-ref (first remaining)]
      (if-let [obs (resolve-ref obs-ref)]
        (recur (rest remaining) (conj acc obs))
        {:error   :unknown-observer
         :message (unknown-observer-message obs-ref)
         :ref     obs-ref})
      {:observers acc})))
