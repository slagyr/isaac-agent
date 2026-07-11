(ns isaac.drive.provider-wall
  (:require
    [clojure.string :as str]
    [isaac.logger :as log]))

(def default-provider-retry-after-ms 1800000)
(def default-provider-auth-retry-ms 300000)

(defn provider-retry-after-ms
  [cfg]
  (or (get-in cfg [:defaults :provider-retry-after-ms])
      default-provider-retry-after-ms))

(defn provider-auth-retry-after-ms
  [cfg]
  (or (get-in cfg [:defaults :provider-auth-retry-ms])
      default-provider-auth-retry-ms))

(defn- wall-message? [message]
  (let [lower (some-> message str/lower-case)]
    (and (seq lower)
         (or (str/includes? lower "usage_limit_reached")
             (str/includes? lower "credit balance")
             (str/includes? lower "insufficient_credit")
             (str/includes? lower "billing hard limit")))))

(defn- response-message [result]
  (or (:message result)
      (let [body       (:body result)
            body-error (:error body)]
        (cond
          (map? body-error) (str (or (:type body-error) (name (:error result)))
                                 ": "
                                 (or (:message body-error) body-error))
          (string? body-error) body-error
          (map? body) (pr-str body)))))

(defn- wall-response? [result]
  (or (= 429 (:status result))
      (wall-message? (response-message result))))

(defn- auth-response? [result]
  (or (= :auth-failed (:error result))
      (contains? #{401 403} (:status result))))

(defn- retry-after-secs [value]
  (cond
    (nil? value) nil
    (number? value) (long value)
    :else (some-> (not-empty (str/trim (str value))) parse-long)))

(defn- retry-after-ms [result default-ms]
  (let [secs (retry-after-secs (or (:retry-after result)
                                   (get-in result [:body :retry_after])
                                   (get-in result [:body :retry-after])))]
    (if (and secs (pos? secs))
      (* secs 1000)
      default-ms)))

(defn- classify-wall
  [result cfg provider]
  (when (wall-response? result)
    (let [retry-ms (retry-after-ms result (provider-retry-after-ms cfg))]
      (log/warn :chat/provider-walled
                :provider provider
                :status (:status result)
                :retry-after-ms retry-ms)
      {:unavailable? true
       :retry-after-ms retry-ms
       :reason       :wall
       :provider     provider})))

(defn- classify-auth
  [result cfg provider]
  (when (auth-response? result)
    (let [retry-ms (provider-auth-retry-after-ms cfg)]
      (log/warn :chat/provider-auth-rejected
                :provider provider
                :status (:status result)
                :retry-after-ms retry-ms)
      (cond-> {:unavailable? true
               :retry-after-ms retry-ms
               :reason       :auth
               :provider     provider}
        (seq (response-message result)) (assoc :message (response-message result))))))

(defn classify
  "Classify provider weather into {:unavailable? true :retry-after-ms N :reason ...}.
   Returns nil when the response is a genuine failure."
  [result cfg provider]
  (or (classify-auth result cfg provider)
      (classify-wall result cfg provider)))

(defn normalize
  "Pass through pre-classified unavailable results; classify auth and wall errors."
  [result cfg provider]
  (cond
    (:unavailable? result)
    (cond-> result
      (nil? (:reason result)) (assoc :reason :wall)
      (nil? (:provider result)) (assoc :provider provider))

    (:error result)
    (or (classify result cfg provider) result)

    :else result))