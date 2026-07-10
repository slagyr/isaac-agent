(ns isaac.llm.auth.cli-spec
  (:require
    [clojure.string :as str]
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

      (describe "oauth device-code flow"

        (it "accepts chatgpt as a known provider"
          (let [output (atom nil)]
            (with-redefs [device-code/request-user-code! (fn [_] {:error :api-error :status 404})]
              (binding [*out* (java.io.StringWriter.)]
                (sut/run ["login" "--provider" "chatgpt"])
                (reset! output (str *out*))))
            (should-not-contain "Unknown provider" @output)))

        (it "accepts grok as a known provider"
          (let [output (atom nil)]
            (with-redefs [device-code/request-user-code! (fn [_] {:error :api-error :status 404})]
              (binding [*out* (java.io.StringWriter.)]
                (sut/run ["login" "--provider" "grok"])
                (reset! output (str *out*))))
            (should-not-contain "Unknown provider" @output)))

        (it "initiates device code flow without --api-key"
          (let [steps      (atom [])
                descriptor {:verification-url "https://auth.openai.com/codex/device"}]
            (with-redefs [device-code/provider-descriptor! (fn [_] descriptor)
                          device-code/request-user-code! (fn [d]
                                                           (should= descriptor d)
                                                           (swap! steps conj :request-code)
                                                           {:device_auth_id "dauth-1"
                                                            :user_code      "TEST-CODE"
                                                            :interval       5})
                          device-code/poll-for-auth!      (fn [d _ _ _]
                                                           (should= descriptor d)
                                                           (swap! steps conj :poll)
                                                           {:authorization_code "auth-xyz"
                                                            :code_verifier     "verify-abc"})
                          device-code/exchange-tokens!    (fn [d _ _]
                                                            (should= descriptor d)
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
          (let [poll-interval (atom nil)
                descriptor    {:verification-url "https://auth.openai.com/codex/device"}]
            (with-redefs [device-code/provider-descriptor! (fn [_] descriptor)
                          device-code/request-user-code! (fn [_]
                                                           {:device_auth_id "dauth-1"
                                                            :user_code      "TEST-CODE"
                                                            :interval       "5"})
                          device-code/poll-for-auth!      (fn [_ _ _ interval-ms]
                                                           (reset! poll-interval interval-ms)
                                                           {:authorization_code "ac"
                                                            :code_verifier     "cv"})
                          device-code/exchange-tokens!    (fn [_ _ _]
                                                            {:access_token  "at"
                                                             :refresh_token "rt"
                                                             :id_token      "id"
                                                             :expires_in    3600})
                          auth-store/save-tokens!         (fn [_ _ _ _] nil)]
              (should= 0 (sut/run ["login" "--provider" "chatgpt"]))
              (should= 5000 @poll-interval))))

        (it "returns 1 when request-user-code fails"
          (with-redefs [device-code/provider-descriptor! (fn [_] {:verification-url "https://auth.openai.com/codex/device"})
                        device-code/request-user-code! (fn [_] {:error :api-error :status 404})]
            (should= 1 (sut/run ["login" "--provider" "chatgpt"]))))

        (it "prints HTTP status and body message on device-code failure, not bare :unknown"
          (let [output (atom nil)]
            (with-redefs [device-code/provider-descriptor! (fn [_] device-code/grok-descriptor)
                          device-code/request-user-code! (fn [_]
                                                           {:error   :api-error
                                                            :status  415
                                                            :message "Form requests must have Content-Type: application/x-www-form-urlencoded"})]
              (binding [*out* (java.io.StringWriter.)]
                (should= 1 (sut/run ["login" "--provider" "grok"]))
                (reset! output (str *out*))))
            (should (clojure.string/includes? @output "415"))
            (should (clojure.string/includes? @output "form-urlencoded"))
            (should-not (clojure.string/includes? @output ":unknown"))))

        (it "completes grok login after authorization_pending polls (oidc RFC 8628)"
          (let [poll-calls (atom 0)
                descriptor (assoc device-code/grok-descriptor :flow :oidc-device-code)]
            (with-redefs [device-code/provider-descriptor! (fn [_] descriptor)
                          device-code/-post-form!
                          (fn [url _body]
                            (if (str/includes? url "/device/code")
                              {:device_code "dc-live" :user_code "LIVE-CODE" :interval 5}
                              (do
                                (swap! poll-calls inc)
                                (if (<= @poll-calls 2)
                                  {:error  :api-error
                                   :status 400
                                   :body   {:error "authorization_pending"}}
                                  {:authorization_code "ac" :code_verifier "cv"}))))
                          device-code/sleep! (fn [_] nil)
                          device-code/exchange-tokens! (fn [_ _ _]
                                                         {:access_token  "at"
                                                          :refresh_token "rt"
                                                          :expires_in    3600})
                          auth-store/save-tokens! (fn [_ _ _ _] nil)]
              (should= 0 (sut/run ["login" "--provider" "grok"]))
              (should= 3 @poll-calls))))

        (it "returns 1 when poll-for-auth fails"
          (with-redefs [device-code/provider-descriptor! (fn [_] {:verification-url "https://auth.openai.com/codex/device"})
                        device-code/request-user-code! (fn [_]
                                                         {:device_auth_id "d"
                                                          :user_code      "C"
                                                          :interval       5})
                        device-code/poll-for-auth!      (fn [_ _ _ _]
                                                         {:error :timeout})]
            (should= 1 (sut/run ["login" "--provider" "chatgpt"]))))

        (it "returns 1 when token exchange fails"
          (with-redefs [device-code/provider-descriptor! (fn [_] {:verification-url "https://auth.openai.com/codex/device"})
                        device-code/request-user-code! (fn [_]
                                                         {:device_auth_id "d"
                                                          :user_code      "C"
                                                          :interval       5})
                        device-code/poll-for-auth!      (fn [_ _ _ _]
                                                         {:authorization_code "ac"
                                                          :code_verifier     "cv"})
                        device-code/exchange-tokens!    (fn [_ _ _]
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
        (with-redefs [loader/load-config! (fn [& _] {:providers {"ollama" {:auth "none"}}})
                      auth-store/load-tokens (fn [& _] nil)]
          (should= 0 (sut/run ["status"]))))

      (it "reports EXPIRED for an expired chatgpt oauth token, not 'no auth required' (isaac-b9rh)"
        (with-redefs [loader/load-config! (fn [& _] {:providers {"chatgpt" {:auth "oauth-device"}} :root "/auth"})
                      auth-store/load-tokens (fn [& _] {:type "oauth" :expires (- (System/currentTimeMillis) 1000)})]
          (let [output (with-out-str (should= 0 (sut/run ["status"])))]
            (should (clojure.string/includes? output "chatgpt: EXPIRED"))
            (should-not (clojure.string/includes? output "no auth required")))))

      (it "reports anthropic api-key auth in the output"
        (with-redefs [loader/load-config! (fn [& _] {:providers {"anthropic" {:auth "api-key" :api-key "sk-123"}} :root "/auth"})
                      auth-store/load-tokens (fn [& _] nil)]
          (let [output (with-out-str (should= 0 (sut/run ["status"])))]
            (should (clojure.string/includes? output "anthropic: authenticated (API key)"))))))

    (describe "provider-auth-line (isaac-b9rh)"

      (it "reports EXPIRED for an expired oauth token — never 'no auth required'"
        (let [now 1500000000000]
          (should= "EXPIRED — run isaac auth login --provider chatgpt"
                   (#'sut/provider-auth-line "chatgpt" {:auth "oauth-device"}
                                             {:type "oauth" :expires (- now 1000)} now))))

      (it "reports authenticated with time-to-expiry for a valid oauth token"
        (let [now 1500000000000]
          (should= "authenticated (expires in 2h)"
                   (#'sut/provider-auth-line "chatgpt" {:auth "oauth-device"}
                                             {:type "oauth" :expires (+ now (* 2 60 60 1000))} now))))

      (it "reports not logged in for an oauth provider with no token"
        (should= "not logged in"
                 (#'sut/provider-auth-line "chatgpt" {:auth "oauth-device"} nil 1500000000000)))

      (it "reports authenticated (API key) for a keyed api-key provider"
        (should= "authenticated (API key)"
                 (#'sut/provider-auth-line "anthropic" {:auth "api-key" :api-key "sk-1"} nil 1500000000000)))

      (it "reports no auth required for a genuinely keyless provider"
        (should= "no auth required"
                 (#'sut/provider-auth-line "ollama" {:auth "none"} nil 1500000000000))))

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
