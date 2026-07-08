(ns isaac.llm.api.openai.shared-spec
  (:require
    [isaac.fs :as fs]
    [isaac.llm.api.openai.shared :as sut]
    [isaac.llm.auth.device-code :as device-code]
    [isaac.llm.auth.store :as auth-store]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def oauth-config {:auth "oauth-device"
                   :root "/auth"
                   :oauth {:issuer "https://auth.openai.com"
                           :token-path "/oauth/token"
                           :verification-url "https://auth.openai.com/codex/device"
                           :client-id "chatgpt-client"
                           :originator "isaac"
                           :chatgpt-account-id? true}})

(describe "openai shared oauth"

  (with fs (fs/mem-fs))

  (it "refreshes expired tokens before resolving"
    (nexus/-with-nested-nexus {:fs @fs}
      (auth-store/save-tokens! "/auth" "chatgpt" {:access_token  "at-old"
                                                  :refresh_token "rt-ok"
                                                  :expires_in    -60} @fs)
      (with-redefs [device-code/refresh-tokens! (fn [descriptor _]
                                                  (should= (:oauth oauth-config) descriptor)
                                                  {:access_token "at-new" :expires_in 3600})]
        (let [tokens (sut/resolve-oauth-tokens "chatgpt" oauth-config)]
          (should= "at-new" (:access tokens))
          (should-not (auth-store/token-expired? tokens))))))

  (it "surfaces a non-429 refresh failure as unavailable with the default retry"
    (nexus/-with-nested-nexus {:fs @fs}
      (auth-store/save-tokens! "/auth" "chatgpt" {:access_token  "at-old"
                                                  :refresh_token "rt-bad"
                                                  :expires_in    -60} @fs)
      (with-redefs [device-code/refresh-tokens! (fn [descriptor _]
                                                  (should= (:oauth oauth-config) descriptor)
                                                  {:error :api-error})]
        (let [result (sut/missing-auth-error "chatgpt" oauth-config)]
          (should (:unavailable? result))
          (should= 1800000 (:retry-after-ms result))))))

  (it "surfaces a refresh provider wall as unavailable"
    (nexus/-with-nested-nexus {:fs @fs}
      (auth-store/save-tokens! "/auth" "chatgpt" {:access_token  "at-old"
                                                  :refresh_token "rt-bad"
                                                  :expires_in    -60} @fs)
      (with-redefs [device-code/refresh-tokens! (fn [descriptor _]
                                                  (should= (:oauth oauth-config) descriptor)
                                                  {:error :api-error
                                                   :status 429
                                                   :retry-after 60
                                                   :message "usage_limit_reached: The usage limit has been reached"})]
        (let [result (sut/missing-auth-error "chatgpt" oauth-config)]
          (should (:unavailable? result))
          (should= 60000 (:retry-after-ms result))))))

  (it "retries once after auth-failed when refresh succeeds"
    (auth-store/save-tokens! "/auth" "chatgpt" {:access_token  "at-old"
                                                :refresh_token "rt-ok"
                                                :expires_in    3600} @fs)
    (let [attempts* (atom 0)]
      (nexus/-with-nested-nexus {:fs @fs}
        (with-redefs [device-code/refresh-tokens! (fn [descriptor _]
                                                    (should= (:oauth oauth-config) descriptor)
                                                    {:access_token "at-new" :expires_in 3600})]
          (let [result (sut/with-oauth-refresh-retry "chatgpt" oauth-config
                       (fn []
                         (swap! attempts* inc)
                         (if (= 1 @attempts*)
                           {:error :auth-failed}
                           {:ok true})))]
            (should= {:ok true} result)
            (should= 2 @attempts*)))))))