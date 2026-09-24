(ns isaac.llm.usage
  "One shape for token accounting, spoken by every provider.

   Only the provider sees the wire response, so only the provider can say what
   a request cost — but nothing provider-specific decides what is recorded.
   An adapter hands back `{:prompt-tokens n :output-tokens n ...}`; the drive
   totals it here and nowhere else.

   A provider that genuinely cannot measure a request says so with
   `unsupported`. That matters: an absent usage map summed as zero is
   indistinguishable from a free request, and a turn quietly reporting a
   fraction of its real cost is how token accounting goes wrong without
   anyone noticing (isaac-5nx5).")

(def zero
  "The counts every usage map carries."
  {:prompt-tokens 0 :output-tokens 0})

(def empty-turn
  "A turn that has made no requests yet."
  {:requests 0 :prompt-tokens 0 :output-tokens 0})

(def ^:private declarations
  "Keys that describe a usage map rather than count anything."
  [:supported? :unsupported-reason :prompt-scope])

(defn unsupported
  "What a provider returns when it cannot measure a request at all."
  ([] (unsupported nil))
  ([reason]
   (cond-> (assoc zero :supported? false)
           reason (assoc :unsupported-reason reason))))

(defn supported?
  "False only when a usage map says outright that it measured nothing."
  [usage]
  (not (false? (:supported? usage))))

(defn normalize
  "Coerce one request's usage into the common shape. A missing or empty map is
   not a zero — it is unmeasured, and comes back saying so."
  [usage]
  (cond
    (not (map? usage)) (unsupported :absent)
    (false? (:supported? usage)) (merge (unsupported) usage)
    (empty? (apply dissoc usage declarations)) (unsupported :absent)
    :else (merge zero (dissoc usage :prompt-scope))))

(defn- counts [usage]
  (into {} (filter (comp number? val)) (apply dissoc usage declarations)))

(defn add
  "Fold one request's usage into a turn total. An unmeasured request still
   counts as a request and is named, never absorbed as a zero."
  [turn usage]
  (let [u (normalize usage)]
    (if (supported? u)
      (-> (merge-with + turn (counts u))
          (update :requests inc))
      (-> turn
          (update :requests inc)
          (update :unsupported-requests (fnil inc 0))))))

(defn total
  "Total a sequence of per-request usages."
  [usages]
  (reduce add empty-turn usages))

(defn reported?
  "Whether a turn total says anything at all about what the turn cost. False
   for a turn that made requests and came back with nothing but zeros — which
   is a provider that does not report, not a turn that was free."
  [turn]
  (boolean (some #(and (number? %) (pos? %))
                 [(:prompt-tokens turn) (:output-tokens turn)])))
