(ns isaac.episodes.layout
  "migrate-layout: leftover flat sessions/<sid>/ plus leftover
   episodes/<crew>/<eid>/ fold into sessions/<crew>/<sid>/ with nested
   episodes/<eid>/. Idempotent. --dry-run prints the plan."
  (:require
    [clojure.string :as str]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.recall.index :as recall-index]
    [isaac.session.store.impl-common :as impl]))

(defn- reserved-name? [name]
  (or (contains? #{"turns" "index.edn" "recall"} name)
      (str/starts-with? (str name) ".")
      (re-matches #".*\.(edn|ednl|jsonl|tmp)$" (str name))))

(defn- list-names [fs* dir]
  (if (fs/exists? fs* dir)
    (->> (or (fs/children fs* dir) [])
         (remove #(str/starts-with? % "."))
         sort
         vec)
    []))

(defn- read-edn [fs* path]
  (when (fs/exists? fs* path)
    (try
      (read-string (or (fs/slurp fs* path) "{}"))
      (catch Exception _ nil))))

(defn- keywordize [m]
  (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword k)) v]) (or m {}))))

(defn- leftover-session-dirs
  "Flat leftover sessions/<sid>/session.edn (not nested under a crew)."
  [fs* root]
  (let [dir (impl/sessions-dir root)]
    (if-not (fs/exists? fs* dir)
      []
      (->> (list-names fs* dir)
           (remove reserved-name?)
           (keep (fn [name]
                   (let [sdir (str dir "/" name)
                         edn  (str sdir "/session.edn")]
                     (when (fs/exists? fs* edn)
                       (let [entry (keywordize (read-edn fs* edn))]
                         {:id   (or (:id entry) name)
                          :dir  sdir
                          :crew (or (:crew entry) "main")
                          :entry entry})))))
           vec))))

(defn- leftover-episodes
  "Legacy episodes/<crew>/<eid>/episode.edn records."
  [fs* root]
  (let [dir (store/episodes-root root)]
    (if-not (fs/exists? fs* dir)
      []
      (mapcat
        (fn [crew]
          (->> (list-names fs* (str dir "/" crew))
               (keep (fn [eid]
                       (let [edn (str dir "/" crew "/" eid "/episode.edn")]
                         (when (fs/exists? fs* edn)
                           (let [entry (keywordize (read-edn fs* edn))]
                             {:crew    crew
                              :id      (or (:id entry) eid)
                              :dir     (str dir "/" crew "/" eid)
                              :entry   entry
                              :thread  (or (:thread entry) (:session-id entry))})))))))
        (list-names fs* dir)))))

(defn- already-nested? [fs* root crew sid]
  (fs/exists? fs* (impl/session-edn-path root crew sid)))

