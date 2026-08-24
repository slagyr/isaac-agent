(ns isaac.turnstile
  "Named turnstile registry plus the built-in null (run-now) gate.

  Turnstiles admit turns one at a time. SUBMITTED refs ride a single request
  (name or [name params]); they are never ambient. Unknown names refuse before
  dispatch. A throwing release is isolated — log and continue."
  (:require
    [clojure.string :as str]
    [isaac.logger :as log]))

(defprotocol Turnstile
  (admit? [this ctx])
  (release! [this token]))

(defrecord ReleaseToken [id])

(defonce ^:private factories* (atom {}))

(defn register!
  "Register a turnstile factory under `name` (keyword). The factory is
   (fn [params] turnstile) — params is nil for a name-only ref."
  [name factory]
  (swap! factories* assoc (keyword name) factory)
  name)

(defn unregister! [name]
  (swap! factories* dissoc (keyword name)))

(defn clear! []
  (reset! factories* {}))

(defn resolve
  "Resolve a registered factory by name and invoke it with no params.
   Returns nil when the name is unknown."
  [name]
  (when-let [factory (get @factories* (keyword name))]
    (factory nil)))

(defn- ref-name [ts-ref]
  (if (sequential? ts-ref)
    (first ts-ref)
    ts-ref))

(defn- ref-params [ts-ref]
  (when (sequential? ts-ref)
    (vec (rest ts-ref))))

(defn resolve-ref
  "Resolve a submitted turnstile ref — a name keyword or [name & params].
   Returns nil when the name is unknown."
  [ts-ref]
  (when-let [factory (get @factories* (keyword (ref-name ts-ref)))]
    (factory (ref-params ts-ref))))

(defn parse-ref
  "Parse a CLI --turnstile value: `worksite` or `worksite:chart-room`."
  [s]
  (let [s (str s)]
    (if-let [colon (str/index-of s ":")]
      (let [name   (keyword (subs s 0 colon))
            params (str/split (subs s (inc colon)) #"/")]
        (into [name] params))
      (keyword s))))

(defn unknown-turnstile-message [ts-ref]
  (str "unknown turnstile: " (name (ref-name ts-ref))))

(defn resolve-submitted
  "Resolve a seq of submitted refs. Returns {:turnstiles [...]} or
   {:error :unknown-turnstile :message ... :ref ...}."
  [refs]
  (loop [remaining refs
         acc       []]
    (if-let [ts-ref (first remaining)]
      (if-let [ts (resolve-ref ts-ref)]
        (recur (rest remaining) (conj acc ts))
        {:error   :unknown-turnstile
         :message (unknown-turnstile-message ts-ref)
         :ref     ts-ref})
      {:turnstiles acc})))

(defn null-turnstile
  "Built-in: always :pass. Today's run-now semantics."
  ([] (null-turnstile nil))
  ([_params]
   (reify Turnstile
     (admit? [_ _ctx] :pass)
     (release! [_ _token] nil))))

(defn register-entry!
  "Per-entry factory for the :isaac.agent/turnstiles berth.
   Receives `[name entry]`; resolves the entry's symbol-valued :factory
   and registers it under `name`."
  [[name entry]]
  (let [factory (some-> (:factory entry) requiring-resolve var-get)]
    (register! name factory)))

(defn registered-names
  "Keywords currently registered in the factory map."
  []
  (set (keys @factories*)))

(defn- ->token [decision]
  (cond
    (= :pass decision) (->ReleaseToken (str (java.util.UUID/randomUUID)))
    (and (map? decision) (= :pass (:status decision)))
    (or (:token decision) (->ReleaseToken (str (java.util.UUID/randomUUID))))
    :else nil))

(defn- refuse-reason [decision]
  (cond
    (= :hold decision) :hold
    (and (map? decision) (contains? decision :refuse)) (:refuse decision)
    :else decision))

(defn- release-one! [{:keys [turnstile token]}]
  (try
    (release! turnstile token)
    (catch Throwable t
      (log/warn :turnstile/release-failed
                :error (.getMessage t)
                :ex-class (.getName (class t))))))

(defn release-all!
  "Invoke acquired tokens in reverse acquisition order. Isolated: a throwing
   release is logged and the rest still run."
  [tokens]
  (doseq [acquired (reverse tokens)]
    (release-one! acquired)))

(defn admit-all!
  "Ask every turnstile to admit. On :pass, collect a release token. On
   :hold or {:refuse reason}, release already-acquired tokens and refuse.
   Zero turnstiles is identity."
  [turnstiles ctx]
  (loop [remaining turnstiles
         acquired  []]
    (if-let [ts (first remaining)]
      (let [decision (admit? ts ctx)]
        (if-let [token (->token decision)]
          (recur (rest remaining) (conj acquired {:turnstile ts :token token}))
          (do
            (release-all! acquired)
            {:error :refused :reason (refuse-reason decision) :tokens []})))
      {:tokens acquired})))
