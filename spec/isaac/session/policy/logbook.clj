(ns isaac.session.policy.logbook
  "Recording SessionPolicy decorator over chronicle. Spec-support fixture
   for features/session/session_policy.feature."
  (:require
    [isaac.session.policy :as policy]
    [isaac.session.policy.chronicle :as chronicle]))

(defonce ^:private calls* (atom []))

(defn recorded-calls []
  @calls*)

(defn reset-calls! []
  (reset! calls* []))

(def ^:private recorded-methods
  #{"open-session!" "record-turn-marker!" "append-message!" "clear-turn-marker!"
    "splice-compaction!" "default-session" "repair-transcript!" "get-transcript"})

(defn- record! [method session-id crew]
  (when (contains? recorded-methods (name method))
    (swap! calls* conj (cond-> {:method (name method)}
                         session-id (assoc :session-id session-id)
                         crew (assoc :crew crew)))))

(deftype LogbookPolicy [inner]
  policy/SessionPolicy
  (open-session! [_ name opts]
    (record! :open-session! name (:crew opts))
    (policy/open-session! inner name opts))
  (delete-session! [_ name]
    (record! :delete-session! name nil)
    (policy/delete-session! inner name))
  (rename-session! [_ old-name new-name]
    (record! :rename-session! old-name nil)
    (policy/rename-session! inner old-name new-name))
  (list-sessions [_]
    (record! :list-sessions nil nil)
    (policy/list-sessions inner))
  (list-sessions-by-agent [_ agent]
    (record! :list-sessions-by-agent nil agent)
    (policy/list-sessions-by-agent inner agent))
  (most-recent-session [_]
    (record! :most-recent-session nil nil)
    (policy/most-recent-session inner))
  (get-session [_ name]
    (record! :get-session name nil)
    (policy/get-session inner name))
  (get-transcript [_ name]
    (record! :get-transcript name nil)
    (policy/get-transcript inner name))
  (active-transcript [_ name]
    (record! :active-transcript name nil)
    (policy/active-transcript inner name))
  (chronicle-transcript [_ name]
    (record! :chronicle-transcript name nil)
    (policy/chronicle-transcript inner name))
  (update-session! [_ name updates]
    (record! :update-session! name nil)
    (policy/update-session! inner name updates))
  (append-message! [_ name message]
    (record! :append-message! name nil)
    (policy/append-message! inner name message))
  (append-error! [_ name error]
    (record! :append-error! name nil)
    (policy/append-error! inner name error))
  (append-compaction! [_ name compaction]
    (record! :append-compaction! name nil)
    (policy/append-compaction! inner name compaction))
  (append-reckoning! [_ name reckoning]
    (record! :append-reckoning! name nil)
    (policy/append-reckoning! inner name reckoning))
  (splice-compaction! [_ name compaction]
    (record! :splice-compaction! name nil)
    (policy/splice-compaction! inner name compaction))
  (truncate-after-compaction! [_ name]
    (record! :truncate-after-compaction! name nil)
    (policy/truncate-after-compaction! inner name))
  (record-turn-marker! [_ session-id marker]
    (record! :record-turn-marker! session-id nil)
    (policy/record-turn-marker! inner session-id marker))
  (clear-turn-marker! [_ session-id]
    (record! :clear-turn-marker! session-id nil)
    (policy/clear-turn-marker! inner session-id))
  (get-turn-marker [_ session-id]
    (record! :get-turn-marker session-id nil)
    (policy/get-turn-marker inner session-id))
  (turn-markers [_]
    (record! :turn-markers nil nil)
    (policy/turn-markers inner))
  (default-session [_ crew opts]
    (record! :default-session nil crew)
    (policy/default-session inner crew opts))
  (repair-transcript! [_ session-id]
    (record! :repair-transcript! session-id nil)
    (policy/repair-transcript! inner session-id)))

(defn create [store]
  (->LogbookPolicy (chronicle/create store)))
