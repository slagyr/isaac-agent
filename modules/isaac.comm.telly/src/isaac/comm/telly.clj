(ns isaac.comm.telly
  (:require
    [c3kit.apron.env :as c3env]
    [isaac.api :as api]
    [isaac.comm.factory :as factory]
    [isaac.comm.protocol :as comm]
    [isaac.logger :as log]))

(when (= "true" (c3env/env "ISAAC_TELLY_FAIL_ON_LOAD"))
  (throw (ex-info "telly load failed"
                  {:entry     'isaac.comm.telly
                   :module-id :isaac.comm.telly
                   :type      :module/activation-failed})))

(deftype Telly [host state])

(extend Telly
  comm/Comm
  (merge comm/defaults
         {:send! (fn [_ _] {:ok false :transient? false})})
  api/Reconfigurable
  {:on-load
   (fn [this slice]
     (log/info :telly/started
               :module (let [name (:name (.-host this))]
                         (if (keyword? name) (clojure.core/name name) (str name))))
     (reset! (.-state this) {:slice      slice
                             :started?   true
                             :host       (.-host this)
                             :last-event :started}))
   :on-config-change!
   (fn [this old-slice new-slice]
     (swap! (.-state this) assoc
            :slice new-slice
            :last-event :changed
            :prior old-slice))
   :on-unload
   (fn [this old-slice]
     (reset! (.-state this) {:slice      nil
                             :started?   false
                             :host       (.-host this)
                             :last-event :stopped
                             :prior      old-slice}))})

(defn make [host]
  (->Telly host (atom {})))

(defmethod factory/create :telly [node-path _slice]
  (make {:name (last node-path)}))

(defn telly? [x]
  (instance? Telly x))

(defn state [^Telly t]
  @(.-state t))
