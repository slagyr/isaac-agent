(ns isaac.agent.tool.tools-steps
  (:require
    [babashka.http-client :as http]
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defwhen defthen helper!]]
    [isaac.agent.bridge.cancellation :as bridge-cancel]
    [isaac.foundation.config.loader :as loader]
    [isaac.agent.step-tables :as match]
    [isaac.foundation.fs :as isaac-fs]
    [isaac.agent.session.session-steps :as session-steps]
    [isaac.agent.session.store.spi :as session-store-proto]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.comm.delivery.queue :as delivery-queue]
    [isaac.agent.tool.builtin :as builtin]
    [isaac.agent.tool.file :as file]
    [isaac.agent.tool.glob :as glob]
    [isaac.agent.tool.grep :as grep]
    [isaac.agent.tool.memory :as memory]
    [isaac.agent.tool.names :as names]
    [isaac.agent.tool.registry :as registry]
    [isaac.agent.tool.web-fetch :as web-fetch]
    [speclj.core :refer [pending]]))

(helper! isaac.agent.tool.tools-steps)

;; region ----- Helpers -----

(defn- root [] (g/get :root))

(defn- feature-fs []
  (or (g/get :mem-fs)
      (nexus/get :fs)
      (isaac-fs/real-fs)))

(defn- ensure-feature-fs! []
  ;; When a scenario installs no in-memory fs of its own, fall back to the
  ;; real filesystem rather than whatever mem-fs a previous feature left
  ;; registered in the process-global nexus. Without this, tool scenarios
  ;; that read real system paths (e.g. /etc/hosts) intermittently failed
  ;; depending on run order.
  (when (nil? (g/get :mem-fs))
    (nexus/deregister! [:fs]))
  (let [fs* (feature-fs)]
    (nexus/register! [:fs] fs*)
    fs*))

(defn- with-feature-fs [f]
  (let [fs* (ensure-feature-fs!)]
    (nexus/-with-nested-nexus {:fs fs*}
      (f))))

(defn- resolve-path [p]
  (if (str/starts-with? p "/")
    p
    (let [root     (root)
          root-name (.getName (io/file root))]
      (if (str/starts-with? p (str root-name "/"))
        (str root "/" (subs p (inc (count root-name))))
        (str root "/" p)))))

(defn- ensure-tool-result-ready! []
  (when (and (nil? (g/get :tool-result))
             (g/get :turn-future))
    (session-steps/await-turn!)))

(defn- result-text []
  (ensure-tool-result-ready!)
  (let [r (g/get :tool-result)]
    (cond
      (map? r) (or (:result r) (:error r) "")
      (string? r) r
      (nil? r) ""
      :else (str r))))

(defn- result-lines []
  (str/split-lines (result-text)))

(defn- parse-tool-value [k v]
  (cond
    (and (string? v) (or (str/starts-with? v "[") (str/starts-with? v "{")))
    (try (edn/read-string v) (catch Exception _ v))

    :else
    (case k
      ("file_path" "path" "workdir") (resolve-path v)
      ("offset" "limit" "timeout" "head_limit" "num_results" "-A" "-B" "-C") (parse-long v)
      ("replace_all" "-i" "-n" "multiline" "reset") (= "true" v)
      v)))

