(ns isaac.comm.comm-steps
  (:require
    [clojure.edn :as edn]
     [clojure.string :as str]
    [gherclj.core :as g :refer [defthen defwhen helper!]]
    [isaac.bridge.core :as bridge]
    [isaac.comm.memory :as memory-comm]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.config.resolve :as resolve]
    [isaac.drive.dispatch :as drive-dispatch]
    [isaac.llm.provider :as llm-provider]
    [isaac.step-tables :as match]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.http :as llm-http]
    [isaac.session.store.spi :as store]
    [isaac.session.store.sidecar :as sidecar-store]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as memory]))

(helper! isaac.comm.comm-steps)

(defn- root []
  (g/get :root))

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (mem-fs)}
    (f)))

(defn- session-store []
  (or (store/registered-store)
      (sidecar-store/create-store (root))))

(defn- get-session [session-key]
  (store/get-session (session-store) session-key))

(defn- with-current-time [f]
  (if-let [current-time (g/get :current-time)]
    (binding [memory/*now* current-time]
      (f))
    (f)))

(defn- stub-http-success-response [url body]
  (cond
    (str/ends-with? url "/chat/completions")
    {:choices [{:message {:role "assistant" :content "stubbed"}}]
     :model   (:model body)
     :usage   {:prompt_tokens 0 :completion_tokens 0}}

    (str/ends-with? url "/v1/messages")
    {:content [{:type "text" :text "stubbed"}]
     :model   (:model body)
     :usage   {:input_tokens 0 :output_tokens 0}}

    (str/ends-with? url "/responses")
    {:output [{:type "message"
               :content [{:type "output_text" :text "stubbed"}]}]
     :model  (:model body)
     :usage  {:input_tokens 0 :output_tokens 0}}

    :else
    {:message {:role "assistant" :content "stubbed"}
     :model   (:model body)
     :usage   {:input_tokens 0 :output_tokens 0}}))

(defn- connection-refused [url]
  {:error :connection-refused :message (str "Could not connect to " url)})

(defn- with-llm-http-stub [captured* f]
  (case (g/get :llm-http-stub)
    :success
    (with-redefs [llm-http/post-json! (fn [url headers body & _]
                                        (swap! captured* conj {:body body :headers headers :stream false :url url})
                                        (stub-http-success-response url body))
                  llm-http/post-sse! (fn [url headers body on-chunk process-event initial & _]
                                       (swap! captured* conj {:body body :headers headers :stream true :url url})
                                       (let [result (stub-http-success-response url body)]
                                         (on-chunk result)
                                         (process-event result initial)))
                  llm-http/post-ndjson-stream! (fn [url headers body on-chunk & _]
                                                 (swap! captured* conj {:body body :headers headers :stream true :url url})
                                                 (let [chunk {:message {:content "stubbed"} :done true}]
                                                   (on-chunk chunk)
                                                   chunk))]
      (f))

    :connection-refused
    (with-redefs [llm-http/post-json! (fn [url headers body & _]
                                        (swap! captured* conj {:body body :headers headers :stream false :url url})
                                        (connection-refused url))
                  llm-http/post-sse! (fn [url headers body _on-chunk _process-event _initial & _]
                                       (swap! captured* conj {:body body :headers headers :stream true :url url})
                                       (connection-refused url))
                  llm-http/post-ndjson-stream! (fn [url headers body _on-chunk & _]
                                                 (swap! captured* conj {:body body :headers headers :stream true :url url})
                                                 (connection-refused url))]
      (f))

    (f)))

(defn- channel-send-opts [key-str channel]
  (let [cfg        (with-feature-fs #(:config (loader/load-config-result {:root (root) :fs (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))})))
        agents     (or (:crew cfg) {})
        models     (:models cfg)
        agent-id   (or (:crew (with-feature-fs #(get-session key-str)))
                       (:agent (with-feature-fs #(get-session key-str)))
                       "main")
        agent-cfg  (get agents agent-id)
        model-id   (:model agent-cfg)
        model-cfg  (or (get models model-id)
                       (when-let [provider-id (:provider agent-cfg)]
                         {:model model-id :provider provider-id}))
        provider   (:provider model-cfg)
        prov-cfg   (merge (or (resolve/resolve-provider cfg provider) {})
                          (or (get (g/get :provider-configs) provider) {})
                          {:module-index (:module-index cfg)})]
    {:model          (:model model-cfg)
     :crew-members   agents
     :models         models
     :soul           (:soul agent-cfg)
     :provider       (when provider (llm-provider/make-provider provider prov-cfg))
     :context-window (:context-window model-cfg)
     :comm           channel}))

(defn user-sends-via-memory-channel [content key-str]
  (grover/clear-provider-requests!)
  (llm-http/clear-outbound-requests!)
  (drive-dispatch/clear-last-request!)
  (let [events            (atom [])
        captured*         (atom [])
        channel           (memory-comm/channel events)
        cfg               (with-feature-fs #(:config (loader/load-config-result {:root (root) :fs (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs))})))
        _                 (with-feature-fs #(store/open-session! (session-store) key-str {}))
        opts              (channel-send-opts key-str channel)
        result            (atom nil)
        output            (with-out-str
                             (with-feature-fs
                               (fn []
                                 (with-current-time
                                   (fn []
                                     (with-llm-http-stub
                                       captured*
                                       (fn []
                                         (try
                                           (config/dangerously-install-config! cfg "spec")
                                           (reset! result (bridge/dispatch! (root)
                                                                            (assoc opts :input content :session-key key-str)))
                                           (catch Exception e
                                             (reset! result {:error :exception :message (.getMessage e)}))))))))))
        outbound-requests (or (seq @captured*)
                              (seq (llm-http/outbound-requests))
                              (seq (grover/provider-requests)))
        outbound-requests (some-> outbound-requests vec)
        grover-request    (some-> (grover/last-request) (hash-map :body))]
    (g/assoc! :current-key key-str)
    (g/assoc! :llm-result @result)
    (g/assoc! :llm-request (or (drive-dispatch/last-request)
                               (grover/last-request)))
    (g/assoc! :provider-request (or (last outbound-requests)
                                    (grover/last-provider-request)
                                    grover-request))
    (g/assoc! :outbound-http-requests outbound-requests)
    (g/assoc! :outbound-http-request (or (first outbound-requests)
                                         (grover/last-provider-request)
                                         grover-request))
    (g/assoc! :memory-comm-events @events)
    (g/assoc! :output output)))

(defn memory-channel-events-match [table]
  (let [events    (mapv (fn [event]
                          (cond-> event
                            (get-in event [:tool :name]) (assoc :tool-name (get-in event [:tool :name]))))
                        (g/get :memory-comm-events))
        expected  (map (fn [row]
                         (into {}
                               (keep (fn [[header value]]
                                       (when (seq value)
                                         [header value]))
                                     (zipmap (:headers table) row))))
                       (:rows table))]
    (loop [remaining events
           expected  expected]
      (if (empty? expected)
        (g/should true)
        (if-let [event (first remaining)]
          (let [row    (mapv #(get (first expected) %) (:headers table))
                result (match/match-entries {:headers (:headers table) :rows [row]} [event])]
            (if (empty? (:failures result))
              (recur (rest remaining) (rest expected))
              (recur (rest remaining) expected)))
          (g/should false))))))


(defn grover-records-zero-provider-requests []
  (g/should= [] (grover/provider-requests)))

(defn- isaac-relative-path [p]
  (str (root) "/" p))

(defn- match-pending-cell [actual expected]
  (let [s (str/trim (str expected))]
    (cond
      (and (str/starts-with? s "contains ")
           (str/includes? s " and "))
       (let [m (re-find #"contains \"([^\"]+)\" and \"([^\"]+)\"" s)]
         (and m (string? actual)
              (str/includes? actual (nth m 1))
              (str/includes? actual (nth m 2))))
      (str/starts-with? s "contains ")
        (let [m (re-find #"contains \"([^\"]+)\"" s)]
         (and m (string? actual) (str/includes? actual (nth m 1))))
      :else
       (= (str expected)
           (cond
             (keyword? actual) (name actual)
             :else (str actual))))))

(defn directory-has-exactly-n-files [dir n-str]
  (with-feature-fs
    (fn []
      (let [fs*   (mem-fs)
            path  (isaac-relative-path dir)
            files (when (fs/exists? fs* path) (fs/children fs* path))]
        (g/should= (parse-long n-str) (count (or files [])))))))


(defn only-file-in-dir-edn-contains [dir table]
  (with-feature-fs
    (fn []
      (let [fs*    (mem-fs)
            path   (isaac-relative-path dir)
            files  (when (fs/exists? fs* path) (vec (fs/children fs* path)))]
        (g/should= 1 (count files))
        (let [record (edn/read-string (fs/slurp fs* (str path "/" (first files))))]
          (doseq [row (:rows table)]
            (let [p (get row "path")
                  v (get row "value")
                  a (get record (keyword p))]
              (g/should (match-pending-cell a v)))))))))


(defwhen "the user sends \"{content:string}\" on session \"{key:string}\" via memory comm" isaac.comm.comm-steps/user-sends-via-memory-channel)

(defthen "grover records zero provider requests" isaac.comm.comm-steps/grover-records-zero-provider-requests)

(defthen #"the directory \"([^\"]+)\" has exactly (\d+) files?" isaac.comm.comm-steps/directory-has-exactly-n-files)

(defthen #"the only file in \"([^\"]+)\" EDN contains:" isaac.comm.comm-steps/only-file-in-dir-edn-contains)

(defthen "the memory comm has events matching:" isaac.comm.comm-steps/memory-channel-events-match
  "Reads :memory-comm-events captured by the preceding 'via memory
   comm' When step. Matches rows as an in-order subsequence — extra
   events between matched rows are allowed.")