(defn- episode-session-id [ep leftover-sessions]
  (or (:thread ep)
      (:session-id (:entry ep))
      (:thread (:entry ep))
      (when-let [backing (some #(when (= (:id %) (:id ep)) %) leftover-sessions)]
        (or (get-in backing [:entry :name])
            (when (not= (:id backing) (get-in backing [:entry :name]))
              (get-in backing [:entry :name]))))
      (:id ep)))

(defn- plan
  "Build move descriptions. Each item is {:kind :from :to :policy :session-id :crew :id}."
  [fs* root]
  (let [leftover-s (leftover-session-dirs fs* root)
        leftover-e (leftover-episodes fs* root)
        episode-ids (set (map :id leftover-e))
        ;; A flat session that is the :session-id/:thread of a leftover episode
        ;; is a post-mmod thread: episodes nest under it, so it is owned by the
        ;; episodes policy and must be stamped that way (isaac-lhnq).
        thread-sids (set (keep (fn [ep] (or (:thread ep) (get-in ep [:entry :thread])
                                            (get-in ep [:entry :session-id])))
                               leftover-e))
        chronicle   (->> leftover-s
                         (remove #(contains? episode-ids (:id %)))
                         (remove #(already-nested? fs* root (:crew %) (:id %)))
                         (map (fn [s]
                                {:kind       :chronicle
                                 :from       (str "sessions/" (:id s))
                                 :to         (str "sessions/" (:crew s) "/" (:id s))
                                 :policy     (if (contains? thread-sids (:id s)) :episodes :chronicle)
                                 :session-id (:id s)
                                 :crew       (:crew s)
                                 :id         (:id s)
                                 :dir        (:dir s)
                                 :entry      (:entry s)})))
        folded      (for [ep leftover-e]
                      (let [sid  (or (:thread ep) (get-in ep [:entry :thread])
                                     (get-in ep [:entry :session-id])
                                     (let [backing (some #(when (= (:id %) (:id ep)) %) leftover-s)]
                                       (or (get-in backing [:entry :name])
                                           (:id backing)))
                                     (:id ep))
                            backing (some #(when (= (:id %) (:id ep)) %) leftover-s)]
                        {:kind       :episode
                         :from       (str "sessions/" (:id ep))
                         :to         (str "sessions/" (:crew ep) "/" sid "/episodes/" (:id ep))
                         :legacy-from (str "episodes/" (:crew ep) "/" (:id ep))
                         :policy     :episodes
                         :session-id sid
                         :crew       (:crew ep)
                         :id         (:id ep)
                         :dir        (:dir ep)
                         :backing    backing
                         :entry      (:entry ep)}))]
    {:chronicle (vec chronicle)
     :episodes  (vec folded)
     :empty?    (and (empty? chronicle) (empty? folded))}))

(defn- format-plan-line [item]
  (case (:kind item)
    :chronicle (str (:from item) " -> " (:to item) " (" (name (or (:policy item) :chronicle)) ")")
    :episode   (str (when (:backing item)
                      (str (:from item) " -> " (:to item) "\n"))
                    (:legacy-from item) " -> " (:to item))))

(defn- copy-file! [fs* src dest]
  (when (fs/exists? fs* src)
    (fs/mkdirs fs* (fs/parent dest))
    (fs/spit fs* dest (fs/slurp fs* src))))

(defn- move-chronicle! [fs* root item]
  (let [crew (:crew item)
        sid  (:session-id item)
        dest (impl/session-dir root crew sid)
        src  (:dir item)
        entry (assoc (or (:entry item) {})
                :id sid
                :crew crew
                :session-policy (or (:policy item) :chronicle))]
    (impl/mkdirs*! fs* dest)
    (when (fs/exists? fs* (str src "/current.ednl"))
      (impl/move-tree! fs* src dest)
      ;; move-tree may have moved session.edn too; rewrite with stamp
      (impl/atomic-spit! fs* (impl/session-edn-path root crew sid)
                         (impl/write-edn entry)))
    (when-not (fs/exists? fs* (impl/session-edn-path root crew sid))
      (impl/atomic-spit! fs* (impl/session-edn-path root crew sid)
                         (impl/write-edn entry))
      (when (fs/exists? fs* (str src "/current.ednl"))
        (copy-file! fs* (str src "/current.ednl") (str dest "/current.ednl")))
      (impl/delete-tree! fs* src))
    (impl/upsert-index-row! fs* root sid {:crew crew :session-policy :chronicle
                                          :updated-at (:updated-at entry) :id sid})))

(defn- ensure-session-edn! [fs* root crew sid policy extra]
  (let [path (impl/session-edn-path root crew sid)]
    (when-not (fs/exists? fs* path)
      (impl/atomic-spit! fs* path
                         (impl/write-edn (merge {:id sid :name sid :crew crew
                                                 :session-policy policy}
                                                extra))))
    (impl/upsert-index-row! fs* root sid {:crew crew :session-policy policy :id sid})))

(defn- move-episode! [fs* root item]
  (let [crew    (:crew item)
        sid     (:session-id item)
        eid     (:id item)
        dest    (store/nested-episode-path root crew sid eid)
        backing (:backing item)
        entry   (-> (or (:entry item) {})
                    (assoc :id eid :crew crew :session-id sid)
                    (dissoc :thread))]
    (ensure-session-edn! fs* root crew sid :episodes
                         (when backing
                           (select-keys (:entry backing) [:name :model :cwd :origin :tags :pins])))
    (impl/mkdirs*! fs* dest)
    (impl/atomic-spit! fs* (str dest "/episode.edn") (impl/write-edn entry))
    (when-let [src-dir (:dir backing)]
      (when (fs/exists? fs* (str src-dir "/current.ednl"))
        (copy-file! fs* (str src-dir "/current.ednl") (str dest "/current.ednl")))
      (impl/delete-tree! fs* src-dir))
    (when-let [legacy (:dir item)]
      (doseq [name (list-names fs* legacy)
              :when (or (str/ends-with? name ".md") (str/ends-with? name ".edn"))]
        (when-not (= name "episode.edn")
          (copy-file! fs* (str legacy "/" name)
                      (str dest "/scenes/" name))))
      (when (fs/exists? fs* (str legacy "/current.ednl"))
        (copy-file! fs* (str legacy "/current.ednl") (str dest "/current.ednl")))
      (impl/delete-tree! fs* legacy))))

(defn- rebuild-recall! [fs* root]
  (doseq [crew (distinct
                 (concat (keep (fn [[_ loc]] (:crew loc)) (impl/scan-session-dirs fs* root))
                         (keep (fn [[_ row]] (:crew row)) (impl/read-index fs* root))))]
    (let [eps (store/list-episodes fs* root crew)
          rows (mapcat
                 (fn [ep]
                   (let [sid (or (:session-id ep) (:thread ep))
                         eid (:id ep)]
                     (mapcat
                       (fn [scene]
                         [{:session-id sid :episode-id eid :scene-id (:id scene) :kind :gist}
                          {:session-id sid :episode-id eid :scene-id (:id scene) :kind :text}])
                       (store/list-scenes fs* root crew eid))))
                 (filter #(contains? #{:closed :partial} (keyword (name (or (:status %) :closed)))) eps))]
      (when (seq rows)
        (recall-index/write-index! fs* root crew (mapv #(assoc % :vector [0.0] :model "") rows))))))

(defn migrate-layout!
  "Move leftover flat sessions and leftover episodes/<crew>/<eid>/ into the
   nested layout. Returns 0. Prints a plan on :dry-run?."
  [{:keys [fs root dry-run?] :or {dry-run? false}}]
  (let [fs*  (or fs (fs/instance))
        root (or root ".")
        {:keys [chronicle episodes] nothing-to-do? :empty?} (plan fs* root)]
    (cond
      (and nothing-to-do? (not dry-run?))
      (do (println "nothing to migrate") 0)

      dry-run?
      (do
        (doseq [item chronicle]
          (println (format-plan-line item)))
        (doseq [item episodes]
          (doseq [line (str/split-lines (format-plan-line item))]
            (when-not (str/blank? line)
              (println line))))
        (println "dry run: 0 moved")
        0)

      :else
      (do
        (doseq [item chronicle] (move-chronicle! fs* root item))
        (doseq [item episodes] (move-episode! fs* root item))
        (impl/repair-index! fs* root)
        (rebuild-recall! fs* root)
        (let [again (plan fs* root)]
          (when (and (empty? (:chronicle again)) (empty? (:episodes again)))
            nil))
        0))))
