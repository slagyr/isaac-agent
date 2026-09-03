(ns isaac.conversation.router
  "Turns a surface-owned conversation reference into the bridge's internal
   session target. Surface adapters never choose a chronicle or episode."
  )

(defprotocol ConversationRouter
  (route-conversation [this request]
    "Return request with the internal :session-key selected from :conversation."))

(defmulti route-by-mode
  (fn [{:keys [crew-cfg conversation]}]
    [(:conversation crew-cfg) (:kind conversation)]))

(defmethod route-by-mode [:episodes :thread]
  [{:keys [conversation] :as request}]
  ;; The bridge lifecycle opens/reuses the backing episode after this route.
  ;; Keeping the stable thread separate until here prevents adapters from
  ;; constructing a chronicle merely to name the conversation.
  (assoc request :session-key (:id conversation)))

(defmethod route-by-mode [:chronicles :thread]
  [{:keys [conversation] :as request}]
  (assoc request :session-key (:id conversation)))

(defmethod route-by-mode :default [request]
  request)

(defrecord DefaultConversationRouter []
  ConversationRouter
  (route-conversation [_ request]
    (if (:conversation request)
      (route-by-mode request)
      request)))

(def default-router (->DefaultConversationRouter))

(defn route-conversation!
  "Public bridge entry for an explicit conversation reference."
  [request]
  (route-conversation default-router request))
