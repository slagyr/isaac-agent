(ns isaac.agent.quota
  "Subscription quota reports for configured provider instances."
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.string :as str]
    [c3kit.apron.schema :as schema]
    [isaac.agent.llm.api.openai.shared :as oauth]
    [isaac.agent.llm.providers :as providers]
    [isaac.foundation.config.loader :as loader]
    [isaac.foundation.config.root :as root]
    [isaac.foundation.cli.host :as host]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.module.loader :as module-loader]))

(def ^:dynamic *now* nil)
(defonce ^:private cache* (atom {}))
(defn clear-cache! [] (reset! cache* {}))
(defn- now [] (or *now* (java.time.Instant/now)))
(defn- date [instant] (java.util.Date/from instant))
(defn- epoch-date [seconds] (date (java.time.Instant/ofEpochSecond (long seconds))))
(defn- iso-date [s] (date (.toInstant (java.time.OffsetDateTime/parse s))))
(defn- label [seconds]
  (if (< seconds 172800)
    (str (quot seconds 3600) "-hour")
    (str (quot seconds 86400) "-day")))

(def window-schema
  {:id {:type :keyword :validate some?}
   :label {:type :string :validate some?}
   :used-percent {:type :double :validations [{:validate #(<= 0 % 100) :message "must be between 0 and 100"}]}
   :resets-at {:type :instant :validate some?}
   :starts-at {:type :instant}
   :length-seconds {:type :long}
   :limited? {:type :boolean}
   :scope {:type :any}
   :used {:type :double} :limit {:type :double} :unit {:type :keyword}})
(def balance-schema
  {:id {:type :keyword :validate some?}
   :amount {:type :double :validate some?}
   :unit {:type :keyword :validate some?}
   :cap {:type :double} :used {:type :double}})
(def report-schema
  {:provider {:type :string :validate some?}
   :status {:type :keyword :validations [{:validate #{:ok :unknown :unsupported}}]}
   :checked-at {:type :instant :validate some?}
   :plan {:type :string}
   :windows {:type :seq :spec {:type :map :schema window-schema} :validate some?}
   :balances {:type :seq :spec {:type :map :schema balance-schema}}
   :error {:type :string}})

(defn chatgpt-report [usage]
  (let [rate (:rate_limit usage)
        windows (for [[id entry] [[:primary (:primary_window rate)] [:secondary (:secondary_window rate)]]
                      :when entry]
                  (let [seconds (:limit_window_seconds entry)]
                    {:id id :label (label seconds) :used-percent (double (:used_percent entry))
                     :resets-at (epoch-date (:reset_at entry)) :length-seconds seconds
                     :limited? (boolean (or (:limit_reached rate) (false? (:allowed rate))))}))]
    (cond-> {:windows (vec windows)}
      (:plan_type usage) (assoc :plan (:plan_type usage))
      (get-in usage [:credits :balance])
      (assoc :balances [{:id :credits :amount (double (parse-double (str (get-in usage [:credits :balance]))))
                         :unit :credits}]))))

(defn grok-report [usage]
  (let [config (:config usage)
        period (:currentPeriod config)
        start (iso-date (:start period))
        end (iso-date (:end period))
        seconds (quot (- (.getTime end) (.getTime start)) 1000)
        base {:label (label seconds) :starts-at start :resets-at end
              :length-seconds seconds :limited? false}
        windows (into [(assoc base :id :weekly :used-percent (double (or (:creditUsagePercent config) 0)))]
                      (map-indexed (fn [i product]
                                     (assoc base :id (keyword (str "product-" (inc i)))
                                            :used-percent (double (or (:usagePercent product) 0))
                                            :scope {:product (:product product)})))
                      (:productUsage config))]
    (cond-> {:windows windows}
      (get-in config [:prepaidBalance :val])
      (assoc :balances [{:id :prepaid :amount (double (get-in config [:prepaidBalance :val])) :unit :credits}]))))

(defn- get-json! [url headers]
  (let [{:keys [status body]} (http/get url {:headers headers :timeout 15000 :throw false})]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "HTTP " status " from " url) {:status status})))
    (json/parse-string body true)))

(defn- auth-root []
  (or (nexus/get :root) (root/current-root)))

(defn- token! [id config]
  (or (:access (oauth/resolve-oauth-tokens id (assoc config :root (auth-root))))
      (throw (ex-info (str "Missing OAuth login for " id) {}))))

(defn chatgpt-meter [{:keys [id] :as config}]
  (let [tokens (oauth/resolve-oauth-tokens id (assoc config :root (auth-root)))
        token (or (:access tokens) (token! id config))
        account (or (:account-id tokens)
                    (get-in (oauth/decode-jwt-payload (:access tokens))
                            ["https://api.openai.com/auth" "chatgpt_account_id"]))]
    (chatgpt-report (get-json! "https://chatgpt.com/backend-api/wham/usage"
                               (cond-> {"Authorization" (str "Bearer " token)}
                                 account (assoc "chatgpt-account-id" account))))))

(defn grok-meter [{:keys [id] :as config}]
  (grok-report (get-json! "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
                          {"Authorization" (str "Bearer " (token! id config))})))

(defn chatgpt-factory [_] chatgpt-meter)
(defn grok-factory [_] grok-meter)

(defn- meter-for [type module-index]
  (when-let [entry (some #(get-in % [:manifest :isaac.agent/quota-meter (keyword type)])
                         (vals module-index))]
    (when-let [factory (:factory entry)]
      ((var-get (requiring-resolve factory)) entry))))

(defn report [id config meter]
  (let [instant (now)
        cached (get @cache* id)]
    (if (and cached (< (- (.toEpochMilli instant) (:timestamp cached)) 120000))
      (:report cached)
      (let [result (try
                     (if-not meter
                       {:provider id :status :unsupported :checked-at (date instant) :windows []}
                       (schema/conform! report-schema
                                        (merge {:provider id :status :ok :checked-at (date instant) :windows []}
                                               (meter config))))
                     (catch Exception e
                       {:provider id :status :unknown :checked-at (date instant)
                        :windows [] :error (or (.getMessage e) (str e))}))]
        (swap! cache* assoc id {:timestamp (.toEpochMilli instant) :report result})
        result))))

(defn reports
  ([] (reports nil))
  ([selected]
   (let [loaded* (atom nil)
         _ (host/ensure-runtime! {:install! #(reset! loaded* (loader/load-config! (auth-root) (fs/instance) "provider quota"))})
         cfg (or @loaded* (loader/snapshot "provider quota"))

         module-index (merge (module-loader/builtin-index) (:module-index cfg))]
     (mapv (fn [id]
             (let [entry (or (get (:providers cfg) (keyword id)) (get (:providers cfg) id))
                   type (name (or (:type entry) (keyword id)))
                   config (assoc (providers/lookup cfg module-index id) :id id)]
               (report id config (meter-for type module-index))))
           (sort (filter #(or (nil? selected) (= selected %))
                         (map name (keys (:providers cfg)))))))))

(defn- json-key [key]
  (-> (name key) (str/replace #"-" "_") (str/replace #"\?$" "")))
(defn- json-value [value]
  (cond
    (map? value) (into {} (map (fn [[k v]] [(json-key k) (json-value v)])) value)
    (sequential? value) (mapv json-value value)
    (keyword? value) (name value)
    (instance? java.util.Date value) (str (.toInstant ^java.util.Date value))
    :else value))
(defn quota-tool [args]
  {:result (json/generate-string {:providers (json-value (reports (or (get args "provider") (:provider args))))})})
