(ns isaac.tool.session-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.marigold :as marigold]
    [isaac.spec-helper :as helper]
    [isaac.session.spec-helper :as store-helper]
    [isaac.nexus :as nexus]
    [isaac.tool.session :as sut]
    [isaac.tool.support :as support]
    [speclj.core :refer :all]))

(def ^:private crew-name marigold/captain)
(def ^:private crew-model "grover")

(describe "Session tools"
  (before (support/clean!))

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (store-helper/with-memory-store
      (nexus/-with-nested-nexus {:root support/test-dir}
        (example))))

  (let [base-cfg {:defaults  {:crew crew-name :model crew-model}
                  :crew      {crew-name {:model :grover :soul (:soul (marigold/crew-cfg crew-name))}}
                  :models    {crew-model {:model "echo" :provider :grover :context-window 32768}
                              "parrot" {:model "squawk" :provider :grover :context-window 16384}}
                  :providers {}}]

    (describe "session_info"

      (it "returns current session state with snake_case keys"
        (store-helper/create-session! support/test-dir "si-basic" {:crew crew-name :cwd support/test-dir})
        (store-helper/update-session! support/test-dir "si-basic" {:createdAt "2026-04-27T10:00:00" :updated-at "2026-04-27T10:00:00"})
        (let [result (helper/with-config base-cfg
                       (sut/session-info-tool {"session_key" "si-basic"}))
              data   (json/parse-string (:result result) true)]
          (should= crew-name (:crew data))
          (should= crew-model (get-in data [:model :alias]))
          (should= "echo" (get-in data [:model :upstream]))
          (should= "grover" (:provider data))
          (should= "si-basic" (:session data))
          (should= 0 (:compactions data))
          (should= 0 (get-in data [:context :used]))
          (should= 32768 (get-in data [:context :window]))
          (should= "2026-04-27T10:00:00Z" (:created_at data))))

      (it "resolves alias and provider when the session stores the upstream model name"
        (store-helper/create-session! support/test-dir "si-upstream" {:crew crew-name :cwd support/test-dir})
        (store-helper/update-session! support/test-dir "si-upstream" {:model "lettuce-grande"})
        (let [cfg    {:defaults  {:crew crew-name :model crew-model}
                      :crew      {crew-name {:model :grover :soul (:soul (marigold/crew-cfg crew-name))}}
                      :models    {crew-model {:model "echo" :provider :grover :context-window 32768}
                                  "lettuce" {:model "lettuce-grande" :provider :hieronymus :context-window 128000}}
                      :providers {"hieronymus" {:api "grover" :auth "none"}}}
              result (helper/with-config cfg
                       (sut/session-info-tool {"session_key" "si-upstream"}))
              data   (json/parse-string (:result result) true)]
          (should= "lettuce" (get-in data [:model :alias]))
          (should= "lettuce-grande" (get-in data [:model :upstream]))
          (should= "hieronymus" (:provider data))
          (should= 128000 (get-in data [:context :window])))))

    (describe "session_model"

      (it "switches model when model arg is provided"
        (store-helper/create-session! support/test-dir "sm-switch" {:crew crew-name :cwd support/test-dir})
        (store-helper/update-session! support/test-dir "sm-switch" {:compaction-disabled true
                                                                :compaction {:consecutive-failures 5}})
        (let [result (helper/with-config base-cfg
                       (sut/session-model-tool {"session_key" "sm-switch" "model" "parrot"}))
              data   (json/parse-string (:result result) true)]
          (should= "parrot" (get-in data [:model :alias]))
          (should= "squawk" (get-in data [:model :upstream]))
          (should= "parrot" (:model (store-helper/get-session support/test-dir "sm-switch")))
          (should= false (:compaction-disabled (store-helper/get-session support/test-dir "sm-switch")))
          (should= 0 (get-in (store-helper/get-session support/test-dir "sm-switch") [:compaction :consecutive-failures]))))

      (it "resets model to crew default when reset is true"
        (store-helper/create-session! support/test-dir "sm-reset" {:crew crew-name :cwd support/test-dir})
        (store-helper/update-session! support/test-dir "sm-reset" {:model "parrot"})
        (let [result (helper/with-config base-cfg
                       (sut/session-model-tool {"session_key" "sm-reset" "reset" true}))
              data   (json/parse-string (:result result) true)]
          (should= crew-model (get-in data [:model :alias]))
          (should= crew-model (:model (store-helper/get-session support/test-dir "sm-reset")))))

      (it "errors when both model and reset are provided"
        (store-helper/create-session! support/test-dir "sm-both" {:crew crew-name :cwd support/test-dir})
        (let [result (sut/session-model-tool {"session_key" "sm-both" "model" crew-model "reset" true})]
          (should (:isError result))
          (should (str/includes? (:error result) "mutually exclusive"))))

      (it "errors when model alias does not exist"
        (store-helper/create-session! support/test-dir "sm-nomodel" {:crew crew-name :cwd support/test-dir})
        (let [result (helper/with-config base-cfg
                       (sut/session-model-tool {"session_key" "sm-nomodel" "model" "nonexistent"}))]
          (should (:isError result))
          (should (str/includes? (:error result) "unknown model: nonexistent")))))))
