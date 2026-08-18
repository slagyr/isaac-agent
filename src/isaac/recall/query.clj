(ns isaac.recall.query
  "Hybrid retrieval over a crew's index.ednl."
  (:require
    [clojure.string :as str]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.recall.embedding :as embedding]
    [isaac.recall.index :as index]
    [isaac.recall.score :as score]
    [isaac.tool.memory :as memory])
  (:import
    (java.time Instant Period ZoneOffset)))

(defn- parse-instant [ts]
  (cond
    (instance? Instant ts) ts
    (string? ts)
    (try
      (let [normalized (if (re-find #"[zZ]|[+-]\d{2}:?\d{2}$" ts) ts (str ts "Z"))]
        (Instant/parse normalized))
      (catch Exception _
        (try
          (-> (java.time.LocalDateTime/parse
                (subs ts 0 (min (count ts) 19))
                (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))
              (.toInstant java.time.ZoneOffset/UTC))
          (catch Exception _
            nil))))
    :else nil))

(defn- now-instant [now]
  (or (parse-instant now)
      (when-let [n memory/*now*]
        (if (instance? Instant n) n (parse-instant (str n))))
      (Instant/now)))

(defn- age-days
  "Calendar age in 30-day months so a two-month gap is exactly 60 days
   (the recency checkpoint: 0.5^(60/30) = 0.25)."
  [ended-at now]
  (let [end (parse-instant ended-at)
        n   (now-instant now)]
    (if (and end n)
      (let [a (.toLocalDate (.atOffset end ZoneOffset/UTC))
            b (.toLocalDate (.atOffset n ZoneOffset/UTC))
            p (Period/between a b)]
        (double (+ (* 30 (.getYears p) 12)
                   (* 30 (.getMonths p))
                   (.getDays p))))
      0.0)))

(defn- configured-model [cfg]
  (or (get-in cfg [:embedding :model]) ""))

(defn- group-stale [rows model]
  (let [stale (remove #(= model (:model %)) rows)
        by-model (frequencies (map :model stale))]
    by-model))

(defn- scene-lookup [fs* root crew]
  (into {}
        (for [ep (store/list-episodes fs* root crew)
              scene (store/list-scenes fs* root crew (:id ep))]
          [(:id scene) (assoc scene :episode-id (:id ep))])))

(defn- rows-by-scene [rows model]
  (->> rows
       (filter #(= model (:model %)))
       (group-by :scene-id)))

(defn query
  "Rank indexed scenes for `query-text`.

   opts:
     :now        Instant or ISO string (defaults to memory/*now* / clock)
     :weights    CLI flag overrides {:text :gist :lex :recency}
     :half-life  days (default 30)
     :top        max hits (default all)

   Returns {:hits [...] :model ... :warning ...} or {:error ... :message ...}."
  [fs* root crew query-text cfg {:keys [now weights half-life top]}]
  (let [path (index/index-path root crew)]
    (if-not (fs/exists? fs* path)
      {:error   :no-index
       :message (str "no index for crew " crew " — run isaac episodes index")}
      (let [rows  (index/read-index fs* root crew)
            model (configured-model cfg)
            matching (filter #(= model (:model %)) rows)]
        (if (empty? matching)
          {:error   :no-rows
           :message (str "no rows for model " model " — run isaac episodes index")}
          (let [embed   (embedding/embed-texts cfg [query-text])
                qvec    (first (:vectors embed))
                w       (score/resolve-weights cfg (or weights {}))
                hl      (or half-life (get-in cfg [:recall :half-life]) 30.0)
                scenes  (scene-lookup fs* root crew)
                grouped (rows-by-scene rows model)
                stale   (group-stale rows model)
                warning (when (seq stale)
                          (let [parts (map (fn [[m n]] (str n " stale rows (" m ")")) stale)]
                            (str (str/join ", " parts) " — run isaac episodes index --rebuild")))
                hits
                (for [[scene-id kind-rows] grouped
                      :let [scene (get scenes scene-id)
                            by-kind (into {} (map (juxt :kind identity) kind-rows))
                            text-row (get by-kind :text)
                            gist-row (get by-kind :gist)
                            text-cos (if (and qvec (:vector text-row))
                                       (score/cosine qvec (:vector text-row))
                                       0.0)
                            gist-cos (if (and qvec (:vector gist-row))
                                       (score/cosine qvec (:vector gist-row))
                                       0.0)
                            lex-hay  (str (:gist scene) " " (:text scene))
                            lex      (score/lexical query-text lex-hay)
                            rec      (score/recency (age-days (:ended-at scene) now) hl)
                            blended  (score/blend {:text text-cos :gist gist-cos
                                                   :lex lex :rec rec}
                                                  w)]]
                  {:scene-id   scene-id
                   :episode-id (or (:episode-id scene) (:episode-id text-row) (:episode-id gist-row))
                   :score      blended
                   :text       text-cos
                   :gist       gist-cos
                   :lex        lex
                   :rec        rec
                   :gist-text  (:gist scene)})
                ranked (->> hits
                            (sort-by (juxt (comp - :score) :scene-id))
                            vec)
                ranked (if top (vec (take (long top) ranked)) ranked)]
            (cond-> {:hits ranked :model model :scene-count (count grouped)}
              warning (assoc :warning warning))))))))
