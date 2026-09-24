(ns isaac.drive.accounting
  "What a request is made of, measured at send time.

   The per-turn total already rides on the transcript. What was missing is the
   per-request line — and the composition behind it. \"The request was 358k\"
   does not say why; \"transcript 326k of 358k\" does (isaac-5nx5).

   This lands in the structured log and nowhere else. The transcript is context
   that gets re-sent, so writing accounting into it would make the very thing
   being measured more expensive."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.llm.usage :as usage]
    [isaac.logger :as log]))

;; region ----- Measurement -----

(defn- text-tokens
  "Characters over four — the same yardstick the compaction gauge uses. Blank
   text is nothing, not the one token `estimate-tokens` floors at."
  [text]
  (let [s (str (or text ""))]
    (if (str/blank? s)
      0
      (long (Math/ceil (/ (double (count s)) 4.0))))))

(defn- wire-tokens
  "Serialized size over four. Messages and tool schemas travel as JSON, and a
   tool schema's bulk is its parameter shape — content-only counting reads it
   as nothing."
  [value]
  (if (empty? value)
    0
    (text-tokens (json/generate-string value))))

(defn- system-role? [message]
  (= "system" (:role message)))

(defn- system-text [request]
  (or (:system request)
      (some->> (:messages request) (filter system-role?) (map :content) (remove nil?) (str/join "\n\n"))))

(defn compose
  "The tokens one request carries, broken down by the parts that built it.

   `parts` are the same values `build-chat-request` was handed — :soul,
   :boot-files, :rules-text, :skill-menu-text. Whatever of the system prefix
   they do not account for is :framing-tokens (the injection guard, the session
   identity block, the tool-batching hint)."
  [request {:keys [soul boot-files rules-text skill-menu-text]}]
  (let [messages    (vec (:messages request))
        system      (text-tokens (system-text request))
        soul*       (text-tokens soul)
        boot*       (text-tokens boot-files)
        rules*      (text-tokens rules-text)
        skills*     (text-tokens skill-menu-text)
        tools*      (wire-tokens (vec (:tools request)))
        transcript* (wire-tokens (vec (remove system-role? messages)))]
    {:total-tokens      (+ system tools* transcript*)
     :system-tokens     system
     :soul-tokens       soul*
     :boot-files-tokens boot*
     :rules-tokens      rules*
     :skill-menu-tokens skills*
     :framing-tokens    (max 0 (- system (+ soul* boot* rules* skills*)))
     :tools-tokens      tools*
     :transcript-tokens transcript*
     :messages          (count messages)
     :tool-count        (count (:tools request))}))

(defn reconcile
  "What the provider charged against what Isaac assembled. Nil when the
   provider said nothing about this request's prompt — a gap cannot be claimed
   from a number nobody reported."
  [composition request-usage]
  (let [u        (usage/normalize request-usage)
        reported (:prompt-tokens u)]
    (when (and (usage/supported? u) (number? reported) (pos? reported))
      (let [estimated (or (:total-tokens composition) 0)]
        {:estimated-tokens       estimated
         :reported-prompt-tokens reported
         :unaccounted-tokens     (- reported estimated)
         :ratio                  (if (pos? estimated)
                                   (double (/ (Math/round (* 100.0 (/ (double reported) estimated))) 100.0))
                                   0.0)}))))

;; endregion ^^^^^ Measurement ^^^^^

;; region ----- Logging -----

(defn log-request!
  "Log what this request is made of, at the moment it is sent. Returns the
   composition so the reply can be reconciled against it."
  [{:keys [session provider model cycle]} request parts]
  (let [composition (compose request parts)]
    (log/info :turn/request-sent
              (cond-> composition
                      session (assoc :session session)
                      provider (assoc :provider provider)
                      model (assoc :model model)
                      cycle (assoc :cycle cycle)))
    composition))

(defn- cycle-prompts [response]
  (->> (:cycle-usages response)
       (map :prompt-tokens)
       (filter #(and (number? %) (pos? %)))
       vec))

(defn log-measured!
  "Log the provider's own figure for the request just sent, beside Isaac's.
   A provider that drives its own tool loop answers one Isaac request with many
   of its own; :provider-cycles says how many, so a gap is not mistaken for a
   single oversized prompt."
  [{:keys [session provider cycle]} composition response]
  (when-let [reconciled (reconcile composition (:usage response))]
    (let [prompts (cycle-prompts response)]
      (log/info :turn/request-measured
                (cond-> reconciled
                        session (assoc :session session)
                        provider (assoc :provider provider)
                        cycle (assoc :cycle cycle)
                        (seq prompts) (assoc :provider-cycles (count prompts)
                                             :provider-cycle-max (apply max prompts)
                                             :provider-cycle-min (apply min prompts)))))
    reconciled))

(defn measuring
  "Wrap a chat-fn so every request it sends is logged with its composition and
   every reply is reconciled against it."
  [chat-fn info-fn parts]
  (fn [request]
    (let [info        (info-fn)
          composition (log-request! info request parts)
          response    (chat-fn request)]
      (when (map? response)
        (log-measured! info composition response))
      response)))

;; endregion ^^^^^ Logging ^^^^^
