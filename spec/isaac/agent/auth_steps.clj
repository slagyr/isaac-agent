(ns isaac.agent.auth-steps
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.fs :as fs]
    [isaac.llm.api.openai.shared :as openai-shared]
    [isaac.llm.auth.device-code :as device-code]
    [isaac.llm.auth.store :as auth-store]
    [isaac.nexus :as nexus]))

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
    (nexus/-with-nested-nexus {:fs mem} (fn [] (f mem)))))

(defn- auth-root []
  (or (g/get :root) "target/test-state"))

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

(defn oauth-token-refresh-fails []
  (g/assoc! :oauth-refresh-stub {:error :api-error}))

(defn chatgpt-oauth-access-resolved []
  (with-redefs [device-code/refresh-tokens!
                (fn [_] (g/get :oauth-refresh-stub))]
    (let [tokens (openai-shared/resolve-oauth-tokens
                   "chatgpt" {:auth "oauth-device" :root (auth-root)})]
      (g/assoc! :oauth-resolve-result tokens))))

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

(defn chatgpt-oauth-access-unavailable []
  (g/should-be-nil (g/get :oauth-resolve-result)))

(defn oauth-access-error-mentions [message]
  (with-redefs [device-code/refresh-tokens!
                (fn [_] (g/get :oauth-refresh-stub))]
    (let [err (openai-shared/missing-auth-error "chatgpt"
                                                {:auth "oauth-device" :root (auth-root)})]
      (g/should (str/includes? (:message err) message)))))

(defgiven "authenticated credentials exist for provider {provider:string}" isaac.agent.auth-steps/authenticated-credentials
  "Writes a minimal api-key credential to <root>/auth.json for the provider.")

(defthen "the stdout prompts for an API key" isaac.agent.auth-steps/output-prompts-for-key)

(defthen "credentials for {provider:string} are removed" isaac.agent.auth-steps/credentials-removed
  "Asserts the logout message appeared in stdout.")

(defgiven #"chatgpt oauth token is expired with refresh \"([^\"]+)\"" isaac.agent.auth-steps/chatgpt-oauth-token-expired-with-refresh)

(defgiven #"oauth token refresh returns access token \"([^\"]+)\" expires in (\d+)" isaac.agent.auth-steps/oauth-token-refresh-returns)

(defgiven "oauth token refresh fails" isaac.agent.auth-steps/oauth-token-refresh-fails)

(defwhen "chatgpt oauth access is resolved" isaac.agent.auth-steps/chatgpt-oauth-access-resolved)

(defthen "chatgpt oauth tokens have a future expiry" isaac.agent.auth-steps/chatgpt-oauth-tokens-have-future-expiry)

(defthen #"persisted chatgpt oauth access is \"([^\"]+)\"" isaac.agent.auth-steps/persisted-chatgpt-oauth-access-is)

(defthen "chatgpt oauth access is unavailable" isaac.agent.auth-steps/chatgpt-oauth-access-unavailable)

(defthen #"the oauth access error mentions \"(.+)\"" isaac.agent.auth-steps/oauth-access-error-mentions)
