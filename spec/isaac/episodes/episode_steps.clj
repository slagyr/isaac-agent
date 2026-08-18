(ns isaac.episodes.episode-steps
  "Feature steps for episode migration assertions."
  (:require
    [clojure.string :as str]
    [clojure.edn :as edn]
    [gherclj.core :as g :refer [defgiven defthen helper!]]
    [isaac.episodes.store :as store]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.recall.index :as recall-index]
    [isaac.step-tables :as match]
    [isaac.tool.memory :as memory]))

(helper! isaac.episodes.episode-steps)

(fcli/register-isaac-run-wrapper!
  (fn [thunk]
    (if-let [current-time (g/get :current-time)]
      (binding [memory/*now* current-time]
        (thunk))
      (thunk))))

(defn- root-dir []
  (or (g/get :runtime-root-dir)
      (g/get :root)))

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (mem-fs)}
    (f)))

(defn- current-episode []
  (g/get :current-episode))

(defn episode-exists-for-crew-matching [crew table]
  (with-feature-fs
    (fn []
      (let [eps (store/list-episodes (mem-fs) (root-dir) crew)
            ;; Prefer matching migrated-from if the table asks for it
            want-migrated (some (fn [[k v]]
                                  (when (= "migrated-from" k) v))
                                (map vector (:headers table)
                                     (first (:rows table))))
            ep (or (when want-migrated
                     (some #(when (= want-migrated (str (:migrated-from %))) %) eps))
                   (last (sort-by :id eps)))]
        (g/should-not-be-nil ep)
        (g/assoc! :current-episode (assoc ep :crew crew))
        (let [result (match/match-object table ep)]
          (g/should= [] (:failures result)))))))

(defn- cell-matches?
  "Match a table cell against an actual value. Regex cells use re-find
   (substring) because scene text is multi-line and features write focused
   patterns like #\"(?s)pinot noir\"."
  [expected actual]
  (let [expected (str expected)
        actual   (str actual)]
    (cond
      (str/starts-with? expected "#\"")
      (let [[_ pattern] (re-matches #"#\"(.+)\"" expected)]
        (boolean (re-find (re-pattern pattern) actual)))

      :else
      (= expected actual))))

(defn- ensure-current-episode!
  "Prefer remembered episode; otherwise pick the only/most recent episode on disk."
  []
  (or (current-episode)
      (with-feature-fs
        (fn []
          (let [root (root-dir)
                fs*  (mem-fs)
                crews (or (fs/children fs* (store/episodes-root root)) [])
                eps (mapcat (fn [crew]
                              (map #(assoc % :crew crew)
                                   (store/list-episodes fs* root crew)))
                            crews)
                ep (last (sort-by :id eps))]
            (when ep (g/assoc! :current-episode ep))
            ep)))))

(defn that-episode-has-scenes-matching [table]
  (with-feature-fs
    (fn []
      (let [ep (ensure-current-episode!)
            _ (g/should-not-be-nil ep)
            ;; Re-read episode so force/resume updates are visible.
            ep (or (store/read-episode (mem-fs) (root-dir) (:crew ep) (:id ep)) ep)
            _ (g/assoc! :current-episode (assoc ep :crew (:crew ep)))
            scenes (store/list-scenes (mem-fs) (root-dir) (:crew ep) (:id ep))
            headers (:headers table)
            rows (:rows table)]
        (g/should= (count rows) (count scenes))
        (doseq [[row scene] (map vector rows scenes)]
          (let [row-map (zipmap headers row)
                failures (keep (fn [[k v]]
                                 (let [actual (get scene (keyword k))]
                                   (when-not (cell-matches? v actual)
                                     (str k ": expected " (pr-str v) ", got: " (pr-str actual)))))
                               row-map)]
            (g/should= [] (vec failures))))))))

(defn scene-n-does-not-contain [n-str needle]
  (with-feature-fs
    (fn []
      (let [ep (current-episode)
            n (if (string? n-str) (parse-long n-str) n-str)
            scenes (store/list-scenes (mem-fs) (root-dir) (:crew ep) (:id ep))
            scene (nth scenes (dec n) nil)]
        (g/should-not-be-nil scene)
        (g/should-not (str/includes? (str (:text scene)) needle))))))

(defn crew-has-n-episodes [crew n-str]
  (with-feature-fs
    (fn []
      (let [n (if (string? n-str) (parse-long n-str) n-str)
            eps (store/list-episodes (mem-fs) (root-dir) crew)]
        (g/should= n (count eps))
        (when-let [ep (last (sort-by :id eps))]
          (g/assoc! :current-episode (assoc ep :crew crew)))))))

(defthen "an episode exists for crew {crew:string} matching:" isaac.episodes.episode-steps/episode-exists-for-crew-matching
  "Reads episode.edn under ~/.isaac/episodes/<crew>/ and matches key/value rows
   (supports #\"regex\" cells). Remembers the episode for subsequent scene steps.")

(defthen "that episode has scenes matching:" isaac.episodes.episode-steps/that-episode-has-scenes-matching
  "Asserts sealed scene .md files (YAML frontmatter + body) for the current
   episode, in id order. Columns: gist, text (regex ok). Count and order must
   match the table.")

(defthen #"scene (\d+) of that episode does not contain \"([^\"]+)\"" isaac.episodes.episode-steps/scene-n-does-not-contain
  "Absence assertion on scene N's :text (1-based).")

(defthen #"crew \"([^\"]+)\" has (\d+) episodes?" isaac.episodes.episode-steps/crew-has-n-episodes
  "Counts episode directories under episodes/<crew>/. Accepts episode/episodes.")

(defn that-episode-has-flagged-spans-matching [table]
  "Asserts :flagged-spans on the current episode. Table columns: span, raw."
  (with-feature-fs
    (fn []
      (let [ep (or (current-episode) (ensure-current-episode!))
            _ (g/should-not-be-nil ep)
            ep (or (store/read-episode (mem-fs) (root-dir) (:crew ep) (:id ep)) ep)
            flagged (vec (or (:flagged-spans ep) []))
            headers (:headers table)
            rows (:rows table)]
        (g/should= (count rows) (count flagged))
        (doseq [[row f] (map vector rows flagged)]
          (let [row-map (zipmap headers row)
                failures (keep (fn [[k v]]
                                 (let [actual (get f (keyword k))]
                                   (when-not (cell-matches? v actual)
                                     (str k ": expected " (pr-str v) ", got: " (pr-str actual)))))
                               row-map)]
            (g/should= [] (vec failures))))))))

(defthen "that episode has flagged spans matching:" isaac.episodes.episode-steps/that-episode-has-flagged-spans-matching
  "Asserts episode :flagged-spans records (span 1-based, optional raw).")

(defn- parse-cell [s]
  (let [s (str s)]
    (cond
      (re-matches #"-?\d+" s) (parse-long s)
      (re-matches #"-?\d+\.\d+" s) (parse-double s)
      (or (str/starts-with? s "[") (str/starts-with? s "{") (str/starts-with? s ":"))
      (try (edn/read-string s) (catch Exception _ s))
      :else s)))

(defn crew-has-closed-episode-with-scenes [crew episode-id table]
  (with-feature-fs
    (fn []
      (let [headers (:headers table)
            rows    (:rows table)
            scenes  (mapv (fn [row]
                            (let [m (zipmap (map keyword headers) row)]
                              {:id         (:id m)
                               :started-at (:started-at m)
                               :ended-at   (:ended-at m)
                               :gist       (:gist m)
                               :text       (:text m)
                               :start-id   (str (:id m) "-start")
                               :end-id     (str (:id m) "-end")
                               :seal-reason :migrate}))
                          rows)
            episode {:id         episode-id
                     :crew       crew
                     :status     :closed
                     :scene-ids  (mapv :id scenes)
                     :started-at (:started-at (first scenes))
                     :ended-at   (:ended-at (last scenes))}]
        (store/write-episode! (mem-fs) (root-dir) episode scenes)
        (g/assoc! :current-episode (assoc episode :crew crew))))))

(defn index-for-crew-has-rows [crew table]
  (with-feature-fs
    (fn []
      (let [rows    (recall-index/read-index (mem-fs) (root-dir) crew)
            headers (:headers table)
            expected (mapv (fn [row]
                             (into {}
                                   (map (fn [h v]
                                          [(keyword h)
                                           (if (= "kind" h)
                                             (keyword (parse-cell v))
                                             (parse-cell v))])
                                        headers row)))
                           (:rows table))]
        (g/should= (count expected) (count rows))
        (doseq [[want got] (map vector expected rows)]
          (let [failures (keep (fn [[k v]]
                                 (let [actual (get got k)]
                                   (when-not (= v actual)
                                     (str k ": expected " (pr-str v) ", got: " (pr-str actual)))))
                               want)]
            (g/should= [] (vec failures))))))))

(defn no-index-exists-for-crew [crew]
  (with-feature-fs
    (fn []
      (g/should-not (fs/exists? (mem-fs) (recall-index/index-path (root-dir) crew))))))

(defgiven "crew {crew:string} has a closed episode {episode-id:string} with scenes:"
  isaac.episodes.episode-steps/crew-has-closed-episode-with-scenes
  "Writes episode.edn + scene .md via store/write-episode!. Synthesizes
   start/end-ids and :seal-reason :migrate.")

(defthen "the index for crew {crew:string} has rows:"
  isaac.episodes.episode-steps/index-for-crew-has-rows
  "Reads episodes/<crew>/index.ednl and matches the EXACT row set
   (count included), so rebuild scenarios prove deletion.")

(defthen "no index exists for crew {crew:string}"
  isaac.episodes.episode-steps/no-index-exists-for-crew
  "Asserts index.ednl is absent (no half-written file).")
