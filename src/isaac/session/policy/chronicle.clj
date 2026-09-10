(ns isaac.session.policy.chronicle
  "Chronicle policy: one container per session. Today's SessionStore
   behaviour, moved behind SessionPolicy."
  (:require
    [isaac.session.policy :as policy]
    [isaac.session.store.spi :as store]))

(deftype ChroniclePolicy [store]
  policy/SessionPolicy
  (open-session! [_ name opts] (store/open-session! store name opts))
  (delete-session! [_ name] (store/delete-session! store name))
  (rename-session! [_ old-name new-name] (store/rename-session! store old-name new-name))
  (list-sessions [_] (store/list-sessions store))
  (list-sessions-by-agent [_ agent] (store/list-sessions-by-agent store agent))
  (most-recent-session [_] (store/most-recent-session store))
  (get-session [_ name] (store/get-session store name))
  (get-transcript [_ name] (store/get-transcript store name))
  (active-transcript [_ name] (store/active-transcript store name))
  (chronicle-transcript [_ name] (store/chronicle-transcript store name))
  (update-session! [_ name updates] (store/update-session! store name updates))
  (append-message! [_ name message] (store/append-message! store name message))
  (append-error! [_ name error] (store/append-error! store name error))
  (append-compaction! [_ name compaction] (store/append-compaction! store name compaction))
  (append-reckoning! [_ name reckoning] (store/append-reckoning! store name reckoning))
  (splice-compaction! [_ name compaction] (store/splice-compaction! store name compaction))
  (truncate-after-compaction! [_ name] (store/truncate-after-compaction! store name))
  (record-turn-marker! [_ session-id marker] (store/record-turn-marker! store session-id marker))
  (clear-turn-marker! [_ session-id] (store/clear-turn-marker! store session-id))
  (get-turn-marker [_ session-id] (store/get-turn-marker store session-id))
  (turn-markers [_] (store/turn-markers store))
  (default-session [_ crew _opts]
    (let [crew-id (if (keyword? crew) (name crew) (str crew))
          recent  (or (when crew-id
                        (last (sort-by :updated-at (store/list-sessions-by-agent store crew-id))))
                      (store/most-recent-session store))]
      (or (:id recent) nil)))
  (repair-transcript! [_ session-id] (store/repair-transcript! store session-id))
  (request-cancel! [_ session-id] (store/request-cancel! store session-id)))

(defn create [store]
  (->ChroniclePolicy store))

(policy/register-factory! :chronicle #'create)
