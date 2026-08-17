(ns isaac.episodes.segment
  "Compaction-span windowing + LLM segmentation parse/validate/resolve."
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.drive.dispatch :as dispatch]
    [isaac.episodes.distill :as distill]
    [isaac.episodes.ids :as ids]))

(def DEFAULT_SIZE_CAP 80)

(defn parse-scenes
  "Parse LLM text into a vector of {:start :end :gist} maps, or nil."
  [text]
  (when (string? text)
    (let [trimmed (str/trim text)
          ;; Strip common markdown fences if the model wraps output.
          body (-> trimmed
                   (str/replace #"(?s)^```(?:edn|clojure)?\s*" "")
                   (str/replace #"(?s)\s*```$" "")
                   str/trim)]
      (try
        (let [parsed (edn/read-string body)
              scenes (cond
                       (vector? parsed) parsed
                       (list? parsed) (vec parsed)
                       (map? parsed) [parsed]
                       :else nil)]
          (when (and (sequential? scenes)
                     (every? map? scenes)
                     (every? #(and (number? (:start %)) (number? (:end %))) scenes))
            (mapv (fn [s]
                    {:start (long (:start s))
                     :end   (long (:end s))
                     :gist  (str (or (:gist s) ""))})
                  scenes)))
        (catch Exception _ nil)))))

(defn valid-tiling?
  "True when scenes are sorted non-overlapping contiguous cover of 1..n."
  [n scenes]
  (boolean
    (and (pos? n)
         (seq scenes)
         (let [sorted (sort-by :start scenes)]
           (and (= 1 (:start (first sorted)))
                (= n (:end (last sorted)))
                (every? (fn [s]
                          (and (integer? (:start s))
                               (integer? (:end s))
                               (<= 1 (:start s) (:end s) n)))
                        sorted)
                (loop [expected 1
                       remaining sorted]
                  (if (empty? remaining)
                    true
                    (let [s (first remaining)]
                      (and (= expected (:start s))
                           (recur (inc (:end s)) (rest remaining)))))))))))

(defn resolve-ordinals
  "Map span-local :start/:end ordinals onto message :id values."
  [distilled-messages scenes]
  (let [ids (mapv :id distilled-messages)]
    (mapv (fn [s]
            (let [si (dec (:start s))
                  ei (dec (:end s))]
              {:start-id  (nth ids si)
               :end-id    (nth ids ei)
               :gist      (:gist s)
               :start-ord (:start s)
               :end-ord   (:end s)}))
          (sort-by :start scenes))))

(defn- message-entry? [e]
  (= "message" (:type e)))

(defn compaction-spans
  "Split a transcript into compaction-bounded spans.

   Each span: {:messages [raw message entries...]
               :preceding-summary (string or nil)
               :index (0-based span index)}
   Compaction summaries ride the FOLLOWING span."
  ([transcript] (compaction-spans transcript DEFAULT_SIZE_CAP))
  ([transcript size-cap]
   (let [size-cap (or size-cap DEFAULT_SIZE_CAP)]
     (loop [remaining transcript
            current   []
            summary   nil
            spans     []
            idx       0]
       (if (empty? remaining)
         (cond-> spans
           (seq current)
           (conj {:messages current :preceding-summary summary :index idx}))
         (let [e (first remaining)]
           (cond
             (= "compaction" (:type e))
             (let [spans (cond-> spans
                           (seq current)
                           (conj {:messages current :preceding-summary summary :index idx}))
                   next-idx (if (seq current) (inc idx) idx)]
               (recur (rest remaining) [] (:summary e) spans next-idx))

             (message-entry? e)
             (let [current (conj current e)]
               (if (>= (count current) size-cap)
                 (recur (rest remaining) [] summary
                        (conj spans {:messages current :preceding-summary summary :index idx})
                        (inc idx))
                 (recur (rest remaining) current summary spans idx)))

             :else
             (recur (rest remaining) current summary spans idx))))))))

(defn- response-text [response]
  (or (get-in response [:message :content])
      (:content response)
      ""))

(defn segment-span!
  "Call the gist model once (with one retry) to segment a span.
   Returns {:ok scenes} | {:error :flagged :raw ...}."
  [provider model distilled-messages preceding-summary]
  (let [prompt (distill/format-span-prompt distilled-messages preceding-summary)
        request {:model model
                 :messages [{:role "user" :content prompt}]}
        attempt (fn []
                  (let [response (dispatch/dispatch-chat provider request)
                        text     (response-text response)
                        scenes   (parse-scenes text)]
                    (if (and scenes (valid-tiling? (count distilled-messages) scenes))
                      {:ok (resolve-ordinals distilled-messages scenes) :raw text}
                      {:error :bad-parse :raw text})))
        ;; Prefer the second span's request as last-request when both succeed:
        ;; call once; on failure retry. Capture request after successful parse
        ;; by re-dispatch is unnecessary — dispatch already records last request.
        first-try (attempt)]
    (if (:ok first-try)
      first-try
      (let [second-try (attempt)]
        (if (:ok second-try)
          second-try
          {:error :flagged
           :raw   (:raw second-try)})))))

(defn seal-scenes
  "Build sealed scene records from resolved ordinal scenes + distilled msgs."
  [distilled-messages resolved-scenes seal-reason]
  (mapv (fn [s]
          (let [start-ord (:start-ord s)
                end-ord   (:end-ord s)
                slice     (subvec distilled-messages (dec start-ord) end-ord)
                texts     (->> slice (keep :text) (str/join "\n"))
                start-ts  (:timestamp (first slice))
                end-ts    (:timestamp (last slice))
                scene-id  (ids/timestamped-id start-ts)]
            {:id          scene-id
             :start-id    (:start-id s)
             :end-id      (:end-id s)
             :started-at  start-ts
             :ended-at    end-ts
             :seal-reason seal-reason
             :text        texts
             :gist        (:gist s)}))
        resolved-scenes))
