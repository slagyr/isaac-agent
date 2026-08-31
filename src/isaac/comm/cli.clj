(ns isaac.comm.cli
  (:require
    [isaac.comm.protocol :as comm]
    [isaac.comm.render :as render]))

(deftype CliComm [])

(extend CliComm
  comm/Comm
  (merge comm/defaults
         {:on-chatter
          (fn [_ _ _ text]
            (print (render/chunk-text text))
            (flush))

          :on-tool-call
          (fn [_ _ tool-call]
            (println (str "  [tool call: " (:name tool-call) "]")))

          :on-turn-end
          (fn [_ _ _]
            (println))

          :send!
          (fn [_ _] {:ok false :transient? false})}))

(defn make [_host]
  (->CliComm))

(def channel (->CliComm))
