(ns isaac.llm.auth.device-code-spec
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [isaac.llm.auth.device-code :as sut]
    [speclj.core :refer :all]))

(describe "Device Code Auth"

  (with-stubs)

  (describe "constants"

    (it "has the OpenAI client ID"
      (should= "app_EMoamEEZ73f0CkXaXp7hrann" sut/client-id))

    (it "has the auth base URL"
      (should= "https://auth.openai.com" sut/base-url)))

  (describe "request-user-code!"

    (it "returns user code response on success"
      (with-redefs [sut/-post-json! (fn [_url _headers _body]
                                      {:device_auth_id "dauth-123"
                                       :user_code      "ABCD-1234"
                                       :interval       5})]
        (let [result (sut/request-user-code!)]
          (should= "dauth-123" (:device_auth_id result))
          (should= "ABCD-1234" (:user_code result))
          (should= 5 (:interval result)))))

    (it "passes correct URL and client_id"
      (let [captured (atom nil)]
        (with-redefs [sut/-post-json! (fn [url headers body]
                                        (reset! captured {:url url :headers headers :body body})
                                        {:device_auth_id "x" :user_code "Y" :interval 5})]
          (sut/request-user-code!)
          (should= "https://auth.openai.com/api/accounts/deviceauth/usercode" (:url @captured))
          (should= {"client_id" "app_EMoamEEZ73f0CkXaXp7hrann"} (:body @captured)))))

    (it "returns error map on failure"
      (with-redefs [sut/-post-json! (fn [_url _headers _body]
                                      {:error :api-error :status 404})]
        (let [result (sut/request-user-code!)]
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
          (let [result (sut/poll-for-auth! "dauth-123" "ABCD-1234" 0)]
            (should= "auth-code-xyz" (:authorization_code result))
            (should= "verifier-abc" (:code_verifier result))
            (should= 3 @call-count)))))

    (it "passes device_auth_id and user_code in body"
      (let [captured (atom nil)]
        (with-redefs [sut/-post-json! (fn [url _headers body]
                                        (reset! captured {:url url :body body})
                                        {:authorization_code "ac" :code_verifier "cv"})]
          (sut/poll-for-auth! "dauth-999" "CODE-5678" 0)
          (should= "https://auth.openai.com/api/accounts/deviceauth/token" (:url @captured))
          (should= {"device_auth_id" "dauth-999" "user_code" "CODE-5678"} (:body @captured)))))

    (it "returns error on non-pending failure"
      (with-redefs [sut/-post-json! (fn [_url _headers _body]
                                      {:error :api-error :status 500})]
        (let [result (sut/poll-for-auth! "dauth-123" "CODE" 0)]
          (should= :api-error (:error result)))))

    (it "times out after repeated pending responses"
      (let [calls (atom 0)]
        (with-redefs [sut/-post-json! (fn [_url _headers _body]
                                        (swap! calls inc)
                                        {:error :pending :status 403})
                      sut/sleep!     (fn [_] nil)]
          (let [result (sut/poll-for-auth! "dauth-123" "CODE" sut/poll-timeout-ms)]
            (should= :timeout (:error result))
            (should= 2 @calls)))))

    (it "sleeps before polling when interval is positive"
      (let [slept (atom [])]
        (with-redefs [sut/-post-json! (fn [_url _headers _body]
                                        {:authorization_code "auth-code" :code_verifier "verifier"})
                      sut/sleep!     (fn [interval] (swap! slept conj interval))]
          (sut/poll-for-auth! "dauth-123" "CODE" 5000)
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
        (let [result (sut/exchange-tokens! "auth-code" "verifier")]
          (should= "at-final" (:access_token result))
          (should= "rt-final" (:refresh_token result))
          (should= 86400 (:expires_in result)))))

    (it "passes correct URL and form params"
      (let [captured (atom nil)]
        (with-redefs [sut/-post-form! (fn [url body]
                                        (reset! captured {:url url :body body})
                                        {:access_token "x" :refresh_token "y" :expires_in 1})]
          (sut/exchange-tokens! "my-auth-code" "my-verifier")
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
        (let [result (sut/exchange-tokens! "bad-code" "bad-verifier")]
          (should= :api-error (:error result))))))

  (describe "exchange-api-key!"

    (it "exchanges id_token for an api-style access token"
      (let [captured (atom nil)]
        (with-redefs [sut/-post-form! (fn [url body]
                                        (reset! captured {:url url :body body})
                                        {:access_token "sk-oauth-backed"})]
          (let [result (sut/exchange-api-key! "id-token-123")]
            (should= "sk-oauth-backed" (:access_token result))
            (should= "https://auth.openai.com/oauth/token" (:url @captured))
            (should= "urn:ietf:params:oauth:grant-type:token-exchange" (get-in @captured [:body "grant_type"]))
            (should= "openai-api-key" (get-in @captured [:body "requested_token"]))
            (should= "id-token-123" (get-in @captured [:body "subject_token"]))
            (should= "urn:ietf:params:oauth:token-type:id_token" (get-in @captured [:body "subject_token_type"]))))))

  (describe "verification-url"

    (it "returns the device verification URL"
      (should= "https://auth.openai.com/codex/device" sut/verification-url)))))
