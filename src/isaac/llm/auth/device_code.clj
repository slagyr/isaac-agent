;; mutation-tested: 2026-05-06
(ns isaac.llm.auth.device-code
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.string :as str]))

;; region ----- Constants -----

(def poll-timeout-ms (* 15 60 1000))

(def chatgpt-descriptor
  {:issuer               "https://auth.openai.com"
   :client-id            "app_EMoamEEZ73f0CkXaXp7hrann"
   :device-path          "/api/accounts/deviceauth/usercode"
   :poll-path            "/api/accounts/deviceauth/token"
   :token-path           "/oauth/token"
   :redirect-uri         "https://auth.openai.com/deviceauth/callback"
   :verification-url     "https://auth.openai.com/codex/device"
   :flow                 :openai-device-auth
   :originator           "isaac"
   :chatgpt-account-id?  true
   :token-exchange       {:requested-token    "openai-api-key"
                          :subject-token-type "urn:ietf:params:oauth:token-type:id_token"}})

(def grok-descriptor
  {:issuer           "https://auth.x.ai"
   :client-id        "b1a00492-073a-47ea-816f-4c329264a828"
   :device-path      "/oauth2/device/code"
   :token-path       "/oauth2/token"
   :verification-url "https://accounts.x.ai/oauth2/device"
   :flow             :oidc-device-code})

;; endregion ^^^^^ Constants ^^^^^

;; region ----- HTTP Helpers -----

(defn- parse-response [resp error-fn]
  (let [parsed (json/parse-string (:body resp) true)]
    (if (>= (:status resp) 400)
      {:error  (error-fn (:status resp))
       :status (:status resp)
       :body   parsed}
      parsed)))

(defn- json-request-opts [headers body]
  {:body    (json/generate-string body)
   :headers (merge {"Content-Type" "application/json"
                    "Accept"       "application/json"}
                   headers)
   :timeout 30000
   :throw   false})

(defn- encode-form-body [body]
  (->> body
       (map (fn [[k v]] (str (java.net.URLEncoder/encode (str k) "UTF-8")
                             "="
                             (java.net.URLEncoder/encode (str v) "UTF-8"))))
       (str/join "&")))

(defn- form-request-opts [body]
  {:body    (encode-form-body body)
   :headers {"Content-Type" "application/x-www-form-urlencoded"}
   :timeout 30000
   :throw   false})

(defn- pending-error? [result]
  (= :pending (:error result)))

(defn sleep! [interval-ms]
  (when (pos? interval-ms)
    (Thread/sleep interval-ms)))

(defn- next-elapsed [elapsed interval-ms]
  (+ elapsed (max interval-ms 1)))

(defn- timed-out? [elapsed]
  (>= elapsed poll-timeout-ms))

(defn- timeout-result []
  {:error :timeout :message "Device code expired after 15 minutes"})

