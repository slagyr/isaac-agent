(ns isaac.session.session-steps-spec
  (:require
    [gherclj.core :as g]
    [isaac.config.config-steps :as config-steps]
    [isaac.config.loader :as loader]
    [isaac.foundation.fs-steps :as ffs]
    [isaac.foundation.root-steps :as froot]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.nexus :as nexus]
    [isaac.session.session-steps :as sut]
    [isaac.session.store.sidecar :as sidecar-store]
    [speclj.core :refer [around describe it should should-be-nil should-not-be-nil should=]]))

(describe "session feature steps"

  #_{:clj-kondo/ignore [:invalid-arity]}
  (around [it]
    (let [create-store (var-get #'sidecar-store/create-store)]
      (g/reset!)
      (grover/reset-queue!)
      (nexus/reset!)
      (try
        (nexus/-with-nexus {:fs (fs/mem-fs)}
          (it))
        (finally
          (alter-var-root #'sidecar-store/create-store (constantly create-store))
          (grover/reset-queue!)
          (nexus/reset!)
          (g/reset!)))))

  (it "does not wait for a Grover gate after a turn already completed"
    (g/assoc! :turn-future (future {:output "done"
                                    :request {:id :request}
                                    :result  {:ok true}}))
    (let [started-at (System/nanoTime)]
      (sut/turn-ends-on-session "bridge")
      (should (< (/ (- (System/nanoTime) started-at) 1000000.0)
                 500.0)))
    (should= {:ok true} (g/get :llm-result)))

  (it "awaits an in-flight turn before reading prompt tools"
    (g/assoc! :turn-future (future {:output  ""
                                    :request {:tools [{:name "fs__read"}]}
                                    :result  {:ok true}}))
    (sut/prompt-has-tools {:headers ["name"]
                           :rows    [["fs__read"]]})
    (should= {:tools [{:name "fs__read"}]} (g/get :llm-request)))

  (it "reuses loaded config until a feature fixture changes it"
    (let [loads* (atom 0)
          cfg    {:defaults {:crew "main"}
                  :crew     {"main" {}}
                  :models   {}
                  :providers {}}]
      (g/assoc! :root "/target/test-state")
      (g/assoc! :mem-fs (nexus/get :fs))
      (with-redefs [loader/load-config-result (fn [_]
                                                (swap! loads* inc)
                                                {:config cfg})]
        (should= cfg (#'sut/loaded-config))
        (should= cfg (#'sut/loaded-config))
        (should= 1 @loads*)
        (ffs/file-exists-with "config/crew/main.edn" "{:model :grover}")
        (should= cfg (#'sut/loaded-config))
        (should= 2 @loads*))))

  (it "parses a tools.allow EDN vector as keywords, not a comma-split of the brackets"
    (should= [:exec/run] (#'ffs/parse-isaac-value "config/crew/main.edn" "tools.allow" "[:exec/run]"))
    (should= [:memory/*] (#'ffs/parse-isaac-value "config/crew/main.edn" "tools.allow" "[:memory/*]")))

  (it "matches a validation error value against a #\"...\" table cell"
    (should (#'config-steps/row-matches?
              {:key "tools.allow" :value ":all is the list, not a list item — use :allow :all, never [:all]"}
              {"key" "tools.allow" "value" "#\":all\""})))

  (it "enriches an Isaac root fixture with tools.max-parallel 4"
    (froot/in-memory-state "target/test-state")
    (should= 4 (get-in (#'sut/loaded-config) [:tools :max-parallel])))

  (it "parks a waiting send only after the turn has started"
    (sut/default-grover-setup)
    (sut/sessions-exist {:headers ["name"] :rows [["longwave"]]})
    (sut/responses-queued {:headers ["type" "content" "model" "wait"]
                           :rows    [["text" "ok" "echo" "true"]]})
    (sut/user-sends-on-session "check the beacon" "longwave")
    (should-not-be-nil (g/get :turn-future))
    (should (some #(= "turn-start" (:event %)) @(g/get :channel-events)))
    (sut/turn-ends-on-session "longwave"))

  (it "arms mid-loop cancellation when no turn is in flight yet"
    (sut/turn-cancelled-after-n-tool-calls "cancel" 1)
    (should= {:session "cancel" :n 1} (g/get :cancel-after-n-tool-calls)))
 
  (it "records each immediate send so later turns can compare chat requests"
    (sut/default-grover-setup)
    (sut/sessions-exist {:headers ["name"] :rows [["greenhouse"]]})
    (sut/responses-queued {:headers ["type" "content" "model"]
                           :rows    [["text" "Nominal." "echo"]
                                     ["text" "Still." "echo"]]})
    (sut/user-sends-on-session "Status?" "greenhouse")
    (sut/user-sends-on-session "And now?" "greenhouse")
    (sut/await-turn!)
    (should= 2 (count (get (g/get :chat-requests-by-session) "greenhouse"))))

  (it "materializes grover as a configured provider on a seeded Isaac root"
    (froot/in-memory-state "target/test-state")
    (sut/ensure-grover-provider-files!)
    (let [cfg (#'sut/loaded-config)]
      (should= "echo" (get-in cfg [:models "grover" :model]))
      (should (contains? (or (:providers cfg) {}) "grover"))))

  (it "finishes a fast send so later steps can read the turn result without an extra await"
    (sut/default-grover-setup)
    (sut/sessions-exist {:headers ["name"] :rows [["trash-can"]]})
    (sut/responses-queued {:headers ["type" "status" "message"]
                           :rows    [["http-error" "400" "not supported"]]})
    (sut/user-sends-on-session "knock knock" "trash-can")
    (should-be-nil (g/get :turn-future))
    (should= :api-error (:error (g/get :llm-result))))
  )
