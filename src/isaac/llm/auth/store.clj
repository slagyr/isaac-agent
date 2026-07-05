(ns isaac.llm.auth.store
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.llm.auth.device-code :as device-code]))

(def ^:private refresh-lead-time-ms (* 5 60 1000))

(defonce ^:private refresh-locks* (atom {}))

(defn- refresh-lock [auth-dir provider-name]
  (get-in (swap! refresh-locks* update-in [auth-dir provider-name]
                   (fn [v] (or v (Object.))))
          [auth-dir provider-name]))

(defn- auth-path [auth-dir]
  (str auth-dir "/auth.json"))

(defn- read-auth [auth-dir fs*]
  (let [path (auth-path auth-dir)]
    (if (fs/exists? fs* path)
      (json/parse-string (fs/slurp fs* path) true)
      {})))

(defn- write-auth! [auth-dir data fs*]
  (let [path (auth-path auth-dir)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path (json/generate-string data {:pretty true}))))

(defn save-tokens!
  "Save OAuth tokens for a provider to auth.json in the given directory."
  [auth-dir provider-name tokens fs*]
  (let [existing  (read-auth auth-dir fs*)
        entry     {:type    "oauth"
                    :access  (:access_token tokens)
                    :id-token (:id_token tokens)
                    :refresh (:refresh_token tokens)
                    :expires (+ (System/currentTimeMillis) (* (:expires_in tokens) 1000))}
        updated   (assoc existing (keyword provider-name) entry)]
    (write-auth! auth-dir updated fs*)))

(defn save-api-key!
  "Save API key credentials for a provider to auth.json in the given directory."
  [auth-dir provider-name api-key fs*]
  (let [existing (read-auth auth-dir fs*)
        entry    {:type "api-key" :apiKey api-key}
        updated  (assoc existing (keyword provider-name) entry)]
    (write-auth! auth-dir updated fs*)))

(defn load-tokens
  "Load OAuth tokens for a provider from auth.json. Returns nil if not found."
  [auth-dir provider-name fs*]
  (let [data (read-auth auth-dir fs*)]
    (get data (keyword provider-name))))

(defn token-expired?
  "Check if a token map has expired."
  [tokens]
  (let [expires (:expires tokens)]
    (or (nil? expires)
        (<= expires (System/currentTimeMillis)))))

(defn token-needs-refresh?
  "True when the access token is expired or within the proactive refresh window."
  ([tokens]
   (token-needs-refresh? tokens (System/currentTimeMillis)))
  ([tokens now-ms]
   (let [expires (:expires tokens)]
     (or (nil? expires)
         (<= expires (+ now-ms refresh-lead-time-ms))))))

(defn- refresh-failure [provider-name]
  {:error :refresh-failed
   :message (str "Missing OpenAI ChatGPT login. Run `isaac auth login --provider " provider-name "` first.")})

(defn refresh-oauth-tokens!
  "Refresh OAuth tokens for provider-name when :refresh is present.
   Returns {:tokens ...} on success or {:error ... :message ...} on failure.
   Single-flight per auth-dir + provider via locking."
  ([auth-dir provider-name fs*]
   (refresh-oauth-tokens! auth-dir provider-name fs* {}))
  ([auth-dir provider-name fs* {:keys [force?]}]
   (locking (refresh-lock auth-dir provider-name)
     (let [tokens (load-tokens auth-dir provider-name fs*)]
       (cond
         (or (nil? tokens) (not= "oauth" (:type tokens)))
         (refresh-failure provider-name)

         (and (not force?) (not (token-needs-refresh? tokens)))
         {:tokens tokens}

         (str/blank? (:refresh tokens))
         (refresh-failure provider-name)

         :else
         (let [response (device-code/refresh-tokens! (:refresh tokens))]
           (if (or (:error response) (not (:access_token response)))
             (refresh-failure provider-name)
             (do
               (save-tokens! auth-dir provider-name
                             (cond-> response
                               (not (:refresh_token response))
                               (assoc :refresh_token (:refresh tokens)))
                             fs*)
               {:tokens (load-tokens auth-dir provider-name fs*)}))))))))
