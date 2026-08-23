(ns isaac.recall.tools
  "Crew tools :recall/search and :recall/scene (wire recall__search / recall__scene)."
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.recall.inject :as inject]
    [isaac.recall.query :as query]
    [isaac.recall.score :as score]
    [isaac.session.store.spi :as session-store]
    [isaac.tool.fs-bounds :as bounds]))

(defn- string-key-map [m]
  (into {} (map (fn [[k v]] [(if (keyword? k) (name k) (str k)) v]) m)))

(defn- arg [args k]
  (let [args (string-key-map args)]
    (or (get args k) (get args (str/replace k "-" "_")))))

(defn- crew-of [args]
  (let [args        (string-key-map args)
        session-key (get args "session_key")]
    (or (some->> session-key (session-store/get-session (bounds/session-store args)) :crew)
        (get-in (loader/snapshot "recall tools: default crew") [:defaults :crew])
        "main")))

(defn- current-episode-id [args crew]
  (let [session-key (arg args "session_key")
        fs*         (bounds/filesystem args)
        root        (bounds/root args)]
    (or (when (and session-key (store/read-episode fs* root crew session-key))
          session-key)
        (some-> (store/find-open-on-thread fs* root crew session-key) :id)
        session-key)))

(defn- record! [args scenes query]
  (let [crew (crew-of args)
        eid  (current-episode-id args crew)]
    (when eid
      (inject/record-refs! (bounds/filesystem args) (bounds/root args) crew eid scenes query))))

(defn- format-hit [scene]
  (inject/format-line (assoc scene :id (or (:id scene) (:scene-id scene))
                             :gist (or (:gist scene) (:gist-text scene)))))

(defn search-tool
  [args]
  (let [q    (arg args "query")
        crew (crew-of args)
        fs*  (bounds/filesystem args)
        root (bounds/root args)
        cfg  (or (loader/snapshot "recall search") {})]
    (if (str/blank? q)
      {:isError true :error "query is required"}
      (let [result (try
                     (query/query fs* root crew q cfg {:top 8})
                     (catch Exception e
                       {:error :embed-failed :message (.getMessage e)}))]
        (if (:error result)
          {:isError true :error (or (:message result) (name (:error result)))}
          (let [floor  (score/resolve-floor cfg {})
                hits   (inject/passing-hits (:hits result) floor)
                scenes (mapv (fn [h]
                               (let [s (or (store/read-scene fs* root crew (:episode-id h) (:scene-id h))
                                           {:id (:scene-id h) :gist (:gist-text h)})]
                                 (assoc s :origin-episode (:episode-id h)
                                        :id (or (:id s) (:scene-id h)))))
                             hits)
                lines  (mapv format-hit scenes)]
            (record! args scenes q)
            {:result (if (seq lines)
                       (str/join "\n" lines)
                       "no matching scenes")}))))))

(defn- find-scene [fs* root crew scene-id]
  (some (fn [ep]
          (when-let [scene (store/read-scene fs* root crew (:id ep) scene-id)]
            (assoc scene :origin-episode (:id ep))))
        (store/list-episodes fs* root crew)))

(defn scene-tool
  [args]
  (let [scene-id (arg args "scene-id")
        crew     (crew-of args)
        fs*      (bounds/filesystem args)
        root     (bounds/root args)]
    (if (str/blank? scene-id)
      {:isError true :error "scene-id is required"}
      (if-let [scene (find-scene fs* root crew scene-id)]
        (do
          (record! args [scene] nil)
          {:result (or (:text scene) "")})
        {:isError true :error (str "unknown scene: " scene-id)}))))
