(ns isaac.episodes.migrate
  "Materialize a session transcript as a closed episode (scenes + gists)."
  (:require
    [isaac.config.resolve :as resolve]
    [isaac.episodes.distill :as distill]
    [isaac.episodes.ids :as ids]
    [isaac.episodes.segment :as segment]
    [isaac.episodes.store :as store]
    [isaac.session.store.spi :as session-store]))

(defn- first-message-timestamp [transcript]
  (->> transcript
       (filter #(= "message" (:type %)))
       first
       :timestamp))

(defn- last-message-timestamp [transcript]
  (->> transcript
       (filter #(= "message" (:type %)))
       last
       :timestamp))

(defn- scene-index-by-start
  "Map start-id -> scene for resume checks."
  [scenes]
  (into {} (map (fn [s] [(:start-id s) s]) scenes)))

(defn- span-already-sealed?
  "A span is sealed when every message id in the span is covered by existing
   scenes whose start-ids are present in the sealed set for this span's first
   message — practical check: first message id of span is a sealed scene start
   and the sealed scene chain covers the span end."
  [sealed-scenes span-messages]
  (let [by-start (scene-index-by-start sealed-scenes)
        first-id (:id (first span-messages))
        last-id  (:id (last span-messages))]
    (boolean
      (when-let [s (get by-start first-id)]
        ;; Walk sealed scenes from this start until we cover last-id.
        (loop [current s
               guard 0]
          (cond
            (> guard 10000) false
            (= last-id (:end-id current)) true
            :else
            (let [;; next sealed scene starts after current end — find message after end-id
                  end-id (:end-id current)
                  ids (mapv :id span-messages)
                  idx (.indexOf ids end-id)
                  next-id (when (and (>= idx 0) (< (inc idx) (count ids)))
                            (nth ids (inc idx)))]
              (if-let [nxt (and next-id (get by-start next-id))]
                (recur nxt (inc guard))
                false))))))))

(defn- flagged-set [episode]
  (set (or (:flagged-spans episode) [])))

(defn- resolve-gist-model
  "Resolve {:provider :model} for gisting from config."
  [cfg]
  (let [cfg (or cfg {})
        gist-ref (get-in cfg [:episodes :gist-model])
        model-id (or (when gist-ref
                       (if (keyword? gist-ref) (name gist-ref) (str gist-ref)))
                     (get-in cfg [:defaults :model]))
        ctx (when model-id
              (try
                (resolve/resolve-crew-context cfg "main" {:model-override model-id})
                (catch Exception _
                  (resolve/resolve-crew-context cfg "main"))))
        ctx (or ctx (resolve/resolve-crew-context cfg "main"))]
    {:provider (:provider ctx)
     :model    (or (:model ctx) model-id "gist")
     :model-id model-id}))

(defn migrate-session!
  "Run migration. opts:
     :fs :root :session :transcript :provider :model
     :force? :size-cap
   Returns {:exit 0|1 :status :closed|:partial|:already-migrated|:resumed
            :episode ... :message ...}"
  [{:keys [fs root session transcript provider model force? size-cap]
    :or {force? false}}]
  (let [crew (or (:crew session) "main")
        session-id (or (:id session) (:key session))
        existing (store/find-by-migrated-from fs root crew session-id)
        sealed (if (and existing (not force?))
                 (store/list-scenes fs root crew (:id existing))
                 [])
        fully-closed? (and existing
                           (= :closed (:status existing))
                           (not force?)
                           (empty? (flagged-set existing)))]
    (cond
      fully-closed?
      {:exit 0 :status :already-migrated :episode existing
       :message "already migrated"}

      (empty? (filter #(= "message" (:type %)) transcript))
      {:exit 1 :status :error :message "session has no messages"}

      :else
      (let [spans (segment/compaction-spans transcript size-cap)
            episode-id (or (:id existing)
                           (ids/timestamped-id (first-message-timestamp transcript)))
            flagged (atom (if force? #{} (flagged-set existing)))
            scenes-acc (atom (if force? [] (vec sealed)))
            any-work? (atom false)
            resumed? (atom (boolean (and existing (not force?))))]
        (doseq [span spans]
          (let [raw-msgs (:messages span)
                distilled (mapv distill/distill-entry raw-msgs)
                idx (:index span)
                skip? (and (not force?)
                           (not (contains? @flagged idx))
                           (span-already-sealed? @scenes-acc raw-msgs))]
            (when-not skip?
              (reset! any-work? true)
              (let [result (segment/segment-span! provider model distilled (:preceding-summary span))]
                (if-let [resolved (:ok result)]
                  (let [sealed-scenes (segment/seal-scenes distilled resolved
                                                           (if (:preceding-summary span)
                                                             :compaction
                                                             :migrate))
                        ;; drop any prior scenes that start inside this span
                        span-ids (set (map :id raw-msgs))
                        kept (remove #(contains? span-ids (:start-id %)) @scenes-acc)]
                    (reset! scenes-acc (vec (concat kept sealed-scenes)))
                    (swap! flagged disj idx))
                  (do
                    (swap! flagged conj idx)
                    (binding [*out* *err*]
                      (println (str "span " (inc idx) " flagged: unparseable segmentation output")))))))))
        ;; Order scenes by first appearance of :start-id in the transcript so
        ;; resume (which concatenates newly sealed spans after prior sealed
        ;; scenes) still yields chronological scene order.
        (let [msg-ids (mapv :id (filter #(= "message" (:type %)) transcript))
              rank (fn [s]
                     (let [i (.indexOf msg-ids (:start-id s))]
                       (if (neg? i) Integer/MAX_VALUE i)))
              scenes (vec (sort-by (juxt rank :id) @scenes-acc))
              status (if (seq @flagged) :partial :closed)
              episode {:id            episode-id
                       :crew          crew
                       :status        status
                       :migrated-from session-id
                       :scene-ids     (mapv :id scenes)
                       :started-at    (first-message-timestamp transcript)
                       :ended-at      (last-message-timestamp transcript)
                       :flagged-spans (vec (sort @flagged))}
              _ (store/write-episode! fs root episode scenes {:replace-scenes? true})
              status-kw (cond
                          (= :partial status) :partial
                          (and @resumed? @any-work?) :resumed
                          :else :closed)
              msg (case status-kw
                    :partial (str "partial migration; flagged spans: " (pr-str (vec (sort @flagged))))
                    :resumed "resumed"
                    :closed  "migrated"
                    "migrated")]
          {:exit (if (= :partial status) 1 0)
           :status status-kw
           :episode episode
           :message msg
           :scenes scenes})))))

(defn migrate-session-id!
  "High-level: look up session + transcript from the registered store, resolve
   gist model from cfg, run migrate-session!."
  [{:keys [fs root cfg session-id force? session-store]
    :or {force? false}}]
  (let [ss (or session-store (session-store/registered-store))
        session (when ss (session-store/get-session ss session-id))
        session (or session
                    ;; Fallback: try open by id on file stores after install
                    (when ss (session-store/get-session ss session-id)))]
    (if-not session
      {:exit 1 :status :error :message (str "unknown session: " session-id)}
      (let [transcript (session-store/get-transcript ss session-id)
            {:keys [provider model]} (resolve-gist-model cfg)]
        (if-not provider
          {:exit 1 :status :error :message "no gist model/provider resolved — set :episodes {:gist-model ...} or :defaults :model"}
          (migrate-session!
            {:fs fs :root root :session session :transcript transcript
             :provider provider :model model :force? force?}))))))
