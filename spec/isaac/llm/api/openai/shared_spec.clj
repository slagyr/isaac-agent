(ns isaac.llm.api.openai.shared-spec
  (:require
    [isaac.config.loader :as loader]
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

(describe "openai shared http opts"

  (it "passes stream idle timeout through llm-http-opts"
    (should= {:session-key "s1" :stream-idle-timeout-ms 100}
             (sut/llm-http-opts {:session-key "s1" :stream-idle-timeout-ms 100})))

  )

(describe "openai shared missing-auth-error"

  (it "names the conventional env var and the provider file when nothing is set"
    (let [message (:message (sut/missing-auth-error "chatgpt" {}))]
      (should-contain "No API key for chatgpt." message)
      (should-contain "Set CHATGPT_API_KEY in the environment" message)
      (should-contain "providers/chatgpt.edn" message)))

  ;; isaac-rxun: :api-key "${OPENAI_ZANE_EMBEDDING_API_KEY}" with the variable
  ;; unset used to reach the provider as that literal and come back a 401
  ;; blaming auth. Config now drops the field and the provider slice carries
  ;; the variable, so the point of use can say which one — without reading
  ;; the ambient config snapshot from in-flight code.
  (context "when config could not resolve the :api-key reference"

    (redefs-around [loader/snapshot (fn [& _] (throw (ex-info "in-flight code read the ambient config" {})))])

    (it "names the variable the :api-key referenced"
      (let [config  {:unresolved-refs {"providers.chatgpt.api-key" "OPENAI_ZANE_EMBEDDING_API_KEY"}}
            message (:message (sut/missing-auth-error "chatgpt" config))]
        (should-contain "No API key for chatgpt." message)
        (should-contain ":api-key references ${OPENAI_ZANE_EMBEDDING_API_KEY}" message)
        (should-not-contain "Set CHATGPT_API_KEY in the environment" message)))

    (it "reads a simulated provider id's reason from its base provider"
      (let [config {:unresolved-refs {"providers.chatgpt.api-key" "ZANE_KEY"}}]
        (should-contain "${ZANE_KEY}" (:message (sut/missing-auth-error "chatgpt:gpt-5" config)))))

    (it "keeps the conventional message for another provider's unresolved reference"
      (should-contain "Set CHATGPT_API_KEY in the environment"
                      (:message (sut/missing-auth-error "chatgpt" {:unresolved-refs {"providers.other.api-key" "OTHER_KEY"}}))))

    (it "keeps the conventional message for another field's unresolved reference"
      (should-contain "Set CHATGPT_API_KEY in the environment"
                      (:message (sut/missing-auth-error "chatgpt" {:unresolved-refs {"providers.chatgpt.base-url" "URL"}}))))

    (it "gives the anthropic-family error the same reason"
      (let [config {:unresolved-refs {"providers.anthropic.api-key" "ANTHROPIC_ZANE_KEY"}}]
        (should-contain ":api-key references ${ANTHROPIC_ZANE_KEY}"
                        (:message (sut/api-key-missing-error "anthropic" config "anthropic"))))))

  (it "says nothing when the key resolves"
    (should-be-nil (sut/missing-auth-error "chatgpt" {:api-key "sk-real"}))))

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