(ns isaac.episodes.segment
  "Compaction-span windowing + LLM segmentation parse/validate/resolve.

   Segmentation LLM contract (line format):
     <first>-<last>: <gist>
   Bare `<n>:` is accepted as `<n>-<n>:`. Non-matching lines are ignored."
  (:require
    [clojure.string :as str]
    [isaac.drive.dispatch :as dispatch]
    [isaac.episodes.distill :as distill]
    [isaac.episodes.ids :as ids]
    [isaac.llm.api.protocol :as api]
    [isaac.logger :as log]))

(def DEFAULT_SIZE_CAP 80)

(def ^:private BOUNDARY_LINE
  #"^\s*(?:(\d+)\s*-\s*(\d+)|(\d+))\s*:\s*(.+?)\s*$")

(defn parse-scenes
  "Parse LLM text into a vector of {:start :end :gist} maps.

   Line format only — non-matching lines (preamble, fences, blanks) are
   ignored. Returns [] when no boundary lines are found (caller treats
   empty as a parse failure against tiling)."
  [text]
  (if-not (string? text)
    []
    (->> (str/split-lines text)
         (keep (fn [line]
                 (when-let [[_ a b solo gist] (re-matches BOUNDARY_LINE line)]
                   (let [start (Long/parseLong (or a solo))
                         end   (Long/parseLong (or b solo))]
                     {:start start
                      :end   end
                      :gist  (str/trim gist)}))))
         vec)))

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

(defn- provider-error?
  "True when a chat response is a provider/API error map (not content)."
  [response]
  (boolean (and (map? response) (contains? response :error))))

(defn- provider-label [provider]
  (try
    (or (api/display-name provider) "provider")
    (catch Exception _ "provider")))

(defn- format-provider-error [provider response]
  (let [pname (provider-label provider)
        err   (or (:error response) :error)
        err-s (if (keyword? err) (clojure.core/name err) (str err))
        msg   (or (:message response) (:content response))]
    (if (str/blank? (str msg))
      (str pname ": " err-s)
      (str pname ": " err-s " — " msg))))

(def ^:private RAW_LOG_MAX 500)

(defn- truncate-raw [s]
  (let [s (str s)]
    (if (<= (count s) RAW_LOG_MAX)
      s
      (str (subs s 0 RAW_LOG_MAX) "…"))))

(defn segment-span!
  "Call the gist model once (with one retry on parse failure) to segment a span.

   Returns:
     {:ok scenes}
     {:error :flagged :raw text}
     {:error :provider-error :provider name :error-key k :message ...}"
  [provider model distilled-messages preceding-summary]
  (let [prompt (distill/format-span-prompt distilled-messages preceding-summary)
        request {:model model
                 :messages [{:role "user" :content prompt}]}
        attempt (fn []
                  (let [response (dispatch/dispatch-chat provider request)]
                    (if (provider-error? response)
                      {:error      :provider-error
                       :provider   (provider-label provider)
                       :error-key  (:error response)
                       :message    (format-provider-error provider response)
                       :response   response}
                      (let [text   (response-text response)
                            scenes (parse-scenes text)]
                        (if (and (seq scenes)
                                 (valid-tiling? (count distilled-messages) scenes))
                          {:ok (resolve-ordinals distilled-messages scenes) :raw text}
                          {:error :bad-parse :raw text})))))
        first-try (attempt)]
    (cond
      (:ok first-try)
      first-try

      (= :provider-error (:error first-try))
      first-try

      :else
      (let [second-try (attempt)]
        (cond
          (:ok second-try)
          second-try

          (= :provider-error (:error second-try))
          second-try

          :else
          (let [raw (or (:raw second-try) (:raw first-try) "")]
            (log/warn :episodes/segment-flagged
                      :raw (truncate-raw raw))
            {:error :flagged
             :raw   raw}))))))

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