(defn -post-json!
  "POST JSON and return parsed response. Seam for testing."
  [url headers body]
  (try
    (let [resp (http/post url (json-request-opts headers body))]
      (parse-response resp #(if (#{403 404 428} %) :pending :api-error)))
    (catch Exception e
      {:error :unknown :message (.getMessage e)})))

(defn -post-form!
  "POST form-encoded data and return parsed JSON response. Seam for testing."
  [url body]
  (try
    (let [resp (http/post url (form-request-opts body))]
      (parse-response resp (constantly :api-error)))
    (catch Exception e
      {:error :unknown :message (.getMessage e)})))

;; endregion ^^^^^ HTTP Helpers ^^^^^

;; region ----- Descriptor Helpers -----

(defn verification-url [descriptor]
  (or (:verification-url descriptor)
      (:verification_uri descriptor)
      (:verification_uri_complete descriptor)))

(defn- oauth-flow [descriptor]
  (or (:flow descriptor)
      (when (:poll-path descriptor) :openai-device-auth)
      :oidc-device-code))

(defn- join-url [base path]
  (str (str/replace (or base "") #"/$" "")
       "/"
       (str/replace (or path "") #"^/" "")))

(defn- template-oauth-descriptor [provider-name]
  (let [template (try
                   ((requiring-resolve 'isaac.llm.providers/template) provider-name)
                   (catch Exception _ nil))]
    (or (:oauth template)
        (case provider-name
          "chatgpt" chatgpt-descriptor
          "grok"    grok-descriptor
          nil))))

(defn provider-descriptor! [provider]
  (or (cond
        (and (map? provider) (:oauth provider)) (:oauth provider)
        (and (map? provider) (:issuer provider) (:client-id provider)) provider
        (or (= provider "chatgpt") (= provider :chatgpt)) chatgpt-descriptor
        (or (= provider "grok") (= provider :grok)) grok-descriptor
        (or (string? provider) (keyword? provider)) (template-oauth-descriptor (name provider))
        :else nil)
      (throw (ex-info (str "Unknown oauth provider descriptor: " provider)
                      {:provider provider}))))

(defn- device-code-url [descriptor]
  (join-url (:issuer descriptor) (:device-path descriptor)))

(defn- poll-url [descriptor]
  (join-url (:issuer descriptor) (or (:poll-path descriptor) (:token-path descriptor))))

(defn- token-url [descriptor]
  (join-url (:issuer descriptor) (:token-path descriptor)))

(defn- redirect-uri [descriptor]
  (or (:redirect-uri descriptor)
      (join-url (:issuer descriptor) "/deviceauth/callback")))

(defn- poll-body [descriptor device-id user-code]
  (case (oauth-flow descriptor)
    :oidc-device-code
    {"grant_type"  "urn:ietf:params:oauth:grant-type:device_code"
     "client_id"   (:client-id descriptor)
     "device_code" device-id}

    {"device_auth_id" device-id
     "user_code"      user-code}))

;; endregion ^^^^^ Descriptor Helpers ^^^^^

;; region ----- Device Code Flow -----

(defn request-user-code!
  "Step 1: Request a device code and user code from the provider descriptor."
  [descriptor]
  (let [descriptor (provider-descriptor! descriptor)]
    (-post-json! (device-code-url descriptor)
                 {}
                 {"client_id" (:client-id descriptor)})))

(defn poll-for-auth!
  "Step 2: Poll for authorization. Returns auth code response or error.
   interval-ms is the polling interval in milliseconds (0 for tests)."
  [descriptor device-id user-code interval-ms]
  (let [descriptor (provider-descriptor! descriptor)
        url        (poll-url descriptor)
        body       (poll-body descriptor device-id user-code)]
    (loop [elapsed 0]
      (sleep! interval-ms)
      (let [result (-post-json! url {} body)]
        (cond
          (not (:error result))   result
          (pending-error? result) (if (timed-out? elapsed)
                                    (timeout-result)
                                    (recur (next-elapsed elapsed interval-ms)))
          :else                   result)))))

(defn exchange-tokens!
  "Step 3: Exchange authorization code for access/refresh tokens."
  [descriptor authorization-code code-verifier]
  (let [descriptor (provider-descriptor! descriptor)]
    (-post-form! (token-url descriptor)
                 {"grant_type"    "authorization_code"
                  "client_id"     (:client-id descriptor)
                  "code"          authorization-code
                  "code_verifier" code-verifier
                  "redirect_uri"  (redirect-uri descriptor)})))

(defn refresh-tokens!
  "Exchange a refresh token for a new access token (and optional refresh)."
  [descriptor refresh-token]
  (let [descriptor (provider-descriptor! descriptor)]
    (-post-form! (token-url descriptor)
                 {"grant_type"    "refresh_token"
                  "client_id"     (:client-id descriptor)
                  "refresh_token" refresh-token})))

(defn exchange-api-key!
  "Step 4: Exchange the id_token for an API-style bearer token when supported."
  [descriptor id-token]
  (let [descriptor     (provider-descriptor! descriptor)
        token-exchange (:token-exchange descriptor)]
    (-post-form! (token-url descriptor)
                 {"grant_type"         "urn:ietf:params:oauth:grant-type:token-exchange"
                  "client_id"          (:client-id descriptor)
                  "requested_token"    (or (:requested-token token-exchange) "openai-api-key")
                  "subject_token"      id-token
                  "subject_token_type" (or (:subject-token-type token-exchange)
                                            "urn:ietf:params:oauth:token-type:id_token")})))

;; endregion ^^^^^ Device Code Flow ^^^^^
