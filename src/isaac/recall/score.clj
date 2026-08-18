(ns isaac.recall.score
  "Hybrid retrieval scoring: cosine, lexical overlap, recency, blended parts.")

(def default-weights
  {:text 1.0 :gist 1.0 :lex 1.0 :recency 1.0})

(defn cosine
  "Cosine similarity of two numeric vectors. Empty/zero vectors → 0.0."
  [a b]
  (let [a (vec a)
        b (vec b)]
    (if (or (empty? a) (empty? b) (not= (count a) (count b)))
      0.0
      (let [dot (reduce + 0.0 (map * a b))
            na  (Math/sqrt (reduce + 0.0 (map #(* % %) a)))
            nb  (Math/sqrt (reduce + 0.0 (map #(* % %) b)))]
        (if (or (zero? na) (zero? nb))
          0.0
          (/ dot (* na nb)))))))

(defn recency
  "Additive recency channel: 0.5^(age-days / half-life). Age 0 → 1.0."
  [age-days half-life]
  (let [half (double (or half-life 30.0))]
    (if (or (zero? half) (neg? half))
      0.0
      (Math/pow 0.5 (/ (double (or age-days 0.0)) half)))))

(defn- tokenize [s]
  (->> (re-seq #"[A-Za-z0-9_-]+" (or s ""))
       (map #(.toLowerCase ^String %))
       distinct
       vec))

(defn lexical
  "Term-overlap: fraction of distinct query tokens found in the scene text."
  [query scene]
  (let [q (tokenize query)]
    (if (empty? q)
      0.0
      (let [haystack (set (tokenize scene))
            hits     (count (filter haystack q))]
        (double (/ hits (count q)))))))

(defn blend
  "Weighted sum of channels, normalized by Σw. Zero-weight total → 0.0.
   Channel keys on `scores`: :text :gist :lex :rec
   Channel keys on `weights`: :text :gist :lex :recency"
  [scores weights]
  (let [pairs [[:text :text]
               [:gist :gist]
               [:lex  :lex]
               [:rec  :recency]]
        weighted (map (fn [[sk wk]]
                        (let [w (double (or (get weights wk) 0.0))
                              s (double (or (get scores sk) 0.0))]
                          [(* w s) w]))
                      pairs)
        total-w (reduce + 0.0 (map second weighted))
        total-s (reduce + 0.0 (map first weighted))]
    (if (zero? total-w)
      0.0
      (/ total-s total-w))))

(defn- as-weight [v]
  (cond
    (number? v) (double v)
    (string? v) (try (Double/parseDouble v) (catch Exception _ nil))
    :else       nil))

(defn resolve-weights
  "defaults → :recall config → CLI flag overrides (flags win)."
  [cfg flags]
  (let [cfg-w (or (get-in cfg [:recall :weights]) {})
        from-cfg (into {}
                       (keep (fn [[k dest]]
                               (when-let [v (as-weight (get cfg-w k))]
                                 [dest v]))
                             {:text :text :gist :gist :lex :lex :recency :recency}))
        from-flags (into {}
                         (keep (fn [k]
                                 (when-let [v (as-weight (get flags k))]
                                   [k v]))
                               [:text :gist :lex :recency]))]
    (merge default-weights from-cfg from-flags)))
