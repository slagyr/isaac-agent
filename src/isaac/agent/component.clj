(ns isaac.agent.component
  "Foundation components owned by the agent module."
  (:require
    [isaac.bridge.resume :as resume]
    [isaac.bridge.suspend :as suspend]
    [isaac.comm.delivery.worker :as delivery]
    [isaac.component.factory :as component-factory]
    [isaac.component.protocol :as component]
    [isaac.episodes.worker :as episodes]
    [isaac.nexus :as nexus]
    [isaac.session.store.spi :as store]
    [isaac.turn.worker :as turn]))

(def ^:private default-suspend-timeout-ms 15000)

(defn- background-services? [opts]
  (and (nexus/get :scheduler)
       (not (false? (:start-background-services? opts)))))

(defrecord WorkerComponent [start! stop! opts handle]
  component/Component
  (start [this]
    (when (background-services? opts)
      (reset! handle (start! {})))
    this)
  (stop [this]
    (when-let [running @handle]
      (stop! running)
      (reset! handle nil))
    this))

(defrecord AgentLifecycle [config root session-store]
  component/Component
  (start [this]
    (when root
      (let [registered (or (store/registered-store)
                           (store/register! config root))]
        (reset! session-store registered)
        (resume/resume-interrupted-turns! {:session-store registered
                                           :root          root
                                           :cfg           config})))
    this)
  (stop [this]
    (when-let [registered @session-store]
      (suspend/suspend! {:session-store registered
                         :timeout-ms    (or (get-in config [:server :suspend-timeout-ms])
                                            default-suspend-timeout-ms)})
      (reset! session-store nil))
    this))

(defn- worker-component [start! stop! opts]
  (->WorkerComponent start! stop! opts (atom nil)))

(defmethod component-factory/create :agent-lifecycle
  [_ {:keys [config root]}]
  (->AgentLifecycle config root (atom nil)))

(defmethod component-factory/create :comm-delivery
  [_ {:keys [opts]}]
  (worker-component delivery/start! delivery/stop! opts))

(defmethod component-factory/create :episodes-worker
  [_ {:keys [opts]}]
  (worker-component episodes/start! episodes/stop! opts))

(defmethod component-factory/create :turn-queue
  [_ {:keys [opts]}]
  (worker-component turn/start! turn/stop! opts))
