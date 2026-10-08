(ns isaac.agent.tool.session-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.foundation.marigold :as marigold]
    [isaac.agent.spec-helper :as helper]
    [isaac.agent.session.spec-helper :as store-helper]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.tool.session :as sut]
    [isaac.agent.tool.support :as support]
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

  (let [base-cfg {:defaults  {:frequencies {:crew crew-name} :crew {:model crew-model}}
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
        (let [cfg    {:defaults  {:frequencies {:crew crew-name} :crew {:model crew-model}}
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
        (store-helper/update-session! support/test-dir "sm-switch" {:compaction {:consecutive-failures 5}})
        (let [result (helper/with-config base-cfg
                       (sut/session-model-tool {"session_key" "sm-switch" "model" "parrot"}))
              data   (json/parse-string (:result result) true)]
          (should= "parrot" (get-in data [:model :alias]))
          (should= "squawk" (get-in data [:model :upstream]))
          (should= "parrot" (:model (store-helper/get-session support/test-dir "sm-switch")))
          (should= 5 (get-in (store-helper/get-session support/test-dir "sm-switch") [:compaction :consecutive-failures]))))

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

(describe "Session history"
  (around [example]
    (store-helper/with-memory-store
      (nexus/-with-nested-nexus {:root support/test-dir}
        (example))))

  (it "only lists the caller's crew messages since Tuesday"
    (store-helper/create-session! support/test-dir "ours" {:crew "main"})
    (store-helper/create-session! support/test-dir "theirs" {:crew "other"})
    (store-helper/append-message! support/test-dir "ours" {:role "user" :content "hello"})
    (store-helper/append-message! support/test-dir "theirs" {:role "user" :content "secret"})
    (let [result (sut/session-list-tool {"session_key" "ours" "since" "2000-01-01T00:00:00Z"})]
      (should-contain "ours 1 messages" (:result result))
      (should-not-contain "theirs" (:result result))))

  (it "does not reveal another crew's session on read"
    (store-helper/create-session! support/test-dir "ours" {:crew "main"})
    (store-helper/create-session! support/test-dir "theirs" {:crew "other"})
    (should= {:isError true :error "no session theirs"}
             (sut/session-read-tool {"session_key" "ours" "session" "theirs" "since" "2000-01-01T00:00:00Z"})))

  (it "pages whole messages, without exposing tool results"
    (store-helper/create-session! support/test-dir "ours" {:crew "main"})
    (store-helper/append-message! support/test-dir "ours" {:role "user" :content "one"})
    (store-helper/append-message! support/test-dir "ours" {:role "toolResult" :content "secret"})
    (store-helper/append-message! support/test-dir "ours" {:role "assistant" :content "two"})
    (let [args {"session_key" "ours" "session" "ours" "since" "2000-01-01T00:00:00Z"}
          result (sut/session-read-tool (assoc args "max_lines" 1))]
      (should-contain "user: one" (:result result))
      (should-contain "1 of 2 messages; next offset 1" (:result result))
      (should-not-contain "secret" (:result result))
      (should-contain "assistant: two" (:result (sut/session-read-tool (assoc args "offset" 1)))))))
