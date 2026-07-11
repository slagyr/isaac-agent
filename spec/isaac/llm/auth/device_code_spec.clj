(ns isaac.llm.auth.device-code-spec
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.llm.auth.device-code :as sut]
    [speclj.core :refer :all]))

(describe "Device Code Auth"

  (with-stubs)

  (describe "descriptors"

    (it "provides the built-in chatgpt oauth descriptor"
      (let [descriptor sut/chatgpt-descriptor]
        (should= "app_EMoamEEZ73f0CkXaXp7hrann" (:client-id descriptor))
        (should= "https://auth.openai.com" (:issuer descriptor))
        (should= "https://auth.openai.com/codex/device" (:verification-url descriptor))))

    (it "derives device-code endpoints from an oauth descriptor"
      (let [descriptor {:issuer           "https://auth.x.ai"
                        :device-path      "/oauth2/device/code"
                        :authorize-path   "/oauth2/authorize"
                        :verification-url "https://accounts.x.ai/oauth2/device"
                        :token-path       "/oauth2/token"
                        :client-id        "grok-client"}]
        (should= "https://auth.x.ai/oauth2/device/code" (#'sut/device-code-url descriptor))
        (should= "https://auth.x.ai/oauth2/token" (#'sut/token-url descriptor)))))

  (describe "request-user-code!"

    (it "returns user code response on success"
      (with-redefs [sut/-post-json! (fn [_url _headers _body]
                                      {:device_auth_id "dauth-123"
                                       :user_code      "ABCD-1234"
                                       :interval       5})]
        (let [result (sut/request-user-code! sut/chatgpt-descriptor)]
          (should= "dauth-123" (:device_auth_id result))
          (should= "ABCD-1234" (:user_code result))
          (should= 5 (:interval result)))))

    (it "passes descriptor URL and client_id for chatgpt"
      (let [captured (atom nil)]
        (with-redefs [sut/-post-json! (fn [url headers body]
                                        (reset! captured {:url url :headers headers :body body})
                                        {:device_auth_id "x" :user_code "Y" :interval 5})]
          (sut/request-user-code! sut/chatgpt-descriptor)
          (should= "https://auth.openai.com/api/accounts/deviceauth/usercode" (:url @captured))
          (should= {"client_id" "app_EMoamEEZ73f0CkXaXp7hrann"} (:body @captured)))))

    (it "passes descriptor URL and client_id for grok via form encoding (oidc)"
      (let [captured (atom nil)
            descriptor {:issuer           "https://auth.x.ai"
                        :device-path      "/oauth2/device/code"
                        :token-path       "/oauth2/token"
                        :verification-url "https://accounts.x.ai/oauth2/device"
                        :flow             :oidc-device-code
                        :client-id        "grok-client"}]
        (with-redefs [sut/-post-form! (fn [url body]
                                        (reset! captured {:url url :body body})
                                        {:device_code "dc" :user_code "UC" :interval 5})]
          (sut/request-user-code! descriptor)
          (should= "https://auth.x.ai/oauth2/device/code" (:url @captured))
          (should= {"client_id" "grok-client"} (:body @captured)))))

    (it "uses JSON for chatgpt openai device-auth request (regression)"
      (let [captured (atom nil)]
        (with-redefs [sut/-post-json! (fn [url headers body]
                                        (reset! captured {:url url :headers headers :body body})
                                        {:device_auth_id "x" :user_code "Y" :interval 5})
                      sut/-post-form! (fn [& _] (throw (ex-info "oidc must not use form for chatgpt" {})))]
          (sut/request-user-code! sut/chatgpt-descriptor)
          (should= "https://auth.openai.com/api/accounts/deviceauth/usercode" (:url @captured))
          (should= {"client_id" "app_EMoamEEZ73f0CkXaXp7hrann"} (:body @captured)))))

    (it "returns error map on failure"
      (with-redefs [sut/-post-json! (fn [_url _headers _body]
                                      {:error :api-error :status 404})]
        (let [result (sut/request-user-code! sut/chatgpt-descriptor)]
          (should= :api-error (:error result))))))

  (describe "-post-json!"

    (it "posts json with merged headers and parses success"
      (let [captured (atom nil)]
        (with-redefs [http/post (fn [url opts]
                                  (reset! captured {:url url :opts opts})
                                  {:status 200 :body (json/generate-string {:ok true})})]
          (let [result (sut/-post-json! "https://example.test" {"X-Test" "1"} {"a" 1})]
            (should= {:ok true} result)
            (should= "https://example.test" (:url @captured))
            (should= "application/json" (get-in @captured [:opts :headers "Content-Type"]))
            (should= "1" (get-in @captured [:opts :headers "X-Test"]))))))

    (it "returns pending on 404"
      (with-redefs [http/post (fn [_ _]
                                {:status 404 :body (json/generate-string {:error "nope"})})]
        (let [result (sut/-post-json! "https://example.test" {} {})]
          (should= :pending (:error result))
          (should= 404 (:status result)))))

    (it "returns api-error on 500"
      (with-redefs [http/post (fn [_ _]
                                {:status 500 :body (json/generate-string {:error "bad"})})]
        (let [result (sut/-post-json! "https://example.test" {} {})]
          (should= :api-error (:error result))
          (should= 500 (:status result)))))

    (it "surfaces response body message on 415 form error"
      (with-redefs [http/post (fn [_ _]
                                {:status 415
                                 :body   "Form requests must have Content-Type: application/x-www-form-urlencoded"})]
        (let [result (sut/-post-form! "https://auth.x.ai/oauth2/device/code" {"client_id" "x"})]
          (should= :api-error (:error result))
          (should= 415 (:status result))
          (should-contain "form-urlencoded" (:message result)))))

    (it "returns unknown on exception"
      (with-redefs [http/post (fn [_ _] (throw (ex-info "boom" {})))]
        (let [result (sut/-post-json! "https://example.test" {} {})]
          (should= :unknown (:error result))
          (should-contain "boom" (:message result))))))

  (describe "poll-for-auth!"

    (it "returns authorization on success after pending"
      (let [call-count (atom 0)]
        (with-redefs [sut/-post-json! (fn [_url _headers _body]
                                        (swap! call-count inc)
                                        (if (< @call-count 3)
                                          {:error :pending :status 403}
                                          {:authorization_code "auth-code-xyz"
                                           :code_verifier     "verifier-abc"}))]
          (let [result (sut/poll-for-auth! sut/chatgpt-descriptor "dauth-123" "ABCD-1234" 0)]
            (should= "auth-code-xyz" (:authorization_code result))
            (should= "verifier-abc" (:code_verifier result))
            (should= 3 @call-count)))))

    (it "passes descriptor token URL, device_auth_id, and user_code in body"
      (let [captured (atom nil)]
        (with-redefs [sut/-post-json! (fn [url _headers body]
                                        (reset! captured {:url url :body body})
                                        {:authorization_code "ac" :code_verifier "cv"})]
          (sut/poll-for-auth! sut/chatgpt-descriptor "dauth-999" "CODE-5678" 0)
          (should= "https://auth.openai.com/api/accounts/deviceauth/token" (:url @captured))
          (should= {"device_auth_id" "dauth-999" "user_code" "CODE-5678"} (:body @captured)))))

    (it "uses oidc device_code polling via form encoding for descriptor-driven providers"
      (let [captured   (atom nil)
            descriptor {:issuer           "https://auth.x.ai"
                        :client-id        "grok-client"
                        :token-path       "/oauth2/token"
                        :verification-url "https://accounts.x.ai/oauth2/device"
                        :flow             :oidc-device-code}]
        (with-redefs [sut/-post-form! (fn [url body]
                                        (reset! captured {:url url :body body})
                                        {:access_token "at" :refresh_token "rt"})]
          (sut/poll-for-auth! descriptor "dc-999" "CODE-5678" 0)
          (should= "https://auth.x.ai/oauth2/token" (:url @captured))
          (should= {"grant_type" "urn:ietf:params:oauth:grant-type:device_code"
                    "client_id" "grok-client"
                    "device_code" "dc-999"}
                   (:body @captured)))))

    (it "keeps polling through oidc authorization_pending then succeeds"
      (let [calls (atom 0)
            descriptor (assoc sut/grok-descriptor :flow :oidc-device-code)]
        (with-redefs [sut/-post-form! (fn [_ _]
                                        (swap! calls inc)
                                        (if (<= @calls 2)
                                          {:error  :api-error
                                           :status 400
                                           :body   {:error "authorization_pending"}}
                                          {:access_token "at-ok" :refresh_token "rt-ok"}))
                      sut/sleep! (fn [_] nil)]
          (let [result (sut/poll-for-auth! descriptor "dc-1" "UC" 5000)]
            (should= "at-ok" (:access_token result))
            (should= "rt-ok" (:refresh_token result))
            (should-not (:authorization_code result))
            (should= 3 @calls)))))

    (it "increases poll interval after oidc slow_down"
      (let [calls (atom 0)
            slept (atom [])
            descriptor (assoc sut/grok-descriptor :flow :oidc-device-code)]
        (with-redefs [sut/-post-form! (fn [_ _]
                                        (swap! calls inc)
                                        (if (= 1 @calls)
                                          {:error  :api-error
                                           :status 400
                                           :body   {:error "slow_down"}}
                                          {:access_token "at" :refresh_token "rt"}))
                      sut/sleep! (fn [ms] (swap! slept conj ms))]
          (sut/poll-for-auth! descriptor "dc-1" "UC" 5000)
          (should= [5000 10000] @slept))))

    (it "terminates oidc poll on access_denied with message"
      (let [descriptor (assoc sut/grok-descriptor :flow :oidc-device-code)]
        (with-redefs [sut/-post-form! (fn [_ _]
                                        {:error  :api-error
                                         :status 400
                                         :body   {:error "access_denied"}})
                      sut/sleep! (fn [_] nil)]
          (let [result (sut/poll-for-auth! descriptor "dc-1" "UC" 0)]
            (should= :api-error (:error result))
            (should= "access_denied" (:message result))))))

    (it "asserts form Content-Type and scope on oidc device-code request (scripted endpoint)"
      (let [captured (atom nil)
            descriptor (assoc sut/grok-descriptor :flow :oidc-device-code)]
        (with-redefs [http/post (fn [url opts]
                                  (reset! captured {:url url :opts opts})
                                  {:status 200
                                   :body   (json/generate-string {:device_code "dc" :user_code "UC" :interval 5})})]
          (sut/request-user-code! descriptor)
          (should= "application/x-www-form-urlencoded"
                   (get-in @captured [:opts :headers "Content-Type"]))
          (should-contain "client_id=" (get-in @captured [:opts :body]))
          (should-contain "scope=" (get-in @captured [:opts :body]))
          (should-contain "api%3Aaccess" (get-in @captured [:opts :body]))
          (should-contain "offline_access" (get-in @captured [:opts :body])))))

    (it "does not send scope on chatgpt openai device-auth request (regression)"
      (let [captured (atom nil)]
        (with-redefs [sut/-post-json! (fn [url headers body]
                                        (reset! captured {:url url :headers headers :body body})
                                        {:device_auth_id "x" :user_code "Y" :interval 5})]
          (sut/request-user-code! sut/chatgpt-descriptor)
          (should-not (contains? (:body @captured) "scope"))
          (should-not (contains? (:body @captured) :scope))))))

    (it "asserts form Content-Type on oidc poll and refresh"
      (let [poll-captured (atom nil)
            descriptor    (assoc sut/grok-descriptor :flow :oidc-device-code)]
        (with-redefs [http/post (fn [url opts]
                                  (when (str/includes? url "/oauth2/token")
                                    (reset! poll-captured {:url url :opts opts}))
                                  {:status 200
                                   :body   (json/generate-string
                                             (if (str/includes? (or (get opts :body) "") "device_code")
                                               {:access_token "at" :refresh_token "rt"}
                                               {:access_token "at2" :refresh_token "rt2" :expires_in 3600}))})]
          (sut/poll-for-auth! descriptor "dc-1" "UC" 0)
          (should= "application/x-www-form-urlencoded"
                   (get-in @poll-captured [:opts :headers "Content-Type"]))
          (sut/refresh-tokens! descriptor "rt-stored")
          (should= "application/x-www-form-urlencoded"
                   (get-in @poll-captured [:opts :headers "Content-Type"])))))

    (it "returns error on non-pending failure"
      (with-redefs [sut/-post-json! (fn [_url _headers _body]
                                      {:error :api-error :status 500})]
        (let [result (sut/poll-for-auth! sut/chatgpt-descriptor "dauth-123" "CODE" 0)]
          (should= :api-error (:error result)))))

    (it "times out after repeated pending responses"
      (let [calls (atom 0)]
        (with-redefs [sut/-post-json! (fn [_url _headers _body]
                                        (swap! calls inc)
                                        {:error :pending :status 403})
                      sut/sleep!     (fn [_] nil)]
          (let [result (sut/poll-for-auth! sut/chatgpt-descriptor "dauth-123" "CODE" sut/poll-timeout-ms)]
            (should= :timeout (:error result))
            (should= 2 @calls)))))

    (it "sleeps before polling when interval is positive"
      (let [slept (atom [])]
        (with-redefs [sut/-post-json! (fn [_url _headers _body]
                                        {:authorization_code "auth-code" :code_verifier "verifier"})
                      sut/sleep!     (fn [interval] (swap! slept conj interval))]
          (sut/poll-for-auth! sut/chatgpt-descriptor "dauth-123" "CODE" 5000)
          (should= [5000] @slept)))))

  (describe "-post-form!"

    (it "posts form-encoded data and parses success"
      (let [captured (atom nil)]
        (with-redefs [http/post (fn [url opts]
                                  (reset! captured {:url url :opts opts})
                                  {:status 200 :body (json/generate-string {:access_token "ok"})})]
          (let [result (sut/-post-form! "https://example.test" {"grant_type" "authorization_code"
                                                                 "code"       "my code"})]
            (should= "ok" (:access_token result))
            (should= "https://example.test" (:url @captured))
            (should= "application/x-www-form-urlencoded" (get-in @captured [:opts :headers "Content-Type"]))
            (should-contain "grant_type=authorization_code" (get-in @captured [:opts :body]))
            (should-contain "code=my+code" (get-in @captured [:opts :body]))))))

    (it "returns api-error on failure"
      (with-redefs [http/post (fn [_ _]
                                {:status 400 :body (json/generate-string {:error "bad"})})]
        (let [result (sut/-post-form! "https://example.test" {"x" "y"})]
          (should= :api-error (:error result))
          (should= 400 (:status result)))))

    (it "returns unknown on exception"
      (with-redefs [http/post (fn [_ _] (throw (ex-info "nope" {})))]
        (let [result (sut/-post-form! "https://example.test" {"x" "y"})]
          (should= :unknown (:error result))
          (should-contain "nope" (:message result))))))

  (describe "exchange-tokens!"

    (it "returns tokens on success"
      (with-redefs [sut/-post-form! (fn [_url _body]
                                      {:access_token  "at-final"
                                       :refresh_token "rt-final"
                                       :id_token      "id-final"
                                       :expires_in    86400})]
        (let [result (sut/exchange-tokens! sut/chatgpt-descriptor "auth-code" "verifier")]
          (should= "at-final" (:access_token result))
          (should= "rt-final" (:refresh_token result))
          (should= 86400 (:expires_in result)))))

    (it "passes correct URL and form params"
      (let [captured (atom nil)]
        (with-redefs [sut/-post-form! (fn [url body]
                                        (reset! captured {:url url :body body})
                                        {:access_token "x" :refresh_token "y" :expires_in 1})]
          (sut/exchange-tokens! sut/chatgpt-descriptor "my-auth-code" "my-verifier")
          (should= "https://auth.openai.com/oauth/token" (:url @captured))
          (let [body (:body @captured)]
            (should= "authorization_code" (get body "grant_type"))
            (should= "app_EMoamEEZ73f0CkXaXp7hrann" (get body "client_id"))
            (should= "my-auth-code" (get body "code"))
            (should= "my-verifier" (get body "code_verifier"))
            (should= "https://auth.openai.com/deviceauth/callback" (get body "redirect_uri"))))))

    (it "returns error on failure"
      (with-redefs [sut/-post-form! (fn [_url _body]
                                      {:error :api-error :status 400})]
        (let [result (sut/exchange-tokens! sut/chatgpt-descriptor "bad-code" "bad-verifier")]
          (should= :api-error (:error result))))))

  (describe "refresh-tokens!"

    (it "returns tokens on success"
      (with-redefs [sut/-post-form! (fn [_url _body]
                                      {:access_token  "at-refreshed"
                                       :refresh_token "rt-rotated"
                                       :expires_in    3600})]
        (let [result (sut/refresh-tokens! sut/chatgpt-descriptor "rt-stored")]
          (should= "at-refreshed" (:access_token result))
          (should= "rt-rotated" (:refresh_token result)))))

    (it "passes correct URL and form params"
      (let [captured (atom nil)]
        (with-redefs [sut/-post-form! (fn [url body]
                                        (reset! captured {:url url :body body})
                                        {:access_token "x" :expires_in 1})]
          (sut/refresh-tokens! sut/chatgpt-descriptor "my-refresh")
          (should= "https://auth.openai.com/oauth/token" (:url @captured))
          (let [body (:body @captured)]
            (should= "refresh_token" (get body "grant_type"))
            (should= "app_EMoamEEZ73f0CkXaXp7hrann" (get body "client_id"))
            (should= "my-refresh" (get body "refresh_token"))))))

    (it "supports a descriptor-driven refresh endpoint"
      (let [captured   (atom nil)
            descriptor {:issuer           "https://auth.x.ai"
                        :token-path       "/oauth2/token"
                        :client-id        "grok-client"
                        :verification-url "https://accounts.x.ai/oauth2/device"}]
        (with-redefs [sut/-post-form! (fn [url body]
                                        (reset! captured {:url url :body body})
                                        {:access_token "x" :refresh_token "rt-2" :expires_in 1})]
          (sut/refresh-tokens! descriptor "my-refresh")
          (should= "https://auth.x.ai/oauth2/token" (:url @captured))
          (should= "grok-client" (get-in @captured [:body "client_id"])))) )

    (it "returns error on failure"
      (with-redefs [sut/-post-form! (fn [_url _body]
                                      {:error :api-error :status 400})]
        (let [result (sut/refresh-tokens! sut/chatgpt-descriptor "bad-refresh")]
          (should= :api-error (:error result))))))

  (describe "exchange-api-key!"

    (it "exchanges id_token for an api-style access token"
      (let [captured (atom nil)]
        (with-redefs [sut/-post-form! (fn [url body]
                                        (reset! captured {:url url :body body})
                                        {:access_token "sk-oauth-backed"})]
          (let [result (sut/exchange-api-key! sut/chatgpt-descriptor "id-token-123")]
            (should= "sk-oauth-backed" (:access_token result))
            (should= "https://auth.openai.com/oauth/token" (:url @captured))
            (should= "urn:ietf:params:oauth:grant-type:token-exchange" (get-in @captured [:body "grant_type"]))
            (should= "openai-api-key" (get-in @captured [:body "requested_token"]))
            (should= "id-token-123" (get-in @captured [:body "subject_token"]))
            (should= "urn:ietf:params:oauth:token-type:id_token" (get-in @captured [:body "subject_token_type"]))))))

  (describe "verification-url"

    (it "returns the device verification URL from the descriptor"
      (should= "https://auth.openai.com/codex/device" (sut/verification-url sut/chatgpt-descriptor))))))