(defn- with-current-time [f]
  (if-let [current-time (g/get :current-time)]
    (binding [memory/*now* current-time]
      (f))
    (f)))

(defn- unquote-string [s]
  (if (and (string? s)
           (<= 2 (count s))
           (str/starts-with? s "\"")
           (str/ends-with? s "\""))
    (subs s 1 (dec (count s)))
    s))

(defn- table-rows [table]
  (mapv #(zipmap (:headers table) %) (:rows table)))

(defn- kv-rows [table]
  (cond-> (:rows table)
    (seq (:headers table)) (conj (:headers table))))

(defn- extract-tool-args [table]
  (let [headers (:headers table)
        rows    (:rows table)]
    (if (and (= 1 (count headers)) (seq rows))
      ;; 1-column format: header is the key; data row cells rejoined with " | " are the value
      ;; (pipe chars in the original Gherkin value get split into extra cells by the parser)
      (let [key (first headers)
            val (str/join " | " (first rows))]
        {key (parse-tool-value key val)})
      ;; Standard k-v format: each row (including header row) is a [key value] pair
      (into {} (map (fn [[k v]] [k (parse-tool-value k v)])
                    (cond-> rows (seq headers) (conj headers)))))))

(defn- ensure-parent! [path]
  (when-let [parent (.getParentFile (io/file path))]
    (.mkdirs parent)))

(defn- generated-content [{:strs [content lines]}]
  (cond
    lines   (str/join "\n" (map #(str "line " %) (range 1 (inc (parse-long lines)))))
    content (str/replace content "\\n" "\n")
    :else   ""))

(defn- strip-quotes [s]
  (if (and (string? s) (str/starts-with? s "\"") (str/ends-with? s "\""))
    (subs s 1 (dec (count s)))
    s))

(defn- tool-default-var [tool-name key]
  (case [(strip-quotes tool-name) key]
    ["glob" "head_limit"] #'glob/*default-head-limit*
    ["fs__glob" "head_limit"] #'glob/*default-head-limit*
    ["read" "limit"] #'file/*default-read-limit*
    ["fs__read" "limit"] #'file/*default-read-limit*
    ["grep" "head_limit"] #'grep/*default-head-limit*
    ["fs__grep" "head_limit"] #'grep/*default-head-limit*
    ["web_fetch" "limit"] #'web-fetch/*default-limit*
    ["web__fetch" "limit"] #'web-fetch/*default-limit*
    nil))

(defn- with-tool-defaults [f]
  (if-let [bindings (seq (g/get :tool-default-bindings))]
    (with-bindings (into {} bindings) (f))
    (f)))

(defn- query-param [url name]
  (when-let [value (some-> (re-find (re-pattern (str "(?:^|[?&])" name "=([^&]+)")) url)
                           second)]
    (java.net.URLDecoder/decode value "UTF-8")))

(defn- merge-url-stub [url f]
  (g/update! :url-stubs
             (fn [stubs]
               (let [stubs (or stubs {})
                     stub  (merge {:status 200 :headers {} :body ""} (get stubs url {}))]
                 (assoc stubs url (f stub))))))

(defn- search-response [query]
  (when-let [results (get (g/get :search-results) query)]
    {:status  200
     :headers {"content-type" "application/json"}
     :body    (json/generate-string {:web {:results results}})}))

(defn- with-tool-config [f]
  ;; web_search config is provided via feature-config-snapshot (the committed
  ;; snapshot), so no loader stub is needed here.
  (f))

(defn- feature-config-snapshot [_reason]
  (let [base (when-let [dir (root)]
               (with-feature-fs
                 #(:config (loader/load-config-result {:root dir
                                                       :fs (or (g/get :mem-fs) (nexus/get :fs) (isaac-fs/real-fs))}))))]
    (cond-> (or base {})
      (g/get :web-search-config) (assoc-in [:tools :web_search] (g/get :web-search-config)))))

(defn- with-http-stubs [f]
  (let [stubs          (g/get :url-stubs)
        search-results (g/get :search-results)]
    (if (or stubs search-results)
      (with-redefs [http/get (fn [url _opts]
                               (or (get stubs url)
                                   (search-response (query-param url "q"))
                                   (throw (ex-info "no stubbed response for URL" {:url url}))))]
        (f))
      (f))))

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Registration -----

(defn builtin-tools-registered []
  (ensure-feature-fs!)
  (registry/clear!)
  (builtin/register-all!))

(defn provider-registered-for-tool [provider tool]
  (g/assoc! :web-search-config {:provider (keyword provider)
                                :api-key  "sk-test"})
  tool)

(defn provider-registered-for-tool-with-schema [provider tool schema-str]
  (let [fields (edn/read-string schema-str)]
    (g/assoc! :web-search-config {:provider (keyword provider)
                                  :api-key  (when (contains? fields :api-key) "sk-test")})
    tool))

(defn web-search-initialized [_tool]
  (ensure-feature-fs!)
  (builtin/register-all! #{:web/search}))

(defn nil-tool-registered [name]
  (registry/register! {:name name :description "Returns nil" :handler (fn [_] nil)}))

(defn stall-tool-registered [name]
  ;; isaac-4g2k: a fixture tool that never returns, so the registry's
  ;; tool-call deadline is what ends the call.
  (registry/register! {:name name :description "Never returns" :handler (fn [_] (deref (promise)))}))

(defn slow-tool-registered [name delay-ms]
  ;; isaac-4g2k: a fixture tool that finishes after a given delay, used to
  ;; prove a crew/tool timeout override outlasts the shorter global default.
  (let [ms (if (number? delay-ms) delay-ms (parse-long delay-ms))]
    (registry/register! {:name        name
                         :description "Returns after a delay"
                         :handler     (fn [_] (Thread/sleep (long ms)) {:result "done"})})))

;; endregion ^^^^^ Registration ^^^^^

;; region ----- File / Directory Setup -----

(defn clean-test-dir [dir]
  (let [abs-dir (if (str/starts-with? dir "/")
                  dir
                  (str (System/getProperty "user.dir") "/" dir))
        f       (io/file abs-dir)
        fs*     (isaac-fs/real-fs)]
    (when (.exists f)
      (doseq [file (reverse (file-seq f))]
        (.delete file)))
    (.mkdirs f)
    (nexus/register! [:fs] fs*)
    (nexus/register! [:root] abs-dir)
    (nexus/deregister! [:sessions])
    (g/assoc! :root abs-dir)
    (g/dissoc! :mem-fs)))

(defn- unescape-content [s]
  (-> s (str/replace "\\\"" "\"") (str/replace "\\n" "\n")))

;; "a file X exists with content Y" / "... content:" moved to
;; isaac.foundation.fs-steps (foundation-grade file fixtures).

(defn file-appended-with [name content]
  (let [path   (resolve-path name)
        actual (unescape-content content)]
    (with-feature-fs
      #(let [fs* (or (g/get :mem-fs) (nexus/get :fs) (isaac-fs/real-fs))]
         (isaac-fs/spit fs* path (str actual "\n") :append true)))))

(defn file-with-lines [name n]
  (let [path  (resolve-path name)
        lines (str/join "\n" (map #(str "line " %) (range 1 (inc (parse-long n)))))]
    (with-feature-fs
      #(let [fs* (or (g/get :mem-fs) (nexus/get :fs) (isaac-fs/real-fs))]
         (isaac-fs/mkdirs fs* (isaac-fs/parent path))
         (isaac-fs/spit   fs* path lines)))))

(defn file-with-log-entries [name n]
  (let [path  (resolve-path name)
        n     (parse-long n)
        lines (->> (range 1 (inc n))
                   (map #(format "{:ts \"2026-05-12T00:%02d:%02dZ\" :level :info :event :e%02d}"
                                 (quot % 60) (mod % 60) %))
                   (str/join "\n"))]
    (with-feature-fs
      #(let [fs* (or (g/get :mem-fs) (nexus/get :fs) (isaac-fs/real-fs))]
         (isaac-fs/mkdirs fs* (isaac-fs/parent path))
         (isaac-fs/spit   fs* path lines)))))

(defn files-exist [table]
  (doseq [row (table-rows table)]
    (let [path (resolve-path (get row "name"))]
      (with-feature-fs
        #(let [fs* (or (g/get :mem-fs) (nexus/get :fs) (isaac-fs/real-fs))]
           (isaac-fs/mkdirs fs* (isaac-fs/parent path))
           (isaac-fs/spit   fs* path (generated-content row))))
      (when (and (nil? (g/get :mem-fs)) (get row "mtime"))
        (.setLastModified (io/file path)
                          (.toEpochMilli (java.time.Instant/parse (get row "mtime"))))))))

(defn binary-file-exists [name]
  (let [path  (resolve-path name)
        bytes (byte-array (map unchecked-byte
                               [0x00 0x01 0x02 0x03 0xFF]))]
    (.mkdirs (.getParentFile (io/file path)))
    (with-open [out (io/output-stream (io/file path))]
      (.write out bytes))))

(declare session-working-directory)

(defn- image-bytes []
  (.decode (java.util.Base64/getDecoder)
           "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9WlJLQAAAABJRU5ErkJggg=="))

(defn- image-fs [delegate paths bytes]
  (reify isaac-fs/Fs
    (-slurp [_ p opts] (isaac-fs/-slurp delegate p opts))
    (-spit [_ p content opts] (isaac-fs/-spit delegate p content opts))
    (-exists? [_ p] (if (contains? paths p) true (isaac-fs/-exists? delegate p)))
    (-file? [_ p] (if (contains? paths p) true (isaac-fs/-file? delegate p)))
    (-dir? [_ p] (isaac-fs/-dir? delegate p))
    (-children [_ p] (isaac-fs/-children delegate p))
    (-cache-token [_] (isaac-fs/-cache-token delegate))
    (-modified [_ p] (isaac-fs/-modified delegate p))
    (-size [_ p] (if (contains? paths p) (alength bytes) (isaac-fs/-size delegate p)))
    (-mkdirs [_ p] (isaac-fs/-mkdirs delegate p))
    (-delete [_ p] (isaac-fs/-delete delegate p))
    (-move [_ from to] (isaac-fs/-move delegate from to))
    (-copy [_ from to] (isaac-fs/-copy delegate from to))
    (-read-bytes [_ p offset length]
      (if (contains? paths p)
        (let [start (min (alength bytes) (max 0 offset))
              end   (min (alength bytes) (+ start (max 0 length)))]
          (java.util.Arrays/copyOfRange bytes start end))
        (isaac-fs/-read-bytes delegate p offset length)))))

(defn- install-image! [paths]
  (let [fs* (image-fs (feature-fs) paths (image-bytes))]
    (g/assoc! :mem-fs fs*)
    (nexus/register! [:fs] fs*)))

(defn image-file-exists [name]
  (let [path (str (System/getProperty "user.dir") "/target/test-state-tools/" name)]
    (install-image! (set [path (str (root) "/" name)]))))

(defn image-in-session-workdir [name]
  (install-image! #{(str (session-working-directory) "/" name)}))

(defn dir-with-files [dir-name files-str]
  (let [dir-path   (resolve-path dir-name)
        _          (.mkdirs (io/file dir-path))
        file-names (mapv second (re-seq #"\"([^\"]+)\"" files-str))]
    (doseq [f file-names]
      (spit (str dir-path "/" f) ""))))

(defn exec-timeout [n]
  (g/assoc! :exec-timeout n))

(defn default-tool-value-is [tool-name key n]
  (if-let [var (tool-default-var (unquote-string tool-name) key)]
    (g/update! :tool-default-bindings #(assoc (or % {}) var n))
    (throw (ex-info "unknown tool default" {:tool tool-name :key key}))))

(defn glob-scan-budget-is [n]
  (g/update! :tool-default-bindings #(assoc (or % {}) #'glob/*scan-entry-budget* n)))

(defn url-responds-with [url table]
  (doseq [[path value] (map (juxt first second) (kv-rows table))]
    (merge-url-stub (unquote-string url)
                    (fn [stub]
                      (cond
                        (= "status" path)
                        (assoc stub :status (parse-long value))

                        (str/starts-with? path "header.")
                        (assoc-in stub [:headers (str/lower-case (subs path 7))] value)

                        :else
                        stub)))))

(defn url-has-body [url body]
  (merge-url-stub (unquote-string url)
                  (fn [stub]
                    (-> stub
                        (assoc :body (str/trim body))
                        (update :headers #(if (contains? % "content-type")
                                            %
                                            (assoc % "content-type" "text/html")))))))

(defn search-query-returns-results [query table]
  (g/assoc! :web-search-config {:provider :brave :api-key "test-brave-api-key"})
  (g/update! :search-results assoc query
             (mapv (fn [row]
                     {:title       (get row "title")
                      :url         (get row "url")
                      :description (get row "description")})
                   (table-rows table))))

(defn brave-api-key-is-set []
  (if-let [api-key (System/getenv "BRAVE_API_KEY")]
    (g/assoc! :web-search-config {:provider :brave :api-key api-key})
    (pending "BRAVE_API_KEY is not set; skipping live web_search integration scenario")))

(defn current-time-is [iso]
  (let [s (if (re-find #"[zZ]|[+-]\d{2}:?\d{2}$" iso) iso (str iso "Z"))]
    (g/assoc! :current-time (java.time.Instant/parse s))))

(defn current-session-is [key]
  (g/assoc! :current-session-key key))

;; endregion ^^^^^ File / Directory Setup ^^^^^

;; region ----- Tool Execution -----

(defn- execute-tool* [name args]
  (with-tool-defaults
    (fn []
      (with-tool-config
        (fn []
          (with-http-stubs
            (fn []
              (with-feature-fs
                (fn []
                  (with-current-time
                    (fn []
                      (with-redefs [loader/snapshot feature-config-snapshot]
                        (registry/execute name args)))))))))))))

(defn- session-store []
  (or (session-store-proto/registered-store)
      (throw (ex-info "no session-store registered" {}))))

(defn- session-working-directory
  "The current (or most recent) session's cwd. Before any session exists -
   a comm that creates its session on the inbound message - the file goes
   where that session will land: the store's default cwd, user.dir."
  []
  (let [store   (session-store)
        session (or (some->> (g/get :current-key) (session-store-proto/get-session store))
                    (session-store-proto/most-recent-session store))]
    (cond
      (nil? session)  (System/getProperty "user.dir")
      (:cwd session)  (:cwd session)
      :else           (throw (ex-info "no session working directory" {:session (:id session)})))))

(defn file-in-session-workdir [name content]
  (with-feature-fs
    #(let [fs*  (feature-fs)
           path (str (session-working-directory) "/" name)]
       (isaac-fs/mkdirs fs* (isaac-fs/parent path))
       (isaac-fs/spit fs* path content))))

(defn- base-tool-args []
  (cond-> {}
    (root)                  (assoc "state_dir" (root))
    (g/get :current-session-key) (assoc "session_key" (g/get :current-session-key))))

(defn tool-executed [name table]
  (ensure-feature-fs!)
  (builtin/register-all!)
  (let [args   (merge (base-tool-args) (extract-tool-args table))
        result (execute-tool* name args)]
    (g/assoc! :tool-result result)))

(defn tool-called [tool-name table]
  (ensure-feature-fs!)
  (registry/clear!)
  (builtin/register-all!)
  (let [timeout  (g/get :exec-timeout)
        all-rows (cond-> (:rows table)
                   (seq (:headers table)) (conj (:headers table)))
        args     (into {} (map (fn [[k v]]
                                 [k (parse-tool-value k v)])
                                all-rows))
        args     (if timeout (assoc args "timeout" timeout) args)
        args     (merge (base-tool-args) args)
        result   (execute-tool* tool-name args)]
    (g/assoc! :tool-result result)))

(defn tool-called-no-args [tool-name]
  (ensure-feature-fs!)
  (registry/clear!)
  (builtin/register-all!)
  (let [result (execute-tool* tool-name (base-tool-args))]
    (g/assoc! :tool-result result)))

(defn tool-executed-for-session [name session-key table]
  (ensure-feature-fs!)
  (builtin/register-all!)
  (let [args    (merge (base-tool-args) (extract-tool-args table) {"session_key" session-key})
        result  (execute-tool* name args)
        store   (session-store)
        _       (when-not (session-store-proto/get-session store session-key)
                  (session-store-proto/open-session! store session-key {:cwd (root)}))
        tc-id   (str (java.util.UUID/randomUUID))
        content (or (:result result) (:error result) "")
        error?  (boolean (:isError result))]
    (session-store-proto/append-message! store session-key
                                         {:role    "assistant"
                                          :content [{:type      "toolCall"
                                                     :id        tc-id
                                                     :name      name
                                                     :arguments args}]})
    (session-store-proto/append-message! store session-key
                                         (cond-> {:role "toolResult" :id tc-id :content content}
                                                 error? (assoc :isError true)))
    (g/assoc! :tool-result result)))

;; endregion ^^^^^ Tool Execution ^^^^^

;; region ----- Assertions -----

(defn tool-result-contains [text]
  (g/should (str/includes? (result-text) text)))

(defn- line-matches? [line needle]
  (if (and (string? needle) (str/starts-with? needle "#\""))
    (:pass? (match/match-object {:rows [["text" needle]]} {:text line}))
    (str/includes? line needle)))

(defn tool-result-lines-match [table]
  (let [lines     (vec (result-lines))
        headers   (:headers table)
        row-maps  (table-rows table)
        has-index? (contains? (set headers) "#index")
        resolve-index (fn [idx]
                        (let [resolved (if (neg? idx) (+ (count lines) idx) idx)]
                          (when (<= 0 resolved (dec (count lines)))
                            resolved)))]
    (if has-index?
      (doseq [row row-maps]
        (let [needle      (or (get row "text") (first (vals row)))
              idx         (parse-long (get row "#index"))
              resolved    (resolve-index idx)
              line        (when (some? resolved) (nth lines resolved nil))]
          (g/should (some? resolved))
          (g/should (line-matches? (or line "") needle))))
      (let [needles (mapv #(or (get % "text") (first (vals %))) row-maps)]
        (loop [needles needles
               from    0]
          (when-let [needle (first needles)]
            (let [idx (first (keep-indexed (fn [i line]
                                             (when (and (<= from i) (line-matches? line needle)) i))
                                           lines))]
              (g/should (some? idx))
              (recur (rest needles) idx))))))))

(defn tool-result-not-contains [text]
  (g/should-not (str/includes? (result-text) text)))

(defn tool-result-not-error []
  ;; isaac-4g2k: a slow-but-successful tool can still be mid-flight when this
  ;; assertion runs; await the turn the same way result-text does so this
  ;; doesn't pass vacuously on a nil :tool-result.
  (ensure-tool-result-ready!)
  (g/should-not (:isError (g/get :tool-result))))

(defn tool-result-is-image [media-type]
  (ensure-tool-result-ready!)
  (let [image (:result (g/get :tool-result))]
    (g/should= "image" (:type image))
    (g/should= media-type (:media-type image))
    (g/should (pos? (:bytes image)))
    (g/should (seq (:data image)))))

(defn tool-result-json-has [table]
  (ensure-tool-result-ready!)
  (let [result (g/get :tool-result)
        parsed (json/parse-string (or (:result result) "{}") true)
        r      (match/match-object table parsed)]
    (g/should= [] (:failures r))))

(defn tool-result-is-error []
  ;; isaac-4g2k: a call that times out past the default/crew deadline can
  ;; still be mid-flight (parked :turn-future) when this assertion runs;
  ;; await it first instead of reading a premature nil :tool-result.
  (ensure-tool-result-ready!)
  (let [result (g/get :tool-result)]
    (g/should (or (:isError result)
                  (and (string? result) (str/starts-with? result "Error:"))))))

(defn- loaded-feature-config []
  (or (g/get :feature-config)
      (let [cfg (:config (loader/load-config-result {:root (root) :fs (feature-fs)}))]
        (g/assoc! :feature-config cfg)
        cfg)))

(defn- record-value [record path-str]
  (if (str/includes? path-str "/")
    (get record (keyword path-str))
    (match/get-path record path-str)))

(defn- actual->str [actual]
  (cond
    (nil? actual)     nil
    (keyword? actual) (name actual)
    :else             (str actual)))

(defn prompt-tool-has-parameters [tool-name table]
  (with-feature-fs
    (fn []
      (let [cfg   (loaded-feature-config)
            _     (loader/set-snapshot! cfg "prompt-tool-has-parameters")
            allow #{(or (names/config-token tool-name) (keyword tool-name))}
            _     (builtin/register-all! allow)
            tool  (first (filter #(= tool-name (:name %))
                                 (registry/tool-definitions allow (:module-index cfg))))
            props (get-in tool [:parameters :properties])
            req   (set (get-in tool [:parameters :required]))]
        (g/should (some? tool))
        (doseq [row (table-rows table)]
          (let [param (get row "param")
                spec  (get props param)]
            (when (nil? spec)
              (throw (ex-info (str "missing parameter: " param) {:param param :props props})))
            (g/should (some? spec))
            (g/should= (get row "type") (:type spec))
            (g/should= (= "true" (get row "required"))
                       (contains? req param))))))))

(defn pending-comm-delivery-matches [table]
  (with-feature-fs
    (fn []
      (let [pending (delivery-queue/list-pending)]
        (g/should (pos? (count pending)))
        (doseq [row (table-rows table)]
          (let [path     (get row "path")
                expected (get row "value")
                actual   (record-value (first pending) path)
                result   (match/match-value expected (actual->str actual))]
            (when-not (:match result)
              (g/should= expected (actual->str actual)))
            (g/should (:match result))))))))

(defn no-pending-comm-deliveries []
  (with-feature-fs
    (fn []
      (g/should= [] (delivery-queue/list-pending)))))

(defn comm-send-tool-result-is-error []
  (tool-result-is-error))

(defn tool-result-indicates-error []
  (g/should (:isError (g/get :tool-result))))

(defn file-has-content [name content]
  (let [path   (resolve-path name)
        actual (with-feature-fs #(isaac-fs/slurp (or (g/get :mem-fs) (nexus/get :fs) (isaac-fs/real-fs)) path))
        expect (str/replace content "\\n" "\n")]
    (g/should= expect actual)))

(defn file-matches [name table]
  (let [path    (resolve-path name)
        needles (mapv #(or (get % "text") (first (vals %))) (table-rows table))
        lines   (vec (str/split-lines (or (with-feature-fs #(isaac-fs/slurp (or (g/get :mem-fs) (nexus/get :fs) (isaac-fs/real-fs)) path)) "")))]
    (loop [needles needles
           from    0]
      (when-let [needle (first needles)]
        (let [idx (first (keep-indexed (fn [i line]
                                         (when (and (<= from i) (str/includes? line needle)) i))
                                       lines))]
          (g/should (some? idx))
          (recur (rest needles) idx))))))

;; endregion ^^^^^ Assertions ^^^^^

;; region ----- Routing -----

(defgiven "the built-in tools are registered" isaac.agent.tool.tools-steps/builtin-tools-registered
  "Clears the tool registry, then registers every built-in tool (read,
   write, edit, exec, grep, glob, web_fetch, web_search, memory_*).
   Required for features that execute tools — most other features
   should skip this unless they actually need to run tools.")

(defgiven #"a tool \"([^\"]+)\" that returns nil is registered" isaac.agent.tool.tools-steps/nil-tool-registered)

(defgiven #"a fixture tool \"([^\"]+)\" that never returns is registered"
  isaac.agent.tool.tools-steps/stall-tool-registered
  "Registers a mock tool whose handler blocks forever — only the registry's
   tool-call deadline (isaac-4g2k) can end the call.")

(defgiven #"a fixture tool \"([^\"]+)\" that returns after (\d+)ms is registered"
  isaac.agent.tool.tools-steps/slow-tool-registered
  "Registers a mock tool whose handler sleeps for the given delay then returns \"done\".")

(defgiven "a clean test directory {dir:string}" isaac.agent.tool.tools-steps/clean-test-dir
  "Wipes the directory on the REAL filesystem and recreates it, then
   binds :root. Use for tool tests that need real files (exec,
   glob with mtimes, etc.) — not compatible with mem-fs.")

(defwhen "the file {name:string} is appended with {content:string}" isaac.agent.tool.tools-steps/file-appended-with)

(defgiven #"a file \"([^\"]+)\" exists with (\d+) lines" isaac.agent.tool.tools-steps/file-with-lines)

(defgiven #"a file \"([^\"]+)\" exists with (\d+) log entries" isaac.agent.tool.tools-steps/file-with-log-entries)

(defgiven "the following files exist:" isaac.agent.tool.tools-steps/files-exist)

(defgiven "a binary file {name:string} exists" isaac.agent.tool.tools-steps/binary-file-exists)
(defgiven "an image file {name:string} exists" isaac.agent.tool.tools-steps/image-file-exists)
(defgiven "an image file {name:string} exists in the session working directory"
  isaac.agent.tool.tools-steps/image-in-session-workdir)

(defgiven "a file {name:string} exists in the session working directory with content {content:string}" isaac.agent.tool.tools-steps/file-in-session-workdir
  "Writes the file under the current session's :cwd (the :current-key
   session, else the most recent one) on the feature filesystem.")

(defgiven #"a directory \"([^\"]+)\" exists with files (.+)" isaac.agent.tool.tools-steps/dir-with-files)

(defgiven "the exec timeout is set to {n:int} milliseconds" isaac.agent.tool.tools-steps/exec-timeout)

(defgiven "the default {string} {word} is {n:int}" isaac.agent.tool.tools-steps/default-tool-value-is)

(defgiven "the glob scan budget is {n:int} entries" isaac.agent.tool.tools-steps/glob-scan-budget-is
  "Binds isaac.agent.tool.glob/*scan-entry-budget* for tool calls in this scenario,
   so a scan can be driven past its entry budget with a handful of files.")

(defgiven "the URL {string} responds with:" isaac.agent.tool.tools-steps/url-responds-with
  "Registers an HTTP stub for the URL. Table rows configure the stubbed
   response: 'status' sets HTTP status; 'header.<name>' sets a header.
   Pair with 'the URL X has body:' to set the body. Applies to web_fetch
   / web_search and any other HTTP-making tool.")

(defgiven "the URL {string} has body:" isaac.agent.tool.tools-steps/url-has-body)

(defgiven #"the search query \"([^\"]+)\" returns results:" isaac.agent.tool.tools-steps/search-query-returns-results)

(defgiven "the BRAVE_API_KEY environment variable is set" isaac.agent.tool.tools-steps/brave-api-key-is-set)

(defgiven "the current session is {key:string}" isaac.agent.tool.tools-steps/current-session-is)

(defgiven "the current time is {iso:string}" isaac.agent.tool.tools-steps/current-time-is
  "Sets :current-time. The tool execution harness binds this as
   memory/*now* so memory_write etc. use the virtual time instead of
   the real clock.")

(defwhen "tool {name:string} is executed with:" isaac.agent.tool.tools-steps/tool-executed
  "Invokes the registered tool with args taken from the table (column
   headers become string keys). Wraps execution in the tool-defaults,
   tool-config, http-stub, feature-fs, and current-time bindings.
   Stores raw result in :tool-result.")

(defwhen "tool {name:string} is executed for session {key:string} with:" isaac.agent.tool.tools-steps/tool-executed-for-session
  "Executes the tool, applies the output cap, creates the session if needed,
   and appends a toolCall + toolResult entry to the session transcript.")

(defwhen "the tool {name:string} is called with:" isaac.agent.tool.tools-steps/tool-called)

(defwhen "the tool {name:string} is called" isaac.agent.tool.tools-steps/tool-called-no-args)

(defthen "the tool result contains {text:string}" isaac.agent.tool.tools-steps/tool-result-contains)

(defthen "the tool result lines match:" isaac.agent.tool.tools-steps/tool-result-lines-match)

(defthen "the tool result does not contain {text:string}" isaac.agent.tool.tools-steps/tool-result-not-contains)

(defthen "the tool result is not an error" isaac.agent.tool.tools-steps/tool-result-not-error)
(defthen "the tool result is an image of type {media-type:string}"
  isaac.agent.tool.tools-steps/tool-result-is-image)

(defthen "the tool result JSON has:" isaac.agent.tool.tools-steps/tool-result-json-has)

(defthen "the tool result is an error" isaac.agent.tool.tools-steps/tool-result-is-error)

(defthen #"the prompt tool \"([^\"]+)\" has parameters:" isaac.agent.tool.tools-steps/prompt-tool-has-parameters)

(defthen "a pending comm delivery matches:" isaac.agent.tool.tools-steps/pending-comm-delivery-matches)

(defthen "there are no pending comm deliveries" isaac.agent.tool.tools-steps/no-pending-comm-deliveries)

(defthen "the comm_send tool result is an error" isaac.agent.tool.tools-steps/comm-send-tool-result-is-error)

(defthen "the tool result should indicate an error" isaac.agent.tool.tools-steps/tool-result-indicates-error)

(defthen "the file {name:string} has content {content:string}" isaac.agent.tool.tools-steps/file-has-content)

(defthen "the file {name:string} matches:" isaac.agent.tool.tools-steps/file-matches)

(defgiven "the {provider:string} provider is registered for {tool:string}" isaac.agent.tool.tools-steps/provider-registered-for-tool)

(defgiven "a {provider:string} provider is registered for {tool:string} with schema:" isaac.agent.tool.tools-steps/provider-registered-for-tool-with-schema)

(defwhen "the {tool:string} tool is initialized" isaac.agent.tool.tools-steps/web-search-initialized)

(defn- tool-completions []
  (or (g/get :tool-completions)
      (let [completions (atom {})]
        (g/assoc! :tool-completions completions)
        completions)))

(defn- completion-signal [tool-name]
  (let [completions (tool-completions)
        existing    (get @completions tool-name)]
    (or existing
        (let [created (promise)]
          (or (get (swap! completions #(if (contains? % tool-name) % (assoc % tool-name created))) tool-name)
              created)))))

(defn- mark-tool-complete! [tool-name]
  (deliver (completion-signal tool-name) true)
  nil)

(defn- allow-mock-tool! [tool-name]
  (let [allow (or (some->> tool-name
                           (re-find #"^([^_]+)__")
                           second)
                  (some-> (names/config-token tool-name)
                          namespace))]
    (when allow
      (session-steps/crew-tool-allow "main" allow))))

(defn- register-mock-tool! [tool]
  (registry/register! (assoc tool :builtin? true)))

(defn streaming-tool-registered [tool-name progress-edn result]
  (let [chunks (edn/read-string progress-edn)]
    (register-mock-tool!
      {:name        tool-name
       :description (str "streaming mock " tool-name)
       :parameters  {:type "object" :properties {}}
       :handler     (fn [args]
                      (let [progress! (or (:progress! args) (get args "progress!"))]
                        (doseq [c chunks]
                          (when progress! (progress! c))))
                      {:result result
                       :after-result! #(mark-tool-complete! tool-name)})})
    (allow-mock-tool! tool-name)
    nil))

(defn failing-tool-registered [tool-name waits-for error]
  (register-mock-tool!
    {:name        tool-name
     :description (str "failing mock " tool-name)
     :parameters  {:type "object" :properties {}}
     :handler     (fn [_args]
                    (deref (completion-signal waits-for) 1000 false)
                    {:isError true
                     :error error
                     :after-result! #(mark-tool-complete! tool-name)})})
  (allow-mock-tool! tool-name)
  nil)

(defn rendezvous-tool-registered [tool-name result n]
  (let [release   (promise)
        in-flight (atom 0)
        target    (long (if (number? n) n (parse-long n)))]
    (register-mock-tool!
      {:name        tool-name
       :description (str "rendezvous mock " tool-name)
       :parameters  {:type "object" :properties {}}
       :handler     (fn [_args]
                      (let [now (swap! in-flight inc)]
                        (when (<= target now)
                          (deliver release true))
                        (let [met? (= true (deref release 1000 false))]
                          (swap! in-flight dec)
                          {:result (if met? result "alone")
                           :after-result! #(mark-tool-complete! tool-name)})))})
    (allow-mock-tool! tool-name)
    nil))

(defn gated-tool-registered [tool-name result other-tool]
  (let [gate (completion-signal other-tool)]
    (register-mock-tool!
      {:name        tool-name
       :description (str "gated mock " tool-name)
       :parameters  {:type "object" :properties {}}
       :handler     (fn [_args]
                      (let [opened? (= true (deref gate 1000 false))]
                        {:result (if opened? result "gate never opened")
                         :after-result! #(mark-tool-complete! tool-name)}))})
    (allow-mock-tool! tool-name)
    nil))

(defn blocking-tool-registered [tool-name]
  (register-mock-tool!
    {:name        tool-name
     :description (str "blocking mock " tool-name)
     :parameters  {:type "object" :properties {}}
     :handler     (fn [args]
                    (let [session-key (or (get args "session_key") (:session_key args))
                          cancelled   (promise)]
                      (bridge-cancel/on-cancel! session-key #(deliver cancelled true))
                      (if (= true (deref cancelled 1000 false))
                        {:error :cancelled}
                        {:result "still running"})))})
  (allow-mock-tool! tool-name)
  nil)

(defgiven #"a streaming tool \"([^\"]+)\" is registered that emits progress (.+) and returns \"([^\"]+)\""
  isaac.agent.tool.tools-steps/streaming-tool-registered
  "Registers a mock tool whose handler calls ctx :progress! for each chunk then returns the given string.")

(defgiven
  #"a failing tool \"([^\"]+)\" waits for tool \"([^\"]+)\" then returns error \"([^\"]+)\""
  isaac.agent.tool.tools-steps/failing-tool-registered
  "Registers a concurrent mock failure whose result waits for the named tool's completion signal.")

(defgiven #"a rendezvous tool \"([^\"]+)\" is registered that returns \"([^\"]+)\" once (\d+) calls are in flight"
  isaac.agent.tool.tools-steps/rendezvous-tool-registered
  "Registers a concurrent mock tool that releases once N calls are simultaneously in flight; otherwise returns 'alone' after the ceiling.")

(defgiven #"a gated tool \"([^\"]+)\" is registered that returns \"([^\"]+)\" once tool \"([^\"]+)\" has completed"
  isaac.agent.tool.tools-steps/gated-tool-registered
  "Registers a mock tool that waits for the named tool to complete, then returns the given string; otherwise returns 'gate never opened' after the ceiling.")

(defgiven #"a blocking tool \"([^\"]+)\" is registered that returns cancelled once the turn is cancelled"
  isaac.agent.tool.tools-steps/blocking-tool-registered
  "Registers a mock tool that waits for bridge cancellation on its session_key and then reports cancellation.")

;; endregion ^^^^^ Routing ^^^^^
