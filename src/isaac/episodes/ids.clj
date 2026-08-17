(ns isaac.episodes.ids
  "Timestamped episode/scene ids: <yyyy-MM-dd-HHmm>-<chaos>."
  (:import
    (java.time Instant ZoneOffset)
    (java.time.format DateTimeFormatter)
    (java.util Random)))

(def ^:private TS_FMT
  (DateTimeFormatter/ofPattern "yyyy-MM-dd-HHmm"))

(def ^:private BASE36 "0123456789abcdefghijklmnopqrstuvwxyz")

(defn now-ms []
  (System/currentTimeMillis))

(defn chaos-suffix
  "Few random base36 chars for same-minute uniqueness."
  ([] (chaos-suffix 4))
  ([n]
   (let [rnd (Random.)]
     (apply str (repeatedly n #(.charAt BASE36 (.nextInt rnd 36)))))))

(defn- parse-instant [ts]
  (cond
    (number? ts)
    (Instant/ofEpochMilli (long ts))

    (string? ts)
    (try
      (if (re-matches #"-?\d+" ts)
        (Instant/ofEpochMilli (Long/parseLong ts))
        ;; Accept "yyyy-MM-dd'T'HH:mm:ss" and full ISO-8601
        (let [normalized (if (re-find #"[zZ]|[+-]\d{2}:?\d{2}$" ts)
                           ts
                           (str ts "Z"))]
          (Instant/parse normalized)))
      (catch Exception _
        (try
          (-> (java.time.LocalDateTime/parse
                (subs ts 0 (min (count ts) 19))
                (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss"))
              (.toInstant ZoneOffset/UTC))
          (catch Exception _
            (Instant/ofEpochMilli (now-ms))))))

    :else
    (Instant/ofEpochMilli (now-ms))))

(defn timestamped-id
  "Build `<yyyy-MM-dd-HHmm>-<chaos>` from a message/session timestamp."
  ([ts] (timestamped-id ts (chaos-suffix)))
  ([ts chaos]
   (let [inst (parse-instant ts)
         stamp (.format TS_FMT (.atOffset inst ZoneOffset/UTC))]
     (str stamp "-" chaos))))
