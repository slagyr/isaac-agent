(ns isaac.agent.drive.tools-config
  (:require
    [isaac.agent.config.defaults :as defaults]
    [isaac.agent.llm.tool-loop :as tool-loop]
    [isaac.agent.tool.names :as names]
    [isaac.agent.tool.registry :as tool-registry]))

(defn- parse-long-or-raw [raw]
  (cond
    (number? raw) (long raw)
    (string? raw) (parse-long raw)
    :else raw))

(defn- crew-cycle [config crew crew-cfg]
  (or (:cycle crew-cfg)
      (get-in config [:crew (keyword crew) :cycle])
      (get-in config [:crew crew :cycle])
      {}))

(def default-continuations
  "How many times a wrapped-up turn re-drives itself when the crew says
   nothing. Continuations are a last resort — checkpoints inside the turn are
   the save point (isaac-tic5)."
  2)

(defn resolve-cycle [{:keys [cycle config crew crew-cfg]}]
  (let [defaults      (or (defaults/cycle-knobs config) {})
        layered       (merge defaults (crew-cycle config crew crew-cfg) (or cycle {}))
        limit         (or (:limit layered)
                          tool-loop/default-max-loops)
        continuations (if (some? (:continuations layered))
                        (:continuations layered)
                        default-continuations)]
    (assoc layered
           :limit (parse-long-or-raw limit)
           :continuations (parse-long-or-raw continuations))))

(defn- resolve-cycle-limit [opts]
  (:limit (resolve-cycle opts)))

(defn resolve-max-parallel-tools [{:keys [config crew crew-cfg]}]
  (let [raw (or (get-in crew-cfg [:tools :max-parallel])
                (get-in config [:crew (keyword crew) :tools :max-parallel])
                (get-in config [:crew crew :tools :max-parallel])
                (defaults/max-parallel config)
                tool-loop/default-max-parallel-tools)]
    (parse-long-or-raw raw)))

(defn resolve-tool-timeout-ms
  "Crew override of the tool-call deadline, else the global default
   (:defaults :tools :timeout-ms). Nil here is fine — the registry falls
   back to its own hard-coded default when neither is configured; a tool's
   own declared :timeout-ms still outranks both (isaac-4g2k)."
  [{:keys [config crew crew-cfg]}]
  (parse-long-or-raw
    (or (get-in crew-cfg [:tools :timeout-ms])
        (get-in config [:crew (keyword crew) :tools :timeout-ms])
        (get-in config [:crew crew :tools :timeout-ms])
        (defaults/tool-timeout-ms config))))

(defn tool-name [tool]
  (or (:name tool)
      (get-in tool [:function :name])))

(defn- declared-wire-names [tools]
  (->> (concat (names/policy-list (:allow tools))
               (names/policy-list (:deny tools)))
       (remove #{names/POLICY_ALL})
       (remove names/glob-token?)
       (keep (fn [token]
               (or (names/wire-name token)
                   (cond
                     (keyword? token) (name token)
                     (string? token)  token
                     :else            (str token)))))))

(defn allowed-tool-names
  "Wire names allowed for this turn via the four-step cascade (isaac-da0r).
   Empty/missing :allow is deny-all. Crew overlays global; omit crew :tools
   inherits the global result. Skill auto-tools are merged by the caller.
   Candidates are registered tools plus any exact tokens declared in policy
   (test fixtures / not-yet-activated modules)."
  [crew-members crew-id config]
  (let [global-tools (defaults/tools config)
        crew-tools   (get-in crew-members [crew-id :tools])
        observers    (map keyword (or (get-in crew-members [crew-id :observers]) []))
        _            (tool-registry/ensure-policy-tools! (:module-index config)
                                                         (concat (names/policy-list (:allow global-tools))
                                                                 (names/policy-list (:allow crew-tools))
                                                                 (when (some #{:episodes} observers)
                                                                   ["recall__search" "recall__scene"])))
        registered   (map :name (tool-registry/all-tools))
        declared     (concat (declared-wire-names global-tools)
                             (declared-wire-names crew-tools))
        candidates   (distinct (concat registered declared))]
    (let [allowed (->> candidates
                       (filter #(names/cascade-allowed? global-tools crew-tools %))
                       set)
          observers (map keyword (or (get-in crew-members [crew-id :observers]) []))
          deny (names/policy-list (:deny crew-tools))
          recall-ok? (and (some #{:episodes} observers)
                          (not (names/covers? deny "recall/*"))
                          (not (names/covers? deny "recall__search")))]
      (cond-> allowed
        recall-ok? (into #{"recall__search" "recall__scene"})))))

(defn active-tools [_p allowed-tools module-index]
  (not-empty (if module-index
               (tool-registry/tool-definitions allowed-tools module-index)
               (tool-registry/tool-definitions allowed-tools))))

(defn merge-allowed-tools [crew-tools auto-tools]
  (not-empty (into (set (or crew-tools [])) auto-tools)))
