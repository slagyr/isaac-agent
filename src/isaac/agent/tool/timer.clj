(ns isaac.agent.tool.timer
  "Durable session-local delayed turn submissions."
  (:require
    [cheshire.core :as json]
    [isaac.agent.tool.fs-bounds :as bounds]
    [isaac.agent.tool.memory :as memory]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.turn.queue :as queue]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus])
  (:import (java.time Instant)))

(defonce ^:private locks* (atom {}))

(defn- session-lock [root session]
  (get (swap! locks* update [root session] #(or % (Object.))) [root session]))

(defn- pending [session]
  (->> (queue/list-held)
       (filter #(and (= :timer (get-in % [:origin :kind]))
                     (= session (:session %))))))

(defn- with-root [args f]
  (binding [queue/*root* (bounds/root args)]
    (nexus/-with-nested-nexus {:fs (bounds/filesystem args)}
      (f))))

(defn set-tool [raw-args]
  (let [args    (bounds/string-key-map raw-args)
        after   (bounds/arg-int args "after" nil)
        session (get args "session_key")
        root    (bounds/root args)]
    (cond
      (or (nil? after) (not (<= 60 after 3600)))
      {:isError true :error "after must be between 60 and 3600 seconds"}
      (not (string? (get args "note")))
      {:isError true :error "note must be a string"}
      (not session)
      {:isError true :error "session_key is required"}
      :else
      (with-root args
        #(locking (session-lock root session)
           (let [cfg   (loader/snapshot "timer set")
                 crew  (some-> (bounds/session-store args)
                               (store/get-session session) :crew)
                 limit (or (get-in cfg [:crew crew :max-timers])
                           (get-in cfg [:defaults :crew :max-timers]) 10)
                 count* (count (pending session))]
             (if (>= count* limit)
               {:isError true :error (str "timer limit reached (" count* " pending)")}
               (let [due  (str (.plusSeconds ^Instant (memory/now) after))
                     turn (queue/enqueue! {:session session :input (get args "note")
                                           :origin {:kind :timer} :due-at due :state :queued})]
                 {:result (json/generate-string {:handle (:id turn) :due due})}))))))))

(defn list-tool [raw-args]
  (let [args (bounds/string-key-map raw-args)]
    (with-root args
      #(let [session (get args "session_key")]
         {:result (json/generate-string
                    {:timers (mapv (fn [turn] {:handle (:id turn) :note (:input turn) :due (:due-at turn)})
                                   (pending session))})}))))

(defn cancel-tool [raw-args]
  (let [args    (bounds/string-key-map raw-args)
        session (get args "session_key")
        handle  (get args "handle")]
    (with-root args
      #(locking (session-lock (bounds/root args) session)
         (if (some (fn [turn] (= handle (:id turn))) (pending session))
           (do (queue/update-turn! handle {:state :finished :outcome :cancelled})
               {:result "Timer cancelled."})
           {:isError true :error "timer handle not found"})))))
