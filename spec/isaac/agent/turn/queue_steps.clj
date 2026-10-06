(ns isaac.agent.turn.queue-steps
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.foundation.fs-steps :as fsteps]
    [isaac.foundation.config.api :as config]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.fs :as fs]
    [isaac.agent.llm.api.grover :as grover]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.scheduler.runtime :as scheduler]
    [isaac.agent.session.session-steps :as session-steps]
    [isaac.agent.spec-helper :as helper]
    [isaac.agent.tool.memory :as memory]
    [isaac.agent.turn.queue :as queue]
    [isaac.agent.turn.submit :as submit]
    [isaac.agent.turn.worker :as worker]
    [isaac.agent.resource-pool :as pool])
  (:import
    (java.time Instant)))

(helper! isaac.agent.turn.queue-steps)

(defonce ^:private scripted-gates* (atom {}))

(defn- root-dir []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- parse-iso [iso]
  (let [s (if (re-find #"[zZ]|[+-]\d{2}:?\d{2}$" iso) iso (str iso "Z"))]
    (Instant/parse s)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (mem-fs)}
    (f)))

(defn- interpolate-held [s]
  (cond-> (str s)
    (g/get :held-id) (str/replace "#held-id" (g/get :held-id))
    (g/get :turn-id) (str/replace "#turn-id" (g/get :turn-id))))

(defonce ^:private parse-argv-wrapped?
  (do
    (alter-var-root #'fcli/parse-argv
      (fn [orig]
        (fn [args]
          (orig (interpolate-held args)))))
    true))

(fcli/register-isaac-run-wrapper!
  (fn [thunk]
    (binding [queue/*root* (root-dir)
              memory/*now* (or (g/get :current-time) memory/*now*)]
      (thunk))))

(fcli/register-isaac-run-postflight!
  (fn []
    (when-let [output (g/get :output)]
      (when-let [[_ id] (re-find #"(?:held|waiting):\s+([a-z0-9-]+)" output)]
        (g/assoc! :held-id id))
      (when-let [[_ id] (re-find #"queued:\s+([a-z0-9-]+)" output)]
        (g/assoc! :turn-id id)))))

(fcli/register-isaac-run-preflight!
  (fn []
    (when-let [held-id (g/get :held-id)]
      (g/assoc! :held-id held-id))))

(defn- ensure-wake-hook! []
  (pool/set-wake-hook! worker/tick!))

(defn turn-queue-ticks-at [iso]
  (ensure-wake-hook!)
  (let [now (parse-iso iso)]
    (g/assoc! :current-time now)
    ;; Outside the nested scopes below: the worker wakes on the process-wide
    ;; snapshot, and a config registered inside a nested nexus dies with it.
    (session-steps/with-feature-config! "turn queue tick"
      (fn []
        (with-feature-fs
          (fn []
            (binding [memory/*now* now
                      queue/*root* (root-dir)]
              (nexus/-with-nested-nexus {:root (root-dir) :fs (mem-fs)}
                (config/dangerously-install-config! (g/get :feature-config) "feature: queue tick")
                ;; worker/tick! only claims and starts each runnable turn
                ;; before returning (isaac-e9jl); await-idle! waits out that
                ;; turn (and any it chains via a drain-on-release nudge)
                ;; before the next step runs.
                (worker/tick! {:now now})
                (worker/await-idle!)))))))))

(defonce ^:private live-scheduler* (atom nil))

(defn- shutdown-live-scheduler! []
  (when-let [running @live-scheduler*]
    (worker/stop! {:scheduler running
                   :task-id       :turn.queue/tick
                   :sweep-task-id :turn/sweep-weather})
    (scheduler/shutdown! running)
    ;; A dead scheduler left in the nexus makes every later scenario think
    ;; background services are available.
    (nexus/deregister! [:scheduler])
    (reset! live-scheduler* nil)))

(defn weather-sweep-started []
  (shutdown-live-scheduler!)
  ;; Created, not started: the assertion reads the registered tasks, and a
  ;; running scheduler would drive turns in the background of every scenario
  ;; that follows this one.
  (let [instance (scheduler/create {:clock (fn [] (parse-iso "2026-04-21T10:00:00Z"))})]
    (nexus/register! [:scheduler] instance)
    (worker/start! {})
    (reset! live-scheduler* instance)
    ;; isaac.foundation.scheduler-steps reads the scheduler from here; its
    ;; "the scheduled tasks include:" step does the asserting.
    (g/assoc! :scheduler instance)))

(defn- scripted-gate [name n]
  (let [state (or (get @scripted-gates* name)
                  (let [fresh {:open?     (atom true)
                               :inflight  (atom 0)
                               :limit     n
                               :bindings (atom {})
                               :leases (atom #{})}]
                    (swap! scripted-gates* assoc name fresh)
                    fresh))]
    (swap! scripted-gates* assoc-in [name :limit] n)
    (reify pool/ResourcePool
      (try-acquire [_ _ctx]
        (if (false? @(:open? state))
          :busy
          ;; Claimed turns for different sessions now start concurrently
          ;; (isaac-e9jl), so two futures can call try-acquire at once —
          ;; check-then-swap on :inflight would let both through. swap-vals!
          ;; makes "am I under the limit" and "claim a slot" one atomic step:
          ;; only a call whose pre-swap value was still under the limit won.
          (let [limit (:limit state)
                [before _after] (swap-vals! (:inflight state)
                                            (fn [n] (if (< n limit) (inc n) n)))]
            (if (< before limit)
              (let [id (str (java.util.UUID/randomUUID))]
                (swap! (:leases state) conj id)
                {:bindings @(:bindings state) :release-id id})
              :busy))))
      (release! [_ token]
        (let [id (or (:release-id token) (:id token))]
          (when (contains? @(:leases state) id)
            (swap! (:leases state) disj id)
            (swap! (:inflight state) dec)))))))

(defn register-admits-n-resource-pool [name n]
  (let [n (if (string? n) (parse-long n) n)]
    (pool/register! :scripted (fn [{:keys [name limit]}] (scripted-gate name limit)))
    (scripted-gate name n)
    (fsteps/isaac-edn-file-exists
      (str "config/resource-pools/" name ".edn")
      {:headers ["path" "value"]
       :rows [["type" ":scripted"] ["name" (pr-str name)] ["limit" (str n)]]})))

(defn scripted-pool-binds [name table]
  (register-admits-n-resource-pool name 1)
  (let [state (get @scripted-gates* name)]
    (reset! (:bindings state)
            (into {} (map (fn [[key value]] [(keyword key) (if (= key "session/cwd")
                                             (str (root-dir) "/" value) value)]) (:rows table))))))

(defn scripted-pool-has-lease [name id]
  (register-admits-n-resource-pool name 1)
  (let [state (get @scripted-gates* name)]
    (swap! (:leases state) conj id)
    (swap! (:inflight state) inc)))

(defn close-resource-pool [name]
  (let [state (or (get @scripted-gates* name)
                  (let [fresh {:open?     (atom false)
                               :inflight  (atom 0)
                               :limit     1}]
                    (swap! scripted-gates* assoc name fresh)
                    fresh))]
    (reset! (:open? state) false)))

(defn open-resource-pool [name]
  (ensure-wake-hook!)
  (when-let [state (get @scripted-gates* name)]
    (reset! (:open? state) true)
    (session-steps/with-feature-config! "resource pool opened"
      (fn []
        (config/dangerously-install-config! (g/get :feature-config) "feature: pool opened")
        (pool/release-all! [{:resource-pool (scripted-gate name (:limit state))
                             :token (pool/->ReleaseToken "open")}])
        ;; release-all! only wakes the queue (worker/tick!, via the wake-hook)
        ;; — that only claims and starts the newly-admitted turn before
        ;; returning (isaac-e9jl); await it before the next step runs.
        (worker/await-idle!)))))

;; isaac-r209: a hail submits a charge by frequencies alone (crew/tags),
;; unresolved to any session until the turn queue claims it — the same
;; entry point isaac-hail uses (isaac.agent.turn.submit/submit!), not the
;; CLI's --session/--crew resolve-at-submit path. Two submits back to back,
;; before any tick, land both records in queue/list-held as :queued so a
;; single "the turn queue ticks at" claims both in the same pass.
(defn- do-submit! [input frequencies resource-pools]
  (session-steps/with-feature-config! "hail submit"
    (fn []
      (with-feature-fs
        (fn []
          (binding [queue/*root* (root-dir)]
            (nexus/-with-nested-nexus {:root (root-dir) :fs (mem-fs)}
              (config/dangerously-install-config! (g/get :feature-config) "feature: hail submit")
              (g/dissoc! :submit-error)
              (try
                (let [accepted (submit/submit! {:root           (root-dir)
                                                 :config         (g/get :feature-config)
                                                 :frequencies    frequencies
                                                 :resource-pools resource-pools
                                                 :prompt         input
                                                 :origin         {:kind :hail}})]
                  (g/assoc! :turn-id (:id accepted))
                  (g/assoc! :held-id (:id accepted))
                  accepted)
                (catch Exception e
                  (g/assoc! :submit-error (ex-message e))
                  nil)))))))))

(defn turn-submitted-with-frequencies [input table]
  (let [frequencies (into {} (map (fn [[key value]]
                                    [(keyword key) (if (= key "create") (keyword value) value)])
                                  (:rows table)))]
    (do-submit! input frequencies nil)))

(defn turn-submitted-to-crew [input crew]
  (do-submit! input {:crew crew} nil))

(defn turn-submitted-to-crew-with-pools [input crew resource-pools]
  (do-submit! input {:crew crew} (mapv keyword (str/split resource-pools #",\s*"))))

(defn turn-submitted-to-crew-with-create [input crew create-mode]
  (do-submit! input {:crew crew :create (keyword create-mode)} nil))

(defn hail-submission-failed [expected]
  (g/should (some? (g/get :submit-error)))
  (g/should (str/includes? (g/get :submit-error) expected)))

(defn hail-submission-did-not-wait []
  (g/should= [] (queue/list-held)))

(defn user-sends-with-resource-pools [content key-str resource-pools]
  (ensure-wake-hook!)
  (session-steps/user-sends-on-session
    content key-str (mapv keyword (str/split resource-pools #",\s*"))))

(defn session-waiting-on-model
  "Polls isaac.agent.llm.api.grover/waiting? — true once that session's turn has
   dequeued a scripted response tagged `wait` and is blocked in Grover's
   wait-gate, rather than having finished or never having started."
  [n session]
  (helper/await-condition #(grover/waiting? session) (* 1000 n))
  (g/should (grover/waiting? session)))

(defn model-releases-session
  "Delivers isaac.agent.llm.api.grover/release-wait! for that session's wait-gate,
   unblocking the turn that isaac.agent.llm.api.grover/waiting? found parked there."
  [session]
  (grover/release-wait! session))

(g/after-scenario
  (fn []
    ;; A scenario can end (or move to the next step) without every turn it
    ;; started having settled — e.g. a release that wakes the queue but
    ;; whose admitted turn the scenario never asserted on. worker/tick! only
    ;; claims and starts before returning (isaac-e9jl), so a straggler can
    ;; still be running when the next scenario resets scripted-gates* out
    ;; from under it. Best-effort drain before tearing anything else down.
    (worker/await-idle! 2000)
    (reset! scripted-gates* {})
    (g/dissoc! :held-id)
    (g/dissoc! :turn-id)
    (shutdown-live-scheduler!)
    (g/dissoc! :scheduler)
    (pool/set-wake-hook! nil)))

(defwhen #"the turn queue ticks at \"([^\"]+)\"" isaac.agent.turn.queue-steps/turn-queue-ticks-at)

(defwhen "the weather sweep is started"
  isaac.agent.turn.queue-steps/weather-sweep-started
  "Starts the turn worker on a live shared scheduler so its tasks can be read.")

(defgiven #"a scripted resource pool \"([^\"]+)\" admits (\d+) turn at a time"
  isaac.agent.turn.queue-steps/register-admits-n-resource-pool)

(defgiven #"resource pool \"([^\"]+)\" is closed" isaac.agent.turn.queue-steps/close-resource-pool)

(defwhen #"resource pool \"([^\"]+)\" is opened" isaac.agent.turn.queue-steps/open-resource-pool)

(defwhen #"the user sends \"(.+)\" on session \"([^\"]+)\" with resource pools \"([^\"]+)\""
  isaac.agent.turn.queue-steps/user-sends-with-resource-pools)

(defgiven #"a scripted resource pool \"([^\"]+)\" binds:" isaac.agent.turn.queue-steps/scripted-pool-binds)
(defgiven #"resource pool \"([^\"]+)\" has lease \"([^\"]+)\" out" isaac.agent.turn.queue-steps/scripted-pool-has-lease)

;; isaac-r209: minimal new steps — submit a charge by frequencies alone
;; (crew), the same unresolved-until-claim shape isaac-hail uses, so the
;; turn queue's claim-time admission can be exercised from a feature.
(defwhen #"a turn with input \"([^\"]+)\" is submitted with frequencies:"
  isaac.agent.turn.queue-steps/turn-submitted-with-frequencies)

(defwhen #"a turn with input \"([^\"]+)\" is submitted to crew \"([^\"]+)\""
  isaac.agent.turn.queue-steps/turn-submitted-to-crew)

(defwhen #"a turn with input \"([^\"]+)\" is submitted to crew \"([^\"]+)\" with resource pools \"([^\"]+)\""
  isaac.agent.turn.queue-steps/turn-submitted-to-crew-with-pools)

(defwhen #"a turn with input \"([^\"]+)\" is submitted to crew \"([^\"]+)\" with create \"([^\"]+)\""
  isaac.agent.turn.queue-steps/turn-submitted-to-crew-with-create)

(defthen #"the hail submission failed with \"([^\"]+)\""
  isaac.agent.turn.queue-steps/hail-submission-failed)

(defthen "the hail submission did not wait"
  isaac.agent.turn.queue-steps/hail-submission-did-not-wait)

(defthen "within {n:int} seconds session {s:string} is waiting on the model"
  isaac.agent.turn.queue-steps/session-waiting-on-model
  "Polls isaac.agent.llm.api.grover/waiting? up to n seconds — true once that
   session's in-flight turn has dequeued a `wait`-tagged scripted response and
   is blocked in Grover's wait-gate (isaac-e9jl).")

(defwhen "the model releases session {s:string}"
  isaac.agent.turn.queue-steps/model-releases-session
  "isaac.agent.llm.api.grover/release-wait! for that session — unblocks the turn a
   prior 'is waiting on the model' step found parked there (isaac-e9jl).")
