(ns isaac.agent.auth-steps
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.fs :as fs]
    [isaac.llm.api.openai.shared :as openai-shared]
    [isaac.llm.auth.device-code :as device-code]
    [isaac.llm.auth.store :as auth-store]
    [isaac.nexus :as nexus]))

(defn- stubbed-device-code-login? []
  (not= false (g/get :oauth-device-code-stub)))

(defn- stub-user-code-response [_descriptor]
  {:device_auth_id "device-auth-stub"
   :user_code      "TEST-CODE"
   :interval       0})

(defn- stub-auth-response [_descriptor _device-id _user-code _interval-ms]
  {:authorization_code "auth-code-stub"
   :code_verifier     "code-verifier-stub"})

(defn- stub-token-response [_descriptor _authorization-code _code-verifier]
  {:access_token  "at-stub"
   :refresh_token "rt-stub"
   :id_token      "id-stub"
   :expires_in    3600})

(fcli/register-isaac-run-wrapper!
  (fn [thunk]
    (if (stubbed-device-code-login?)
      (with-redefs [device-code/request-user-code! stub-user-code-response
                    device-code/poll-for-auth!   stub-auth-response
                    device-code/exchange-tokens! stub-token-response]
        (thunk))
      (thunk))))

(helper! isaac.agent.auth-steps)

(defn authenticated-credentials [provider]
  (let [root      (or (g/get :root) "target/test-state")
        auth-file (str root "/auth.json")
        mem-fs    (g/get :mem-fs)
        write-fn  (fn [fs*]
                    (let [auth-data (if (fs/exists? fs* auth-file)
                                      (json/parse-string (fs/slurp fs* auth-file) true)
                                      {})]
                      (fs/mkdirs fs* (fs/parent auth-file))
                      (fs/spit fs* auth-file
                               (json/generate-string
                                 (assoc-in auth-data [:providers (keyword provider)]
                                           {:type "api-key" :apiKey "sk-test-key"})))))]
    (if mem-fs
      (nexus/-with-nested-nexus {:fs mem-fs}
        (write-fn mem-fs))
      (write-fn (or (nexus/get :fs) (fs/real-fs))))))

(defn output-prompts-for-key []
  (let [output (g/get :output)]
    (g/should (or (str/includes? output "API key")
                  (str/includes? output "Enter")))))

(defn credentials-removed [_provider]
  (let [output (g/get :output)]
    (g/should (str/includes? output "Logged out"))))

(defn- with-mem-fs [f]
  (let [mem (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))]
    (nexus/-with-nested-nexus {:fs mem}
      (f mem))))

(defn- auth-root []
  (or (g/get :root) "target/test-state"))

