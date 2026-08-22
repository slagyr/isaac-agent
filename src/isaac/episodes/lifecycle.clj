(ns isaac.episodes.lifecycle
  "Open/close live episodes and route a THREAD handle to the backing session."
  (:require
    [isaac.config.loader :as loader]
    [isaac.config.resolve :as resolve]
    [isaac.episodes.ids :as ids]
    [isaac.episodes.migrate :as migrate]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.provider :as llm-provider]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.session.context :as session-ctx]
    [isaac.session.store.spi :as session-store]
    [isaac.tool.memory :as memory])
  (:import
    (java.time Duration Instant)))

(def DEFAULT_TTL_MINUTES 60)

(defn- now-instant []
  (let [n (memory/now)]
    (cond
      (instance? Instant n) n
      (string? n) (Instant/parse (if (re-find #"[zZ]|[+-]\d{2}:?\d{2}$" n) n (str n "Z")))
      :else (Instant/now))))

(defn- parse-timestamp [ts]
  (cond
    (instance? Instant ts) ts
    (number? ts) (Instant/ofEpochMilli (long ts))
    (string? ts)
    (try
      (Instant/parse (if (re-find #"[zZ]|[+-]\d{2}:?\d{2}$" ts) ts (str ts "Z")))
      (catch Exception _
        nil))
    :else nil))

(defn- last-message-timestamp [transcript]
  (->> transcript
       (filter #(= "message" (:type %)))
       last
       :timestamp))

(defn ttl-minutes [cfg]
  (or (get-in cfg [:episodes :ttl-minutes]) DEFAULT_TTL_MINUTES))

(defn episodes-crew?
  "True when the crew is opted into :conversation :episodes."
  [cfg crew]
  (let [crew-id (if (keyword? crew) (name crew) (str crew))]
    (= :episodes (get-in (or cfg {}) [:crew crew-id :conversation]))))

(defn warm?
  "True when the last transcript message is within ttl-minutes of memory/now."
  [transcript ttl]
  (let [ts (parse-timestamp (last-message-timestamp transcript))]
    (boolean
      (when ts
        (let [age (.toMinutes (Duration/between ts (now-instant)))]
          (< age (or ttl DEFAULT_TTL_MINUTES)))))))

(defn- runtime-fs [fs*]
  (or fs* (nexus/get :fs) (fs/instance)))

(defn- runtime-root [root]
  (or root (loader/root)))

(defn- runtime-store [session-store*]
  (or session-store* (session-store/registered-store)))

(defn- gist-provider+model [cfg root provider model]
  (if (and provider model)
    {:provider provider :model model}
    (let [gist-ref (get-in cfg [:episodes :gist-model])
          model-id (or model
                       (when gist-ref (if (keyword? gist-ref) (name gist-ref) (str gist-ref)))
                       (get-in cfg [:defaults :model]))
          ctx (when model-id
                (try
                  (resolve/resolve-crew-context cfg "main" {:model-override model-id})
                  (catch Exception _
                    nil)))
          provider* (or provider (:provider ctx))
          provider* (if (and provider* root)
                      (llm-provider/make-provider (api/display-name provider*)
                                                  (merge (api/config provider*) {:root root}))
                      provider*)]
      {:provider provider* :model (or model (:model ctx) model-id)})))

(defn open-episode!
  "Create an :open episode record and a backing session named by the episode id.
   opts: :fs :root :crew :thread :session-store :parent-episode :cwd :origin
         :compaction :seed-compaction {:summary ...}"
  [{:keys [fs root crew thread session-store parent-episode cwd origin compaction seed-compaction]}]
  (let [fs*     (runtime-fs fs)
        root    (runtime-root root)
        ss      (runtime-store session-store)
        crew    (or crew "main")
        id      (ids/timestamped-id (str (now-instant)))
        episode (cond-> {:id     id
                         :crew   crew
                         :status :open
                         :thread thread}
                  parent-episode (assoc :parent-episode parent-episode))
        create-opts (cond-> {:crew          crew
                             :cwd           cwd
                             :origin        (or origin {:kind :cli})
                             :session-store ss}
                      compaction (assoc :compaction compaction))]
    (store/write-episode! fs* root episode [])
    (if ss
      (session-ctx/create-with-resolved-behavior! id create-opts)
      (log/warn :episodes/open-without-store :episode id :crew crew))
    (when-let [summary (:summary seed-compaction)]
      (when ss
        (session-store/append-compaction! ss id {:summary summary})))
    (log/info :episodes/opened :episode id :crew crew :thread thread)
    episode))

(defn close-episode!
  "Seal an open episode via the migrate/segment pipeline. Preserves :thread
   and :parent-episode on the closed record. No index writes."
  [{:keys [fs root crew episode-id session-store provider model cfg]}]
  (let [fs*     (runtime-fs fs)
        root    (runtime-root root)
        ss      (runtime-store session-store)
        crew    (or crew "main")
        existing (store/read-episode fs* root crew episode-id)
        session  (when ss (session-store/get-session ss episode-id))
        transcript (when ss (session-store/get-transcript ss episode-id))
        {:keys [provider model]} (gist-provider+model (or cfg {}) root provider model)]
    (cond
      (nil? existing)
      {:exit 1 :status :error :message (str "unknown episode: " episode-id)}

      (not= :open (:status existing))
      {:exit 0 :status (:status existing) :episode existing :message "already closed"}

      (nil? session)
      {:exit 1 :status :error :message (str "unknown backing session: " episode-id)}

      (nil? provider)
      {:exit 1 :status :error :message "no gist model/provider resolved — set :episodes {:gist-model ...} or :defaults :model"}

      :else
      (let [_ (when-not (:migrated-from existing)
                (store/write-episode! fs* root (assoc existing :migrated-from episode-id) []))
            result (migrate/migrate-session!
                     {:fs fs* :root root :session session :transcript transcript
                      :provider provider :model model :force? false})
            closed (when-let [ep (:episode result)]
                     (let [merged (cond-> (assoc ep
                                            :id     episode-id
                                            :thread (:thread existing)
                                            :status (or (:status ep) :closed))
                                    (:parent-episode existing)
                                    (assoc :parent-episode (:parent-episode existing)))]
                       (store/write-episode! fs* root merged (or (:scenes result) [])
                                             {:replace-scenes? true})
                       merged))]
        (when closed
          (log/info :episodes/closed :episode episode-id :crew crew :thread (:thread existing)))
        (cond-> result
          closed (assoc :episode closed))))))

(defn close-open-episodes!
  "Close every :open episode for crew. Returns {:closed n :results [...]}."
  [{:keys [fs root crew session-store provider model cfg] :as opts}]
  (let [fs*  (runtime-fs fs)
        root (runtime-root root)
        crew (or crew "main")
        open (->> (store/list-episodes fs* root crew)
                  (filter #(= :open (:status %))))
        results (mapv (fn [ep]
                        (close-episode! (assoc opts :fs fs* :root root :crew crew
                                               :episode-id (:id ep)
                                               :session-store session-store
                                               :provider provider :model model :cfg cfg)))
                      open)]
    {:closed  (count (filter #(contains? #{:closed :partial :resumed}
                                        (or (:status %) (get-in % [:episode :status])))
                            results))
     :results results}))

(defn- chain-successor!
  "Close the open episode and open a successor on the same thread."
  [{:keys [fs root crew thread session-store cwd origin compaction seed-compaction]
    :as opts}
   open-ep]
  (let [closed    (close-episode! (assoc opts :episode-id (:id open-ep)))
        successor (open-episode! {:fs fs :root root :crew crew :thread thread
                                  :session-store session-store
                                  :parent-episode (:id open-ep)
                                  :cwd cwd :origin origin :compaction compaction
                                  :seed-compaction seed-compaction})]
    {:session-key (:id successor)
     :episode     successor
     :closed      (:episode closed)
     :action      :chained}))

(defn- latest-on-thread [fs* root crew thread]
  (->> (store/list-episodes fs* root crew)
       (filter #(= thread (:thread %)))
       (sort-by :id)
       last))

(defn resolve-thread!
  "Map a THREAD handle to the backing session of the current open episode.
   Warm (last message within TTL) -> append. Cold/absent -> close-then-open
   with :parent-episode. After an explicit close, the next prompt still
   chains from the most recent episode on the thread even inside the warm
   window. opts: :fs :root :crew :thread :session-store :cfg
   :provider :model :cwd :origin :compaction"
  [{:keys [fs root crew thread session-store cfg] :as opts}]
  (let [fs*  (runtime-fs fs)
        root (runtime-root root)
        ss   (runtime-store session-store)
        crew (or crew "main")
        ttl  (ttl-minutes cfg)
        open (store/find-open-on-thread fs* root crew thread)]
    (cond
      (nil? open)
      (let [prior (latest-on-thread fs* root crew thread)
            ep    (open-episode! (cond-> (assoc opts :fs fs* :root root :crew crew
                                                :session-store ss)
                                   prior (assoc :parent-episode (:id prior))))]
        {:session-key (:id ep)
         :episode     ep
         :action      (if prior :chained :opened)})

      :else
      (let [transcript (when ss (session-store/get-transcript ss (:id open)))]
        (if (warm? transcript ttl)
          {:session-key (:id open) :episode open :action :warm}
          (chain-successor! (assoc opts :fs fs* :root root :crew crew
                                   :session-store ss)
                            open))))))

(defn compact-close!
  "Compaction on an episode crew: close the current episode and open a
   successor whose transcript begins with the compaction summary."
  [{:keys [fs root crew thread session-store summary cfg cwd origin compaction] :as opts}]
  (let [fs*  (runtime-fs fs)
        root (runtime-root root)
        ss   (runtime-store session-store)
        crew (or crew "main")
        open (or (store/find-open-on-thread fs* root crew thread)
                 (when-let [id (:episode-id opts)]
                   (store/read-episode fs* root crew id)))]
    (if-not open
      {:error :no-open-episode}
      (chain-successor! (assoc opts :fs fs* :root root :crew crew
                               :thread (or thread (:thread open))
                               :session-store ss
                               :seed-compaction {:summary summary}
                               :cwd cwd :origin origin :compaction compaction
                               :cfg cfg)
                        open))))
