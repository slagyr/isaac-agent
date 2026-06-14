(ns isaac.llm.auth.cli-spec
  (:require
    [clojure.string]
    [isaac.fs :as fs]
    [isaac.llm.auth.device-code :as device-code]
    [isaac.llm.auth.store :as auth-store]
    [isaac.llm.auth.cli :as sut]
    [isaac.cli.registry :as registry]
    [isaac.config.loader :as loader]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "CLI Auth"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (binding [*out* (java.io.StringWriter.)]
        (example))))

  (describe "run"

    (it "prints help and returns 0 with no subcommand"
      (should= 0 (sut/run [])))

    (it "prints help and returns 0 with --help"
      (should= 0 (sut/run ["--help"])))

    (it "returns 1 for unknown subcommand"
      (should= 1 (sut/run ["unknown-sub"])))

    (describe "login"

      (it "prints login usage and returns 0 with login --help"
        (let [output (with-out-str (should= 0 (sut/run ["login" "--help"])))]
          (should (clojure.string/includes? output "Usage: isaac auth login"))))

      (it "prints parse errors and returns 1 for login option errors"
        (with-redefs [isaac.llm.auth.cli/parse-option-map (fn [_ _ & _] {:options {} :errors ["bad login arg"]})]
          (let [output (with-out-str (should= 1 (sut/run ["login" "--bogus"]))) ]
            (should (clojure.string/includes? output "bad login arg")))))

      (it "delegates to login with parsed options"
        (let [captured (atom nil)]
          (with-redefs [isaac.llm.auth.cli/parse-option-map (fn [_ _ & _] {:options {:provider "anthropic" :api-key true} :errors []})
                        isaac.llm.auth.cli/login (fn [opts]
                                               (reset! captured opts)
                                               0)]
            (should= 0 (sut/run ["login" "--provider" "anthropic" "--api-key"]))
            (should= {:provider "anthropic" :api-key true} @captured))))

      (it "returns 1 when --provider is missing"
        (should= 1 (sut/run ["login"])))

      (it "returns 1 for unknown provider"
        (should= 1 (sut/run ["login" "--provider" "unknown-provider"])))

      (it "returns 1 when --api-key not specified"
        (should= 1 (sut/run ["login" "--provider" "anthropic"])))

      (describe "chatgpt device code flow"

        (it "accepts chatgpt as a known provider"
          (let [output (atom nil)]
            (with-redefs [device-code/request-user-code! (fn [] {:error :api-error :status 404})]
              (binding [*out* (java.io.StringWriter.)]
                (sut/run ["login" "--provider" "chatgpt"])
                (reset! output (str *out*))))
            (should-not-contain "Unknown provider" @output)))

        (it "initiates device code flow without --api-key"
          (let [steps (atom [])]
            (with-redefs [device-code/request-user-code! (fn []
                                                           (swap! steps conj :request-code)
                                                           {:device_auth_id "dauth-1"
                                                            :user_code      "TEST-CODE"
                                                            :interval       5})
                          device-code/poll-for-auth!      (fn [_ _ _]
                                                           (swap! steps conj :poll)
                                                           {:authorization_code "auth-xyz"
                                                            :code_verifier     "verify-abc"})
                          device-code/exchange-tokens!    (fn [_ _]
                                                            (swap! steps conj :exchange)
                                                            {:access_token  "at-ok"
                                                             :refresh_token "rt-ok"
                                                             :id_token      "id-ok"
                                                             :expires_in    3600})
                          auth-store/save-tokens!         (fn [_ _ _ _]
                                                            (swap! steps conj :save))]
              (should= 0 (sut/run ["login" "--provider" "chatgpt"]))
              (should= [:request-code :poll :exchange :save] @steps))))

        (it "handles string interval from API response"
          (let [poll-interval (atom nil)]
            (with-redefs [device-code/request-user-code! (fn []
                                                           {:device_auth_id "dauth-1"
                                                            :user_code      "TEST-CODE"
                                                            :interval       "5"})
                          device-code/poll-for-auth!      (fn [_ _ interval-ms]
                                                           (reset! poll-interval interval-ms)
                                                           {:authorization_code "ac"
                                                            :code_verifier     "cv"})
                          device-code/exchange-tokens!    (fn [_ _]
                                                            {:access_token  "at"
                                                             :refresh_token "rt"
                                                             :id_token      "id"
                                                             :expires_in    3600})
                          auth-store/save-tokens!         (fn [_ _ _ _] nil)]
              (should= 0 (sut/run ["login" "--provider" "chatgpt"]))
              (should= 5000 @poll-interval))))

        (it "returns 1 when request-user-code fails"
          (with-redefs [device-code/request-user-code! (fn [] {:error :api-error :status 404})]
            (should= 1 (sut/run ["login" "--provider" "chatgpt"]))))

        (it "returns 1 when poll-for-auth fails"
          (with-redefs [device-code/request-user-code! (fn []
                                                         {:device_auth_id "d"
                                                          :user_code      "C"
                                                          :interval       5})
                        device-code/poll-for-auth!      (fn [_ _ _]
                                                         {:error :timeout})]
            (should= 1 (sut/run ["login" "--provider" "chatgpt"]))))

        (it "returns 1 when token exchange fails"
          (with-redefs [device-code/request-user-code! (fn []
                                                         {:device_auth_id "d"
                                                          :user_code      "C"
                                                          :interval       5})
                        device-code/poll-for-auth!      (fn [_ _ _]
                                                         {:authorization_code "ac"
                                                          :code_verifier     "cv"})
                        device-code/exchange-tokens!    (fn [_ _]
                                                          {:error :api-error})]
            (should= 1 (sut/run ["login" "--provider" "chatgpt"]))))

        ))

    (describe "status"

      (it "prints status usage and returns 0 with status --help"
        (let [output (with-out-str (should= 0 (sut/run ["status" "--help"])))]
          (should (clojure.string/includes? output "Usage: isaac auth status"))))

      (it "prints parse errors and returns 1 for status option errors"
        (with-redefs [isaac.llm.auth.cli/parse-option-map (fn [_ _ & _] {:options {} :errors ["bad status arg"]})]
          (let [output (with-out-str (should= 1 (sut/run ["status" "--bogus"]))) ]
            (should (clojure.string/includes? output "bad status arg")))))

      (it "delegates to status with parsed options"
        (let [captured (atom nil)]
          (with-redefs [isaac.llm.auth.cli/parse-option-map (fn [_ _ & _] {:options {:help false} :errors []})
                        isaac.llm.auth.cli/status (fn [opts]
                                                (reset! captured opts)
                                                0)]
            (should= 0 (sut/run ["status"]))
            (should= {:help false} @captured))))

      (it "returns 0"
        (with-redefs [loader/load-config! (fn [& _] {:providers {"ollama" {}}})]
          (should= 0 (sut/run ["status"]))))

      (it "reports anthropic not authenticated"
        (with-redefs [loader/load-config! (fn [& _] {:providers {"anthropic" {}}})]
          (should= 0 (sut/run ["status"]))))

      (it "reports anthropic api-key auth"
        (with-redefs [loader/load-config! (fn [& _] {:providers {"anthropic" {:api-key "sk-123"}}})]
          (should= 0 (sut/run ["status"])))))

    (describe "logout"

      (it "prints logout usage and returns 0 with logout --help"
        (let [output (with-out-str (should= 0 (sut/run ["logout" "--help"])))]
          (should (clojure.string/includes? output "Usage: isaac auth logout"))))

      (it "prints parse errors and returns 1 for logout option errors"
        (with-redefs [isaac.llm.auth.cli/parse-option-map (fn [_ _ & _] {:options {} :errors ["bad logout arg"]})]
          (let [output (with-out-str (should= 1 (sut/run ["logout" "--bogus"]))) ]
            (should (clojure.string/includes? output "bad logout arg")))))

      (it "delegates to logout with parsed options"
        (let [captured (atom nil)]
          (with-redefs [isaac.llm.auth.cli/parse-option-map (fn [_ _ & _] {:options {:provider "anthropic"} :errors []})
                        isaac.llm.auth.cli/logout (fn [opts]
                                                (reset! captured opts)
                                                0)]
            (should= 0 (sut/run ["logout" "--provider" "anthropic"]))
            (should= {:provider "anthropic"} @captured))))

      (it "returns 1 when --provider is missing"
        (should= 1 (sut/run ["logout"])))

      (it "returns 0 for valid provider"
        (should= 0 (sut/run ["logout" "--provider" "anthropic"])))))

  (describe "option parsing"

    (it "dispatches login with parsed --provider requiring --api-key"
      (should= 1 (sut/run ["login" "--provider" "anthropic"])))

    (it "dispatches login with --api-key flag"
      (let [saved (atom nil)]
        (with-redefs [read-line                (fn [] "sk-test-key-123")
                      loader/load-config!        (fn [& _] {:root "target/test-auth"})
                      auth-store/save-api-key! (fn [dir provider key _fs]
                                                 (reset! saved [dir provider key]))]
          (should= 0 (sut/run ["login" "--provider" "anthropic" "--api-key"]))
          (should= ["target/test-auth" "anthropic" "sk-test-key-123"] @saved)))))

  (describe "registry integration"

    (it "registers 'auth' command"
      (module-loader/process-manifest-berths! (module-loader/builtin-index))
      (should-not-be-nil (registry/get-command "auth")))

    (it "registered run-fn delegates to auth/run"
      (module-loader/process-manifest-berths! (module-loader/builtin-index))
      (let [cmd (registry/get-command "auth")]
        (should= 0 ((:run-fn cmd) {:_raw-args ["--help"]}))))))
