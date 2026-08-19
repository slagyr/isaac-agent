(ns isaac.recall.score
  "Hybrid retrieval scoring: cosine, IDF lexical, recency, blended parts, match floor.")

(def default-weights
  {:text 1.0 :gist 1.0 :lex 1.0 :recency 1.0})

(def default-floor 2.5)

(def rare-term-lex 0.5)

(defn float-array?
  "True when v is a primitive float[]. SCI-safe (no reflective alength)."
  [v]
  (instance? (Class/forName "[F") v))

(defn normalize-vector
  "Unit-normalize a numeric vector into a primitive float array. Zero → zeros."
  [v]
  (let [src (vec (or v []))
        n   (count src)
        xs  (float-array n)]
    (if (zero? n)
      xs
      (let [acc (loop [i 0 s 0.0]
                  (if (< i n)
                    (let [x (float (nth src i))]
                      (aset xs i x)
                      (recur (inc i) (+ s (* x x))))
                    s))
            norm (Math/sqrt acc)]
        (if (zero? norm)
          xs
          (do
            (dotimes [j n]
              (aset xs j (float (/ (nth xs j) norm))))
            xs))))))

(defn- as-floats [v]
  (if (float-array? v)
    v
    (float-array (map float (or v [])))))

(defn dot
  "Dot product of two primitive float arrays (or seqs). Length mismatch / empty → 0.0.
   Hot path uses count/nth so babashka SCI never reflects RT.alength on float[]."
  [a b]
  (let [aa (as-floats a)
        bb (as-floats b)
        n  (count aa)]
    (if (or (zero? n) (not= n (count bb)))
      0.0
      (loop [i 0 acc 0.0]
        (if (< i n)
          (recur (inc i) (+ acc (* (nth aa i) (nth bb i))))
          acc)))))

(defn cosine
  "Cosine similarity of two numeric vectors. Empty/zero vectors → 0.0.
   Pre-normalized float arrays reduce to a compiled-core-friendly dot."
  [a b]
  (if (and (float-array? a) (float-array? b))
    (let [d (dot a b)]
      (cond
        (Double/isNaN d) 0.0
        (> d 1.0)        1.0
        (< d -1.0)       -1.0
        :else            d))
    (let [a (vec a)
          b (vec b)]
      (if (or (empty? a) (empty? b) (not= (count a) (count b)))
        0.0
        (let [dot (reduce + 0.0 (map * a b))
              na  (Math/sqrt (reduce + 0.0 (map #(* % %) a)))
              nb  (Math/sqrt (reduce + 0.0 (map #(* % %) b)))]
          (if (or (zero? na) (zero? nb))
            0.0
            (/ dot (* na nb))))))))

(defn recency
  "Additive recency channel: 0.5^(age-days / half-life). Age 0 → 1.0."
  [age-days half-life]
  (let [half (double (or half-life 30.0))]
    (if (or (zero? half) (neg? half))
      0.0
      (Math/pow 0.5 (/ (double (or age-days 0.0)) half)))))

(defn tokenize
  "Lowercased runs of alphanumerics with internal hyphens/dots kept intact."
  [s]
  (->> (re-seq #"[A-Za-z0-9]+(?:[-.][A-Za-z0-9]+)*" (or s ""))
       (map #(.toLowerCase ^String %))
       distinct
       vec))

(defn idf
  "idf(t) = ln(1 + N/(df(t)+1))."
  [n df]
  (Math/log (+ 1.0 (/ (double n) (+ (double (or df 0)) 1.0)))))

(defn document-frequency
  "Live df: count of scenes whose token set contains each query term."
  [scenes query-terms]
  (let [haystacks (mapv #(set (tokenize %)) scenes)]
    (into {}
          (map (fn [t]
                 [t (count (filter #(% t) haystacks))])
               query-terms))))

(defn- scene-tokens [scene]
  (set (tokenize scene)))

(defn lexical
  "IDF-weighted term overlap when :df/:n provided; else unweighted fraction.
   lex = Σ idf(matched query terms) / Σ idf(all query terms)."
  ([query scene]
   (lexical query scene nil))
  ([query scene {:keys [df n]}]
   (let [q (tokenize query)]
     (if (empty? q)
       0.0
       (let [haystack (scene-tokens scene)]
         (if (and df n)
           (let [denom (reduce + 0.0 (map #(idf n (get df % 0)) q))]
             (if (zero? denom)
               0.0
               (let [matched (filter haystack q)
                     numer   (reduce + 0.0 (map #(idf n (get df % 0)) matched))]
                 (double (/ numer denom)))))
           (let [hits (count (filter haystack q))]
             (double (/ hits (count q))))))))))

(defn matched-terms
  "Query terms found in the scene, in query order."
  [query scene]
  (let [haystack (scene-tokens scene)]
    (vec (filter haystack (tokenize query)))))

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

(defn- as-number [v]
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
                               (when-let [v (as-number (get cfg-w k))]
                                 [dest v]))
                             {:text :text :gist :gist :lex :lex :recency :recency}))
        from-flags (into {}
                         (keep (fn [k]
                                 (when-let [v (as-number (get flags k))]
                                   [k v]))
                               [:text :gist :lex :recency]))]
    (merge default-weights from-cfg from-flags)))

(defn resolve-floor
  "defaults (2.5) → :recall {:floor} → CLI :floor. 0 disables."
  [cfg flags]
  (let [from-cfg (as-number (get-in cfg [:recall :floor]))
        from-flag (as-number (get flags :floor))]
    (cond
      (some? from-flag) from-flag
      (some? from-cfg)  from-cfg
      :else             default-floor)))

(defn- mean [xs]
  (/ (reduce + 0.0 xs) (count xs)))

(defn- sample-stddev [xs m]
  (let [n (count xs)]
    (if (< n 2)
      0.0
      (Math/sqrt (/ (reduce + 0.0 (map (fn [x]
                                         (let [d (- x m)]
                                           (* d d)))
                                       xs))
                    (dec n))))))

(defn z-score
  "z = (value − mean) / stddev. Nil when <5 candidates (floor inactive).
   Degenerate sigma → 0.0. Optional :leave-one-out? excludes value from stats."
  ([value xs]
   (z-score value xs nil))
  ([value xs {:keys [leave-one-out?]}]
   (let [xs (mapv double xs)]
     (when (>= (count xs) 5)
       (let [sample (if leave-one-out?
                      (let [idx (.indexOf xs (double value))]
                        (if (neg? idx)
                          xs
                          (vec (concat (subvec xs 0 idx) (subvec xs (inc idx))))))
                      xs)
             m      (mean sample)
             sigma  (sample-stddev sample m)]
         (if (zero? sigma)
           0.0
           (/ (- (double value) m) sigma)))))))

(defn match?
  "True when z ≥ floor OR lex ≥ 0.5 (rare-term anchor). Floor 0 disables."
  [{:keys [z lex]} floor]
  (let [floor (double (or floor 0.0))
        z     (double (or z 0.0))
        lex   (double (or lex 0.0))]
    (or (zero? floor)
        (>= z floor)
        (>= lex rare-term-lex))))
