(ns isaac.llm.providers-steps
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.config.loader :as loader]
    [isaac.step-tables :as match]
    [isaac.session.session-steps :as session-steps]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.http :as llm-http]
    [isaac.session.store.spi :as store]
    [isaac.session.store.sidecar :as sidecar-store]
    [isaac.nexus :as nexus]))

(helper! isaac.llm.providers-steps)

;; region ----- Helpers -----

(defn- root [] (g/get :root))

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (mem-fs)}
    (f)))

(defn- session-store []
  (or (store/registered-store)
      (sidecar-store/create-store (root))))

(defn- current-key []
  (or (g/get :current-key)
      (:key (first (with-feature-fs #(store/list-sessions-by-agent (session-store) "main"))))))

(defn- resolve-env-value [value]
  (if (string? value)
    (str/replace value #"\$\{([^}]+)\}" (fn [[_ var-name]] (or (loader/env var-name) "")))
    value))

(defn- missing-auth-hint [provider]
  (case provider
    "anthropic"   "ANTHROPIC_API_KEY"
    "openai"      "OPENAI_API_KEY"
    "chatgpt" "isaac auth login --provider chatgpt"
    "grok"        "GROK_API_KEY"
    provider))

(defn- clear-access-error? [provider result]
  (let [message (or (get-in result [:body :error :message])
                    (get-in result [:body :detail])
                    "")]
    (case provider
      "openai" (or (= "insufficient_quota" (get-in result [:body :error :code]))
                    (str/includes? message "quota"))
      false)))

(defn- assoc-hyphenated-header [headers name value]
  (let [segments (str/split name #"-")]
    (assoc-in headers (mapv keyword segments) value)))

(defn- provider-config-key [key]
  (keyword key))

(defn- ->index [value]
  (cond
    (nil? value) nil
    (integer? value) value
    (string? value) (parse-long value)
    :else (parse-long (str value))))

(defn- request-for-match [request]
  (update request :headers
          (fn [headers]
            (reduce (fn [acc [name value]]
                      (cond-> acc
                        true                          (assoc (keyword name) value)
                        (str/includes? name "-")     (assoc-hyphenated-header name value)))
                    {}
                    headers))))

(defn- current-provider-request []
  (or (g/get :provider-request)
       (grover/last-provider-request)))

(defn- current-outbound-http-request []
  (or (g/get :outbound-http-request)
      (current-provider-request)))

(defn- outbound-http-requests []
  (or (g/get :outbound-http-requests)
      (when-let [request (current-provider-request)]
        [request])))

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Given -----

(defn provider-configured [provider-name table]
  (let [config (into {} (map (fn [row]
                                  (let [m (zipmap (:headers table) row)]
                                    [(provider-config-key (get m "key")) (resolve-env-value (get m "value"))]))
                                (:rows table)))]
    (g/update! :provider-configs
               (fn [m] (assoc (or m {}) provider-name config)))))

(defn provider-transport-returns-connection-refused []
  (g/assoc! :llm-http-stub :connection-refused))

(defn provider-transport-succeeds-immediately []
  (g/assoc! :llm-http-stub :success))

(defn outbound-http-request-matches [table]
  (session-steps/await-turn!)
  (let [rows      (or (:rows table) [])
        idx-row   (some #(when (= "#index" (first %)) %) rows)
        idx       (some-> idx-row second ->index)
        rows'     (vec (remove #(= "#index" (first %)) rows))
        raw-request (if (some? idx)
                      (nth (outbound-http-requests) idx nil)
                      (current-outbound-http-request))
        request    (request-for-match raw-request)
        result     (match/match-object {:rows rows'} request)]
    (g/assoc! :outbound-http-request raw-request)
    (g/should= [] (:failures result))))

(defn outbound-http-request-to-url-matches [url table]
  (session-steps/await-turn!)
  (let [requests  (->> (outbound-http-requests)
                       (filter #(= url (:url %)))
                       (mapv request-for-match))
        rows      (cond->> (or (:rows table) [])
                    (and (= 2 (count (:headers table)))
                         (not (#{{"key" "value"} {"path" "value"}}
                               (set (:headers table)))))
                    (cons (:headers table)))
        idx-row   (some #(when (= "#index" (first %)) %) rows)
        idx       (some-> idx-row second ->index)
        rows'     (vec (remove #(= "#index" (first %)) rows))
        request   (nth requests (or idx 0) nil)
        result    (match/match-object {:rows rows'} request)]
    (g/assoc! :outbound-http-request request)
    (g/should= [] (:failures result))))

(defn provider-request-lacks-path [path]
  (session-steps/await-turn!)
  (let [request (request-for-match (current-outbound-http-request))]
    (g/should= nil (match/get-path request path))))

(defn auth-failed []
  (session-steps/await-turn!)
  (let [result (g/get :llm-result)]
    (g/should (or (= :auth-failed (:error result))
                  (= 401 (:status result))
                  (and (= :api-error (:error result))
                       (some? (:status result))
                       (>= (:status result) 400))))))

(defn live-call-or-auth-missing [provider]
  (session-steps/await-turn!)
  (let [result (g/get :llm-result)]
    (if (= :auth-missing (:error result))
      (let [message (or (:message result) "")]
        (g/should (str/includes? message (missing-auth-hint provider))))
      (if (clear-access-error? provider result)
        (let [message (or (get-in result [:body :error :message])
                          (get-in result [:body :detail])
                          "")]
          (g/should (str/includes? message "quota")))
        (let [mem-fs     (g/get :mem-fs)
              transcript (if mem-fs
                            (nexus/-with-nested-nexus {:fs mem-fs}
                              (store/get-transcript (session-store) (current-key)))
                            (with-feature-fs #(store/get-transcript (session-store) (current-key))))
              assistant  (last (filter #(= "assistant" (get-in % [:message :role])) transcript))]
          (g/should-not (:error result))
          (g/should-not-be-nil assistant)
          (g/should= provider (get-in assistant [:message :provider])))))))

;; region ----- Routing -----

(defgiven "the provider {name:string} is configured with:" isaac.llm.providers-steps/provider-configured
  "Writes a provider config into the :provider-configs test atom (not
   disk). Keys are converted kebab→camel via provider-config-key; values
   have ${VAR} substitution applied. Passed as extra-opts to the next
   'isaac is run with'.")

(defgiven "provider transport returns connection refused" isaac.llm.providers-steps/provider-transport-returns-connection-refused
  "Stubs the LLM HTTP layer so provider calls fail immediately with the
   same :connection-refused result shape as a real transport failure.")

(defgiven "provider transport succeeds immediately" isaac.llm.providers-steps/provider-transport-succeeds-immediately
  "Stubs the LLM HTTP layer so provider calls return a minimal successful
   response immediately while still capturing the outbound request.")

(defn outbound-http-request-n-matches [n table]
  (session-steps/await-turn!)
  (let [idx       (dec (long (if (string? n) (parse-long n) n)))
        requests  (vec (outbound-http-requests))
        raw       (nth requests idx nil)
        request   (request-for-match raw)
        result    (match/match-object table request)]
    (g/assoc! :outbound-http-request raw)
    (g/should= [] (:failures result))))

(defn outbound-http-request-n-lacks-path [n path]
  (session-steps/await-turn!)
  (let [idx     (dec (long (if (string? n) (parse-long n) n)))
        request (request-for-match (nth (vec (outbound-http-requests)) idx nil))]
    (g/should= nil (match/get-path request path))))

(defthen "the last outbound HTTP request matches:" isaac.llm.providers-steps/outbound-http-request-matches
  "Awaits the turn future, then matches the last HTTP request Isaac
   sent to any provider. Table uses the match/match-object DSL (dot-path
   in 'key' column).")

(defthen "an outbound HTTP request to {url:string} matches:" isaac.llm.providers-steps/outbound-http-request-to-url-matches
  "Filters captured HTTP requests by url, then matches the nth (default
   first). Use a row with key='#index' to select a different position.")

(defthen #"outbound HTTP request (\d+) matches:" isaac.llm.providers-steps/outbound-http-request-n-matches
  "1-based indexed outbound HTTP request match (body/store/previous_response_id etc.).")

(defthen #"outbound HTTP request (\d+) has no ([^\s]+)" isaac.llm.providers-steps/outbound-http-request-n-lacks-path
  "1-based indexed absence assertion for an outbound HTTP request path.")

(defthen "the last provider request does not contain path {path:string}" isaac.llm.providers-steps/provider-request-lacks-path)

(defthen "an error is reported indicating authentication failed" isaac.llm.providers-steps/auth-failed
  "Accepts any of: (:error result) = :auth-failed, HTTP :status 401,
   or :api-error with a 4xx status. Use when the exact error shape
   varies by provider but 'auth failed' is the invariant.")

(defthen "the live {provider:string} call succeeds or reports missing auth clearly" isaac.llm.providers-steps/live-call-or-auth-missing
  "For integration/live tests. Passes if the provider call succeeded OR
   produced a missing-auth / access-error message that names the right
   env var / credential path. Avoids failing the suite just because
   credentials are absent in CI.")

;; endregion ^^^^^ Routing ^^^^^
