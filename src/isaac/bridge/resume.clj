(ns isaac.bridge.resume
  (:require
    [cheshire.core :as json]
    [clojure.pprint :as pprint]
    [clojure.string :as str]
    [isaac.bridge.core :as bridge]
    [isaac.charge :as charge]
    [isaac.comm.null :as null-comm]
    [isaac.drive.turn :as turn]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.session.store.impl-common :as store-common]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as store]
    [isaac.session.transcript :as transcript])
  (:import
    (java.time Instant)))

(def default-resume-window-ms 600000)

(def ^:private resume-note
  "interrupted by a restart; continue from the transcript.")

(def ^:private synthesized-tool-result
  "Interrupted before/during execution; result unknown — verify side effects before repeating.")

(defn- write-edn [value]
  (binding [*print-namespace-maps* false]
    (with-out-str (pprint/pprint value))))

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

(defn- comm-stale? [marker window-ms now-ms]
  (and (= :comm (:source marker))
       (when-let [t (marker-time-ms marker)]
         (> (- now-ms t) window-ms))))

(defn- crash-orphan? [marker]
  (and (= :hail (:source marker)) (not (:suspended marker))))

(defn- resume-attempts [marker]
  (let [base (or (:attempts marker) 0)]
    (if (crash-orphan? marker) (inc base) base)))

(defn- normalize-id [value]
  (cond
    (keyword? value) (name value)
    (string? value)  value
    (nil? value)     nil
    :else            (str value)))

(defn- marker->delivery [marker]
  (or (:delivery marker)
      (when (= :hail (:source marker))
        (cond-> {:id            (or (:delivery-id marker)
                                    (some-> (:delivery marker) :id normalize-id))
                 :prompt        (or (:prompt marker) (get-in marker [:delivery :prompt]))
                 :crew          (or (:crew marker) (get-in marker [:delivery :crew]))
                 :bound-session (or (:bound-session marker) (get-in marker [:delivery :bound-session]))
                 :attempts      (or (:attempts marker) 0)}
          (:thread-id marker) (assoc :thread-id (:thread-id marker))
          (:params marker)    (assoc :params (:params marker))))))

(defn- deliveries-path [root delivery-id]
  (str root "/hail/deliveries/" delivery-id ".edn"))

(defn- write-delivery! [root delivery]
  (let [fs*  (filesystem)
        path (deliveries-path root (:id delivery))
        temp (str path ".tmp")]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* temp (write-edn delivery))
    (fs/move fs* temp path)))

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
    (seq (clojure.set/difference tool-call-ids tool-result-ids))))

(declare sync-truncated-transcript!)

(defn- repair-dangling-tool-calls! [session-store session-id]
  (let [transcript (store/get-transcript session-store session-id)
        dangling   (dangling-tool-call-ids transcript)]
    (when (seq dangling)
      (log/warn :resume/transcript-repair
                :session session-id
                :repair :dangling-tool-call
                :tool-call-ids (vec dangling))
      (doseq [call-id dangling]
        (store/append-message! session-store session-id
                               {:role       "toolResult"
                                :toolCallId call-id
                                :content    synthesized-tool-result}))
      true)))

(defn- truncate-torn-transcript! [session-store session-id root session-file fs]
  (let [path (store-common/transcript-path root session-file)]
    (when (fs/exists? fs path)
      (let [raw   (fs/slurp fs path)
            lines (str/split-lines raw)
            valid (loop [n (count lines)]
                    (if (zero? n)
                      []
                      (let [candidate (take n lines)]
                        (if (every? #(try (json/parse-string % true) (catch Exception _ false))
                                    candidate)
                          candidate
                          (recur (dec n))))))]
        (when (< (count valid) (count lines))
          (log/warn :resume/transcript-repair
                    :session session-id
                    :repair :torn-line
                    :dropped-lines (- (count lines) (count valid)))
          (let [entries (mapv #(json/parse-string % true) valid)]
            (store-common/write-transcript! root session-file entries fs)
            (sync-truncated-transcript! session-store session-id entries)
            true))))))

(defn- sync-truncated-transcript! [session-store session-id entries]
  (when (instance? isaac.session.store.memory.MemorySessionStore session-store)
    (memory-store/replace-transcript! session-store session-id entries)))

(defn- repair-transcript! [session-store root session-id]
  (let [session (store/get-session session-store session-id)
        fs      (filesystem)]
    (or (when session
          (truncate-torn-transcript! session-store session-id root (:session-file session) fs))
        (repair-dangling-tool-calls! session-store session-id))))

(defn- requeue-hail! [root marker]
  (when-let [delivery (some-> (marker->delivery marker)
                              (assoc :attempts (resume-attempts marker)))]
    (write-delivery! root delivery)))

(defn- dispatch-comm-resume! [session-id cfg]
  (turn/run-turn!
    (charge/build {:config      cfg
                   :session-key session-id
                   :input       resume-note
                   :comm        null-comm/channel})))

(defn- resume-marker!
  [{:keys [session-store root cfg window-ms now-ms]} marker]
  (let [session-id (or (:session-id marker) (get marker "session-id"))
        source     (:source marker)]
    (if (comm-stale? marker window-ms now-ms)
      (do
        (log/info :resume/comm-stale :session session-id)
        (store/clear-turn-marker! session-store session-id))
      (do
        (when (crash-orphan? marker)
          (log/warn :resume/crash-orphan :session session-id))
        (when (repair-transcript! session-store root session-id)
          nil)
        (try
          (cond
            (= :hail source)
            (requeue-hail! root marker)

            (#{:comm :cron :cli} source)
            (dispatch-comm-resume! session-id cfg)

            :else nil)
          (finally
            (store/clear-turn-marker! session-store session-id)
            (when root
              (store-common/clear-turn-marker!* root session-id (filesystem)))))))))

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
        opts       {:session-store session-store
                    :root          root
                    :cfg           cfg
                    :window-ms     window-ms
                    :now-ms        now-ms}]
    (doseq [marker (store/turn-markers session-store)]
      (resume-marker! opts marker))
    nil))