(defn- unquote-string [s]
  (if (and (string? s)
           (>= (count s) 2)
           (= \" (first s))
           (= \" (last s)))
    (subs s 1 (dec (count s)))
    s))

(defn chatgpt-oauth-token-expired-with-refresh [refresh-token]
  (with-mem-fs
    (fn [mem]
      (auth-store/save-tokens! (auth-root) "chatgpt"
                             {:access_token  "at-expired"
                              :refresh_token refresh-token
                              :expires_in    -3600}
                             mem))))

(defn oauth-token-refresh-returns [access-token expires-in]
  (g/assoc! :oauth-refresh-stub
            {:access_token access-token :expires_in (parse-long expires-in)}))

(defn oauth-token-refresh-rotates [access-token refresh-token expires-in]
  (g/assoc! :oauth-refresh-stub
            {:access_token access-token
             :refresh_token refresh-token
             :expires_in (parse-long expires-in)}))

(defn oauth-token-refresh-fails []
  (g/assoc! :oauth-refresh-stub {:error :api-error}))

(defn oauth-token-refresh-returns-provider-wall [retry-after-seconds]
  (g/assoc! :oauth-refresh-stub {:error :api-error
                                 :status 429
                                 :retry-after (parse-long retry-after-seconds)
                                 :message "usage_limit_reached: The usage limit has been reached"}))

(def ^:private chatgpt-oauth-descriptor
  {:issuer           "https://auth.openai.com"
   :client-id        "chatgpt-client"
   :token-path       "/oauth/token"
   :verification-url "https://auth.openai.com/codex/device"})

(defn chatgpt-oauth-access-resolved []
  (with-mem-fs
    (fn [_]
      (with-redefs [device-code/refresh-tokens!
                    (fn [_ _] (g/get :oauth-refresh-stub))]
        (let [tokens (openai-shared/resolve-oauth-tokens
                       "chatgpt" {:auth "oauth-device" :root (auth-root) :oauth chatgpt-oauth-descriptor})]
          (g/assoc! :oauth-resolve-result tokens))))))

(defn chatgpt-oauth-tokens-have-future-expiry []
  (with-mem-fs
    (fn [mem]
      (let [tokens (auth-store/load-tokens (auth-root) "chatgpt" mem)]
        (g/should (some? tokens))
        (g/should-not (auth-store/token-expired? tokens))))))

(defn persisted-chatgpt-oauth-access-is [access-token]
  (with-mem-fs
    (fn [mem]
      (let [tokens (auth-store/load-tokens (auth-root) "chatgpt" mem)]
        (g/should= access-token (:access tokens))))))

(defn persisted-chatgpt-oauth-refresh-is [refresh-token]
  (with-mem-fs
    (fn [mem]
      (let [tokens (auth-store/load-tokens (auth-root) "chatgpt" mem)]
        (g/should= refresh-token (:refresh tokens))))))

(defn chatgpt-oauth-access-unavailable []
  (g/should-be-nil (g/get :oauth-resolve-result)))

(defn oauth-access-error-mentions [message]
  (with-mem-fs
    (fn [_]
      (with-redefs [device-code/refresh-tokens!
                    (fn [_ _] (g/get :oauth-refresh-stub))]
        (let [err (openai-shared/missing-auth-error "chatgpt"
                                                    {:auth "oauth-device" :root (auth-root) :oauth chatgpt-oauth-descriptor})]
          (g/should (str/includes? (:message err) message)))))))

(defn chatgpt-oauth-refresh-defaults-to-provider-wall-delay []
  (with-mem-fs
    (fn [_]
      (with-redefs [device-code/refresh-tokens!
                    (fn [_ _] (g/get :oauth-refresh-stub))]
        (let [result (openai-shared/missing-auth-error "chatgpt"
                                                       {:auth "oauth-device" :root (auth-root) :oauth chatgpt-oauth-descriptor})]
          (g/should (:unavailable? result))
          (g/should= 1800000 (:retry-after-ms result)))))))

(defn chatgpt-oauth-access-is-provider-wall [retry-after-seconds]
  (with-mem-fs
    (fn [_]
      (with-redefs [device-code/refresh-tokens!
                    (fn [_ _] (g/get :oauth-refresh-stub))]
        (let [result (openai-shared/missing-auth-error "chatgpt"
                                                       {:auth "oauth-device" :root (auth-root) :oauth chatgpt-oauth-descriptor})]
          (g/should (:unavailable? result))
          (g/should= (* 1000 (parse-long retry-after-seconds)) (:retry-after-ms result)))))))

(defn two-concurrent-chatgpt-oauth-refreshers-resolve-access []
  (with-mem-fs
    (fn [mem]
      (let [started  (promise)
            release  (promise)
            calls*   (atom 0)
            fs-val   mem]
        (with-redefs [device-code/refresh-tokens!
                      (fn [descriptor _]
                        (g/should= chatgpt-oauth-descriptor descriptor)
                        (swap! calls* inc)
                        (deliver started true)
                        @release
                        {:access_token "at-new"
                         :refresh_token "rt-rotated"
                         :expires_in 3600})]
          (let [f1 (future (auth-store/refresh-oauth-tokens! (auth-root) "chatgpt" fs-val chatgpt-oauth-descriptor))]
            @started
            (let [f2 (future (auth-store/refresh-oauth-tokens! (auth-root) "chatgpt" fs-val chatgpt-oauth-descriptor))
                  pending? (= ::pending (deref f2 300 ::pending))]
              (deliver release true)
              (g/assoc! :oauth-concurrent-refresh
                        {:pending? pending?
                         :calls    @calls*
                         :first    @f1
                         :second   @f2
                         :stored   (auth-store/load-tokens (auth-root) "chatgpt" fs-val)}))))))))

(defn both-chatgpt-oauth-refreshers-observe [access-token refresh-token]
  (let [access-token  (unquote-string access-token)
        refresh-token (unquote-string refresh-token)
        {:keys [pending? calls first second stored]} (g/get :oauth-concurrent-refresh)]
    (g/should pending?)
    (g/should= 1 calls)
    (g/should= access-token (get-in first [:tokens :access]))
    (g/should= refresh-token (get-in first [:tokens :refresh]))
    (g/should= access-token (get-in second [:tokens :access]))
    (g/should= refresh-token (get-in second [:tokens :refresh]))
    (g/should= access-token (:access stored))
    (g/should= refresh-token (:refresh stored))))

(defgiven "authenticated credentials exist for provider {provider:string}" isaac.agent.auth-steps/authenticated-credentials
  "Writes a minimal api-key credential to <root>/auth.json for the provider.")

(defthen "the stdout prompts for an API key" isaac.agent.auth-steps/output-prompts-for-key)

(defthen "credentials for {provider:string} are removed" isaac.agent.auth-steps/credentials-removed
  "Asserts the logout message appeared in stdout.")

(defgiven #"chatgpt oauth token is expired with refresh \"([^\"]+)\"" isaac.agent.auth-steps/chatgpt-oauth-token-expired-with-refresh)

(defgiven #"oauth token refresh returns access token \"([^\"]+)\" expires in (\d+)" isaac.agent.auth-steps/oauth-token-refresh-returns)

(defgiven #"oauth token refresh returns access token \"([^\"]+)\" and refresh token \"([^\"]+)\" expires in (\d+)" isaac.agent.auth-steps/oauth-token-refresh-rotates)

(defgiven "oauth token refresh fails" isaac.agent.auth-steps/oauth-token-refresh-fails)

(defgiven "oauth token refresh returns a provider wall after {int} seconds" isaac.agent.auth-steps/oauth-token-refresh-returns-provider-wall)

(defwhen "chatgpt oauth access is resolved" isaac.agent.auth-steps/chatgpt-oauth-access-resolved)

(defwhen "two concurrent chatgpt oauth refreshers resolve access" isaac.agent.auth-steps/two-concurrent-chatgpt-oauth-refreshers-resolve-access)

(defthen "chatgpt oauth tokens have a future expiry" isaac.agent.auth-steps/chatgpt-oauth-tokens-have-future-expiry)

(defthen #"persisted chatgpt oauth access is \"([^\"]+)\"" isaac.agent.auth-steps/persisted-chatgpt-oauth-access-is)

(defthen #"persisted chatgpt oauth refresh is \"([^\"]+)\"" isaac.agent.auth-steps/persisted-chatgpt-oauth-refresh-is)

(defthen "chatgpt oauth access is unavailable" isaac.agent.auth-steps/chatgpt-oauth-access-unavailable)

(defthen "chatgpt oauth access is deferred as a provider wall after {int} seconds" isaac.agent.auth-steps/chatgpt-oauth-access-is-provider-wall)

(defthen "chatgpt oauth access is deferred using the default provider-wall delay" isaac.agent.auth-steps/chatgpt-oauth-refresh-defaults-to-provider-wall-delay)

(defthen "both chatgpt oauth refreshers observe access {string} and refresh {string}" isaac.agent.auth-steps/both-chatgpt-oauth-refreshers-observe)

(defthen #"the oauth access error mentions \"(.+)\"" isaac.agent.auth-steps/oauth-access-error-mentions)
