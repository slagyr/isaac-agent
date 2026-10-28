(ns isaac.agent.drive.turn-compaction-spec
  (:require
    [isaac.agent.drive.turn-compaction :as sut]
    [isaac.agent.llm.api.protocol :as api]
    [isaac.agent.session.compaction :as compaction]
    [isaac.foundation.logger :as log]
    [isaac.foundation.marigold :as marigold]
    [speclj.core :refer [describe it should= should-not-be-nil]]))

(deftype StoppingProvider [name cfg]
  api/Api
  (chat [_ _] nil)
  (chat-stream [_ _ _] nil)
  (followup-messages [_ _ _ _ _] nil)
  (config [_] cfg)
  (display-name [_] name)
  (format-tools [_ tools] tools)
  (build-prompt [_ opts] opts))

(describe "perform-compaction!"
  (it "stops once the attempt limit is exceeded and does not compact"
    (let [provider (->StoppingProvider marigold/starcore {:api marigold/sky-api})]
      (with-redefs [compaction/compact! (fn [& _] (throw (ex-info "should not compact" {})))]
        (log/capture-logs
          (sut/perform-compaction! "attempt-limit" 4 1200 {:context-window 1000
                                                           :model "test-model"
                                                           :provider provider
                                                           :soul "You are Isaac."})
          (let [entry (first (filter #(= :session/compaction-stopped (:event %)) @log/captured-logs))]
            (should-not-be-nil entry)
            (should= :max-attempts (:reason entry))
            (should= 4 (:attempt entry))))))))
