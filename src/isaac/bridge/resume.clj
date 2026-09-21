(ns isaac.bridge.resume
  (:require
    [clojure.set :as set]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.session.policy :as policy]
    [isaac.session.store.impl-common :as store-common]
    [isaac.session.store.spi :as store]
    [isaac.turn.queue :as queue])
  (:import
    (java.time Instant)))

(def default-resume-window-ms 600000)

(def ^:private resume-note
  "interrupted by a restart; continue from the transcript.")

(def ^:private synthesized-tool-result
  "Interrupted before/during execution; result unknown — verify side effects before repeating.")

(defn- filesystem []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- instant->epoch-ms [v]
  (cond
    (instance? Instant v) (.toEpochMilli ^Instant v)
    (string? v) (.toEpochMilli (Instant/parse v))
    (number? v) (long v)
    :else (throw (ex-info "resume requires Instant, ISO string, or epoch ms" {:now v}))))

(defn- marker-time-ms [marker]
  (some-> (or (:interrupted-at marker) (:started-at marker)) instant->epoch-ms))

(defn- comm-stale?
  "Only an interactive turn goes stale. Every other source is a work order:
   nobody is waiting on it, so however old it is, it still wants doing."
  [marker window-ms now-ms]
  (and (= :comm (:source marker))
       (when-let [t (marker-time-ms marker)]
         (> (- now-ms t) window-ms))))

(defn- dangling-tool-call-ids [transcript]
  (let [tool-call-ids (->> transcript
                           (filter #(= "message" (:type %)))
                           (mapcat store-common/entry-toolcall-ids)
                           set)
        tool-result-ids (->> transcript
                             (filter #(= "message" (:type %)))
                             (keep (fn [entry]
                                     (when (= "toolResult" (get-in entry [:message :role]))
                                       (or (get-in entry [:message :toolCallId])
                                           (get-in entry [:message :id])
                                           (:id entry)))))
                             set)]
    (seq (set/difference tool-call-ids tool-result-ids))))

(defn- repair-dangling-tool-calls! [sess session-id]
  (let [transcript (policy/get-transcript sess session-id)
        dangling   (dangling-tool-call-ids transcript)]
    (when (seq dangling)
      (log/warn :resume/transcript-repair
                :session session-id
                :repair :dangling-tool-call
                :tool-call-ids (vec dangling))
      (doseq [call-id dangling]
        (policy/append-message! sess session-id
                                {:role       "toolResult"
                                 :toolCallId call-id
                                 :content    synthesized-tool-result}))
      true)))

(defn- session-policy
  "The crew policy for a session id. The session record read is a primitive
   (policy-neutral); everything transcript-shaped goes through the policy."
  [session-store cfg session-id]
  (policy/for-crew (:crew (store/get-session session-store session-id)) (or cfg {}) session-store))

(defn- repair-transcript! [session-store cfg session-id]
  (let [sess (session-policy session-store cfg session-id)]
    (or (policy/repair-transcript! sess session-id)
        (repair-dangling-tool-calls! sess session-id))))

(defn- enqueue-resume-turn!
  "Parks the resumed turn in the normal turn queue (isaac-yxch). Resume is
   recovery work, not boot work: the scan hands the turn over and returns, so
   starting the components never waits on a turn that may legitimately run for
   minutes. The queue worker drives it and logs its own outcome."
  [{:keys [session-store root cfg]} session-id marker]
  (try
    ;; The note is persisted here, the way a parked CLI turn persists its user
    ;; message at submit time: the queue worker drives a :from-queue? charge and
    ;; never re-appends the input.
    (policy/append-message! (session-policy session-store cfg session-id) session-id
                            {:role "user" :content resume-note})
    (binding [queue/*root* root]
      (queue/enqueue! {:session session-id
                       :input   resume-note
                       :origin  {:kind :resume :source (:source marker)}
                       :root    root}))
    true
    (catch Throwable t
      (log/warn :resume/enqueue-failed
                :session session-id
                :error (.getMessage t))
      false)))

(defn- clear-marker! [session-store root session-id]
  (store/clear-turn-marker! session-store session-id)
  (when root
    (store-common/clear-turn-marker!* root session-id (filesystem))))

(defn- resume-marker!
  [{:keys [session-store root cfg window-ms now-ms] :as opts} marker]
  (let [session-id (or (:session-id marker) (get marker "session-id"))]
    (cond
      (true? (:cancelled marker))
      (do
        (clear-marker! session-store root session-id)
        {:dropped 1})

      (comm-stale? marker window-ms now-ms)
      (do
        (log/info :resume/comm-stale :session session-id)
        (store/clear-turn-marker! session-store session-id)
        {:dropped 1})

      (and (true? (:suspended marker))
           (when-let [retry (:retry-at marker)]
             (< now-ms (instant->epoch-ms retry))))
      (do
        (log/info :resume/weather-deferred
                  :session session-id
                  :retry-at (:retry-at marker))
        {:deferred 1})

      (and (true? (:suspended marker))
           (:retry-at marker))
      (do
        (log/info :turn/resumed
                  :session session-id
                  :trigger :boot
                  :suspended-ms (when-let [at (:suspended-at marker)]
                                  (- now-ms (instant->epoch-ms at))))
        (repair-transcript! session-store cfg session-id)
        (try
          (if (enqueue-resume-turn! opts session-id marker)
            {:requeued 1}
            {:dropped 1})
          (finally
            (clear-marker! session-store root session-id))))

      :else
      (do
        (repair-transcript! session-store cfg session-id)
        (try
          (if (enqueue-resume-turn! opts session-id marker)
            {:requeued 1}
            {:dropped 1})
          (finally
            (clear-marker! session-store root session-id)))))))

(defn resume-interrupted-turns!
  [{:keys [session-store root now cfg resume-window-ms]
    :or   {resume-window-ms default-resume-window-ms}}]
  (when-not session-store
    (throw (ex-info "resume-interrupted-turns! requires :session-store" {})))
  (let [root       (or root (nexus/get :root) (loader/root))
        cfg        (or cfg (loader/snapshot "startup resume scan"))
        now-ms     (instant->epoch-ms (or now (Instant/now)))
        window-ms  (or resume-window-ms
                       (get-in cfg [:turn-resume-window-ms])
                       default-resume-window-ms)
        markers    (vec (store/turn-markers session-store))
        opts       {:session-store session-store
                    :root          root
                    :cfg           cfg
                    :window-ms     window-ms
                    :now-ms        now-ms}
        summary    (reduce (fn [acc marker]
                             (merge-with + acc (or (resume-marker! opts marker) {})))
                           {:markers  (count markers)
                            :requeued 0
                            :dropped  0}
                           markers)]
    (log/info :resume/scan-complete
              :markers (:markers summary)
              :requeued (:requeued summary)
              :dropped (:dropped summary))
    nil))