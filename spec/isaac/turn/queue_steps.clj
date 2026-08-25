(ns isaac.turn.queue-steps
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen helper!]]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.session.session-steps :as session-steps]
    [isaac.tool.memory :as memory]
    [isaac.turn.queue :as queue]
    [isaac.turn.worker :as worker]
    [isaac.turnstile :as turnstile])
  (:import
    (java.time Instant)))

(helper! isaac.turn.queue-steps)

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
  (if-let [held-id (g/get :held-id)]
    (str/replace (str s) "#held-id" held-id)
    s))

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
      (when-let [[_ id] (re-find #"held:\s+([a-z0-9-]+)" output)]
        (g/assoc! :held-id id)))))

(fcli/register-isaac-run-preflight!
  (fn []
    (when-let [held-id (g/get :held-id)]
      (g/assoc! :held-id held-id))))

(defn- ensure-wake-hook! []
  (turnstile/set-wake-hook! worker/tick!))

(defn turn-queue-ticks-at [iso]
  (ensure-wake-hook!)
  (let [now (parse-iso iso)]
    (g/assoc! :current-time now)
    (with-feature-fs
      (fn []
        (binding [memory/*now* now
                  queue/*root* (root-dir)]
          (nexus/-with-nested-nexus {:root (root-dir) :fs (mem-fs)}
            (worker/tick! {:now now})))))))

(defn- scripted-gate [name n]
  (let [state (or (get @scripted-gates* name)
                  (let [fresh {:open?     (atom true)
                               :inflight  (atom 0)
                               :limit     n}]
                    (swap! scripted-gates* assoc name fresh)
                    fresh))]
    (swap! scripted-gates* assoc-in [name :limit] n)
    (reify turnstile/Turnstile
      (admit? [_ _ctx]
        (if (false? @(:open? state))
          {:status :hold :message (str name " closed")}
          (if (< @(:inflight state) (:limit state))
            (do (swap! (:inflight state) inc) :pass)
            {:status :hold :message (str name " full")})))
      (release! [_ _token]
        (swap! (:inflight state) #(max 0 (dec %)))))))

(defn register-admits-n-turnstile [name n]
  (let [n (if (string? n) (parse-long n) n)]
    (turnstile/register! (keyword name) (fn [_] (scripted-gate name n)))))

(defn close-turnstile [name]
  (let [state (or (get @scripted-gates* name)
                  (let [fresh {:open?     (atom false)
                               :inflight  (atom 0)
                               :limit     1}]
                    (swap! scripted-gates* assoc name fresh)
                    fresh))]
    (reset! (:open? state) false)))

(defn open-turnstile [name]
  (ensure-wake-hook!)
  (when-let [state (get @scripted-gates* name)]
    (reset! (:open? state) true)
    (turnstile/release-all! [{:turnstile (scripted-gate name (:limit state))
                              :token     (turnstile/->ReleaseToken "open")}])))

(defn user-sends-with-turnstiles [content key-str turnstiles]
  (ensure-wake-hook!)
  (session-steps/user-sends-on-session
    content key-str (mapv turnstile/parse-ref (str/split turnstiles #",\s*"))))

(g/after-scenario
  (fn []
    (reset! scripted-gates* {})
    (g/dissoc! :held-id)
    (turnstile/set-wake-hook! nil)))

(defwhen #"the turn queue ticks at \"([^\"]+)\"" isaac.turn.queue-steps/turn-queue-ticks-at)

(defgiven #"a turnstile \"([^\"]+)\" is registered that admits (\d+) at a time"
  isaac.turn.queue-steps/register-admits-n-turnstile)

(defgiven #"turnstile \"([^\"]+)\" is closed" isaac.turn.queue-steps/close-turnstile)

(defwhen #"turnstile \"([^\"]+)\" is opened" isaac.turn.queue-steps/open-turnstile)

(defwhen #"the user sends \"(.+)\" on session \"([^\"]+)\" with turnstiles \"([^\"]+)\""
  isaac.turn.queue-steps/user-sends-with-turnstiles)
