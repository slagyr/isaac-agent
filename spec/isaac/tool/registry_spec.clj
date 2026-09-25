(ns isaac.tool.registry-spec
  (:require
    [clojure.string :as str]
    [isaac.config.loader :as loader]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.spec-helper :as helper]
    [isaac.session.spec-helper :as store-helper]
    [isaac.nexus :as nexus]
    [isaac.tool.registry :as sut]
    [speclj.core :refer :all]))

(defn doodad-tool [cfg]
  {:description "Doodad"
   :parameters  {:type "object" :properties {}}
   :handler     (fn [_] {:result (str "api-key=" (:api-key cfg))})})

(def provider-calls* (atom []))

(defn lens-provider
  "A :isaac.agent/tool-providers contributor: registers the lens namespace on demand."
  [ns-str _module-index]
  (swap! provider-calls* conj ns-str)
  (when (= "lens" ns-str)
    (sut/register! {:name "lens__catalog" :description "Catalog" :parameters {} :handler (fn [_] {:result "catalogued"})})
    (sut/register! {:name "lens__read" :description "Read" :parameters {} :handler (fn [_] {:result "read"})})
    ["lens__catalog" "lens__read"]))

(defn boom-provider [_ns-str _module-index]
  (throw (ex-info "boom" {})))

(def provider-index
  {:isaac.tool.mcp {:manifest {:isaac.agent/tool-providers {:mcp {:ensure! 'isaac.tool.registry-spec/lens-provider}}}}})

(defn- with-tool-registry [f]
  (nexus/-with-nexus {:tool-registry (atom {})}
    (f)))

(describe "Tool Registry"

  (around [it]
    (with-tool-registry it))

  (before (sut/clear!))
  (after (module-loader/clear-activations!))

  ;; region ----- Registration -----

  (describe "registering tools"

    (it "registers a tool by name"
      (sut/register! {:name "read" :description "Read a file" :handler identity})
      (should-not-be-nil (sut/lookup "read")))

    (it "stores tools in the system tool-registry atom"
      (let [registry* (atom {})]
        (nexus/-with-nexus {:tool-registry registry*}
          (sut/register! {:name "read" :description "Read a file" :handler identity})
          (should-not-be-nil (get @registry* "read")))))

    (it "stores the full tool definition"
      (let [handler (fn [_args] "result")
            tool    {:name "write" :description "Write a file" :parameters {} :handler handler}]
        (sut/register! tool)
        (let [found (sut/lookup "write")]
          (should= "write" (:name found))
          (should= "Write a file" (:description found))
          (should= handler (:handler found)))))

    (it "overwrites a previously registered tool"
      (sut/register! {:name "read" :description "v1" :handler identity})
      (sut/register! {:name "read" :description "v2" :handler identity})
      (should= "v2" (:description (sut/lookup "read"))))

    (it "returns nil for unregistered tools"
      (should-be-nil (sut/lookup "nonexistent")))

    (it "lists all registered tools"
      (sut/register! {:name "read"  :description "Read"  :handler identity})
      (sut/register! {:name "write" :description "Write" :handler identity})
      (let [names (set (map :name (sut/all-tools)))]
        (should= #{"read" "write"} names)))

    (it "returns empty list when no tools registered"
      (should= [] (sut/all-tools)))

    (it "unregisters a tool"
      (sut/register! {:name "read" :description "Read" :handler identity})
      (sut/unregister! "read")
      (should-be-nil (sut/lookup "read"))))

  ;; endregion ^^^^^ Registration ^^^^^

  ;; region ----- Execution -----

  (describe "executing tools"

    (it "calls the handler with arguments and returns result"
      (sut/register! {:name    "echo"
                      :handler (fn [args] (str "got: " (:message args)))})
      (let [result (sut/execute "echo" {:message "hello"})]
        (should= "got: hello" (:result result))
        (should-be-nil (:isError result))))

    (it "does not pass runtime crew context to external tool handlers"
      (let [received (atom nil)]
        (sut/register! {:name "external__echo" :handler #(do (reset! received %) {:result "ok"})})
        (sut/execute "external__echo" {"message" "ahoy" "session_key" "session-1" "state_dir" "/state" "crew" "worker"})
        (should= {"message" "ahoy"} @received)))

    (it "returns an error map for an unknown tool"
      (let [result (sut/execute "delete_all" {:path "/"})]
        (should (:isError result))
        (should (string? (:error result)))
        (should (re-find #"unknown tool" (:error result)))))

    (it "returns an error map when the handler throws"
      (sut/register! {:name    "boom"
                      :handler (fn [_] (throw (Exception. "handler failed")))})
      (let [result (sut/execute "boom" {})]
        (should (:isError result))
        (should (re-find #"handler failed" (:error result)))))

    (it "does not double-wrap a handler result that is already normalized"
      (sut/register! {:name    "read"
                      :handler (fn [_] {:result "file contents"})})
      (let [result (sut/execute "read" {})]
        (should= "file contents" (:result result))
        (should-be-nil (:isError result))))

    (it "returns an error map when the handler returns nil"
      (sut/register! {:name "nil-tool" :handler (fn [_] nil)})
      (let [result (sut/execute "nil-tool" {})]
        (should (:isError result))
        (should (re-find #"nil" (:error result)))))

    (it "caps tool output bytes using the configured max-bytes limit"
      (sut/register! {:name "verbose" :handler (fn [_] (apply str (repeat 200 "x")))})
      (with-redefs [loader/snapshot (fn [_] {:defaults {:tools {:max-lines 1000 :max-bytes 100}}})]
        (let [result (sut/execute "verbose" {})]
          (should (str/includes? (:result result) "bytes truncated; byte cap hit")))))

    (it "caps tool output lines using the configured max-lines limit"
      (sut/register! {:name "linesy" :handler (fn [_] (str/join "\n" (repeat 20 "line")))})
      (with-redefs [loader/snapshot (fn [_] {:defaults {:tools {:max-lines 5 :max-bytes 262144}}})]
        (let [result (sut/execute "linesy" {})]
          (should (str/includes? (:result result) "lines truncated; line cap hit")))))

    (it "does not cap error results"
      (sut/register! {:name "fail" :handler (fn [_] {:isError true :error "bad input"})})
      (with-redefs [loader/snapshot (fn [_] {:defaults {:tools {:max-lines 1 :max-bytes 1}}})]
        (let [result (sut/execute "fail" {})]
          (should (:isError result))
          (should= "bad input" (:error result)))))

    (it "keeps {:error :cancelled} from the handler instead of stringifying it"
      (sut/register! {:name "sleeper" :handler (fn [_] {:error :cancelled})})
      (let [result (sut/execute "sleeper" {})]
        (should= {:error :cancelled} result)
        (should-be-nil (:isError result))
        (should-be-nil (:result result))))

    (it "treats disallowed tools as unknown tools"
      (sut/register! {:name "read" :handler identity})
      (let [result (sut/execute "read" {} #{"write"})]
        (should (:isError result))
        (should (re-find #"unknown tool" (:error result)))))

    (it "activates a module when an allowed tool is missing from the registry"
      (module-loader/clear-activations!)
      (let [module-index {:isaac.module.tool-test {:manifest {:isaac.agent/tools {:echo_mod {:factory 'isaac.module.tool-test/tool-spec}}}}}
            result      (sut/execute "echo_mod" {:msg "hi"} #{"echo_mod"} module-index)]
        (should= "module:hi" (:result result))
        (should-not-be-nil (sut/lookup "echo_mod"))))

    (it "registers a factory-returned tool spec using user-config"
      (module-loader/clear-activations!)
      (let [module-index {:isaac.tool.doodad {:manifest {:isaac.agent/tools {:doodad {:factory 'isaac.tool.registry-spec/doodad-tool
                                                                                       :schema  {:api-key {:type :string}}}}}}}]
        (nexus/-with-nexus {:tool-registry (atom {})
                             :config        (atom {:tools {:doodad {:api-key "shazam"}}})}
          (let [result (sut/execute "doodad" {} #{"doodad"} module-index)]
            (should= "api-key=shazam" (:result result))
            (should= "Doodad" (:description (sut/lookup "doodad")))))))

    (it "registers a namespaced berth key under its wire name"
      (module-loader/clear-activations!)
      (let [module-index {:isaac.tool.doodad {:manifest {:isaac.agent/tools {:fs/read {:factory 'isaac.tool.registry-spec/doodad-tool}}}}}]
        (nexus/-with-nexus {:tool-registry (atom {})
                            :config        (atom {})}
          (let [result (sut/execute "fs__read" {} #{:fs/read} module-index)]
            (should= "api-key=" (:result result))
            (should-not-be-nil (sut/lookup "fs__read"))))))

  ;; endregion ^^^^^ Execution ^^^^^

  ;; region ----- tool-fn -----

  (describe "tool-fn"

    (it "returns a function that executes tools by name"
      (sut/register! {:name "greet" :handler (fn [args] (str "Hello, " (:name args)))})
      (let [f (sut/tool-fn)]
        (should= "Hello, World" (f "greet" {:name "World"}))))

    (it "returns an error string for unknown tools"
      (let [f (sut/tool-fn)]
        (should (re-find #"unknown tool" (f "nosuchname" {})))))

    (it "returns an error string when handler throws"
      (sut/register! {:name "fail" :handler (fn [_] (throw (Exception. "oops")))})
      (let [f (sut/tool-fn)]
        (should (re-find #"oops" (f "fail" {})))))

    (it "returns an error string for disallowed tools"
      (sut/register! {:name "read" :handler (fn [_] "ok")})
      (let [f (sut/tool-fn #{"write"})]
        (should (re-find #"unknown tool" (f "read" {}))))))

  (describe "window cache"

    (helper/with-captured-logs)

    (it "returns a stub naming the earlier cycle when the same read hash matches"
      (sut/register! {:name "fs__read" :handler (fn [_] {:result "1: alpha\n2: beta"})})
      (let [cache (atom {})
            first (sut/execute "fs__read" {"file_path" "code.txt"} #{"fs__read"} nil nil cache 1)
            second (sut/execute "fs__read" {"file_path" "code.txt"} #{"fs__read"} nil nil cache 2)]
        (should= "1: alpha\n2: beta" (:result first))
        (should (re-find #"unchanged since cycle 1" (:result second)))
        (should (re-find #"already in your context" (:result second)))
        (should (some #(and (= :tool/cache-hit (:event %))
                            (= "fs__read" (:tool %))
                            (= 1 (:cycle %)))
                      @log/captured-logs))))

    (it "does not stub after an edit invalidates the file"
      (sut/register! {:name "fs__read" :handler (fn [_] {:result "1: alpha"})})
      (sut/register! {:name "fs__edit" :handler (fn [_] {:result "1: ALPHA"})})
      (let [cache (atom {})]
        (sut/execute "fs__read" {"file_path" "code.txt"} #{"fs__read"} nil nil cache 1)
        (sut/execute "fs__edit" {"file_path" "code.txt" "old_string" "alpha" "new_string" "ALPHA"}
                     #{"fs__edit"} nil nil cache 2)
        (let [again (sut/execute "fs__read" {"file_path" "code.txt"} #{"fs__read"} nil nil cache 3)]
          (should= "1: alpha" (:result again))
          (should-not (some #(= :tool/cache-hit (:event %)) @log/captured-logs)))))

    (it "returns a stub for a repeated grep over an unchanged tree"
      (sut/register! {:name "fs__grep" :handler (fn [_] {:result "a.clj:1:(defn seed [] :marigold)\nb.clj:1:(defn water [] :marigold)"})})
      (let [cache (atom {})
            first (sut/execute "fs__grep" {"pattern" "marigold" "path" "."} #{"fs__grep"} nil nil cache 1)
            second (sut/execute "fs__grep" {"pattern" "marigold" "path" "."} #{"fs__grep"} nil nil cache 2)]
        (should (re-find #"a\.clj" (:result first)))
        (should (re-find #"unchanged since cycle 1" (:result second)))
        (should (some #(and (= :tool/cache-hit (:event %))
                            (= "fs__grep" (:tool %))
                            (= 1 (:cycle %)))
                      @log/captured-logs))))

    (it "returns a stub for a skill already loaded in this window"
      (sut/register! {:name "skill__load" :handler (fn [_] {:result "Always quarantine new specimens for one cycle."})})
      (let [cache (atom {})
            first (sut/execute "skill__load" {"name" "greenhouse-protocol"} #{"skill__load"} nil nil cache 1)
            second (sut/execute "skill__load" {"name" "greenhouse-protocol"} #{"skill__load"} nil nil cache 2)]
        (should (re-find #"Always quarantine" (:result first)))
        (should (re-find #"greenhouse-protocol" (:result second)))
        (should (re-find #"already in context since cycle 1" (:result second)))
        (should (some #(and (= :tool/cache-hit (:event %))
                            (= "skill__load" (:tool %))
                            (= 1 (:cycle %)))
                      @log/captured-logs))))

    (it "empties the cache when the turn compacts mid-turn"
      (let [cache (atom {[:read "code.txt" nil nil] {:hash "abc" :cycle 1}})]
        (sut/clear-window-cache! cache)
        (should= {} @cache))))

  ;; endregion ^^^^^ tool-fn ^^^^^

  ;; region ----- Tool Definitions for Prompts -----

  (describe "tool definitions for prompts"

    (it "builds tool definitions list from registered tools"
      (sut/register! {:name        "read"
                      :description "Read a file"
                      :parameters  {:type "object" :properties {:filePath {:type "string"}}}
                      :handler     identity})
      (let [defs (sut/tool-definitions)]
        (should= 1 (count defs))
        (let [d (first defs)]
          (should= "read" (:name d))
          (should= "Read a file" (:description d))
          (should-not-be-nil (:parameters d)))))

    (it "returns empty list when no tools registered"
      (should= [] (sut/tool-definitions)))

    (it "excludes the handler from tool definitions"
      (sut/register! {:name "read" :description "Read" :parameters {} :handler identity})
      (let [d (first (sut/tool-definitions))]
        (should-be-nil (:handler d))))

    (it "filters tool definitions by the allowed tool names"
      (sut/register! {:name "read" :description "Read" :parameters {} :handler identity})
      (sut/register! {:name "write" :description "Write" :parameters {} :handler identity})
      (let [defs (sut/tool-definitions #{"read"})]
        (should= ["read"] (mapv :name defs))))

    (it "filters tool definitions by a namespaced allow token"
      (sut/register! {:name "fs__read" :description "Read" :parameters {} :handler identity})
      (sut/register! {:name "fs__write" :description "Write" :parameters {} :handler identity})
      (let [defs (sut/tool-definitions #{:fs/read})]
        (should= ["fs__read"] (mapv :name defs))))

    (it "filters tool definitions by a namespace glob"
      (sut/register! {:name "fs__read" :description "Read" :parameters {} :handler identity})
      (sut/register! {:name "fs__write" :description "Write" :parameters {} :handler identity})
      (sut/register! {:name "exec__run" :description "Exec" :parameters {} :handler identity})
      (let [defs (sut/tool-definitions #{:fs/*})]
        (should= ["fs__read" "fs__write"] (sort (mapv :name defs))))))

  ;; endregion ^^^^^ Tool Definitions for Prompts ^^^^^

  ;; region ----- Tool Providers -----

  (describe "tool providers (:isaac.agent/tool-providers berth)"

    (helper/with-captured-logs)

    (before (reset! provider-calls* []))

    (it "execute asks a provider for an allowed tool nobody registered"
      (let [result (sut/execute "lens__catalog" {} #{:lens/catalog} provider-index)]
        (should= "catalogued" (:result result))
        (should= ["lens"] @provider-calls*)))

    (it "tool-definitions asks a provider for an exact allow token"
      (let [defs (sut/tool-definitions #{:lens/catalog} provider-index)]
        (should= ["lens__catalog"] (mapv :name defs))
        (should= ["lens"] @provider-calls*)))

    (it "tool-definitions asks a provider for a namespace glob"
      (let [defs (sut/tool-definitions #{:lens/*} provider-index)]
        (should= ["lens__catalog" "lens__read"] (sort (mapv :name defs)))
        (should= ["lens"] @provider-calls*)))

    (it "a provider that declines leaves the namespace unknown"
      (should= [] (sut/tool-definitions #{:skybeam/*} provider-index))
      (should= ["skybeam"] @provider-calls*)
      (let [result (sut/execute "skybeam__catalog" {} #{:skybeam/*} provider-index)]
        (should (:isError result))
        (should (re-find #"unknown tool" (:error result)))))

    (it "does not ask providers for a glob whose namespace already has a tool"
      (sut/register! {:name "lens__catalog" :description "Mine" :parameters {} :handler identity})
      (let [defs (sut/tool-definitions #{:lens/*} provider-index)]
        (should= ["lens__catalog"] (mapv :name defs))
        (should= [] @provider-calls*)))

    (it "does not ask providers for unqualified tokens"
      (sut/register! {:name "read" :description "Read" :parameters {} :handler identity})
      (should= ["read"] (mapv :name (sut/tool-definitions #{"read"} provider-index)))
      (should= [] @provider-calls*))

    (it "ensure-policy-tools! asks providers for globs and exact tokens"
      (sut/ensure-policy-tools! provider-index [:lens/* :skybeam/catalog "read"])
      (should= ["lens" "skybeam"] @provider-calls*)
      (should (sut/lookup "lens__catalog")))

    (it "ensure-policy-tools! is a no-op without a module index"
      (sut/ensure-policy-tools! nil [:lens/*])
      (should= [] @provider-calls*)
      (should-be-nil (sut/lookup "lens__catalog")))

    (it "a provider that throws is logged and skipped"
      (let [index {:isaac.tool.boom {:manifest {:isaac.agent/tool-providers {:boom {:ensure! 'isaac.tool.registry-spec/boom-provider}}}}}]
        (should= [] (sut/tool-definitions #{:lens/*} index))
        (should (some #(= :tool/provider-failed (:event %)) @log/captured-logs)))))

  ;; endregion ^^^^^ Tool Providers ^^^^^

  ;; region ----- Logging -----

  (describe "execution logging"

    (helper/with-captured-logs)

    (it "logs tool execution start"
      (sut/register! {:name "greet" :handler (fn [_] "hi")})
      (sut/execute "greet" {:name "world"})
      (should (some #(= :tool/start (:event %)) @log/captured-logs)))

    (it "logs tool result on success"
      (sut/register! {:name "echo" :handler (fn [args] (:msg args))})
      (sut/execute "echo" {:msg "hello"})
      (should (some #(= :tool/result (:event %)) @log/captured-logs)))

    (it "logs tool error on handler exception"
      (sut/register! {:name "boom" :handler (fn [_] (throw (Exception. "kaboom")))})
      (sut/execute "boom" {})
      (let [err (first (filter #(= :error (:level %)) @log/captured-logs))]
        (should-not-be-nil err)
        (should= :tool/execute-failed (:event err))))

    (it "logs tool execute-failed when handler returns isError"
      (sut/register! {:name "failer" :handler (fn [_] {:isError true :error "bad input"})})
      (sut/execute "failer" {})
      (let [err (first (filter #(= :error (:level %)) @log/captured-logs))]
        (should-not-be-nil err)
        (should= :tool/execute-failed (:event err))))

    (it "logs tool error for unknown tool"
      (sut/execute "nosuchname" {})
      (let [err (first (filter #(= :error (:level %)) @log/captured-logs))]
        (should-not-be-nil err)
        (should= :tool/execute-failed (:event err))))

    (it "includes arguments in :tool/start log entry"
      (sut/register! {:name "read" :handler (fn [_] "content")})
      (sut/execute "read" {:filePath "/etc/hosts"})
      (let [start (first (filter #(= :tool/start (:event %)) @log/captured-logs))]
        (should= {:filePath "/etc/hosts"} (:arguments start))))

    (it "includes result metadata in :tool/result log entry"
      (sut/register! {:name "echo" :handler (fn [_] "file contents here")})
      (sut/execute "echo" {})
      (let [result (first (filter #(= :tool/result (:event %)) @log/captured-logs))]
        (should= 18 (:result-chars result))
        (should= :string (:result-type result))))

    (it "does not log large result bodies in :tool/result"
      (let [big-content (apply str (repeat 300 "x"))]
        (sut/register! {:name "big" :handler (fn [_] big-content)})
        (sut/execute "big" {})
        (let [result (first (filter #(= :tool/result (:event %)) @log/captured-logs))]
          (should= 300 (:result-chars result))
          (should-not-contain "xxx" (pr-str result)))))

    (it "includes arguments in :tool/execute-failed log when handler throws"
      (sut/register! {:name "boom" :handler (fn [_] (throw (Exception. "kaboom")))})
      (sut/execute "boom" {:input "data"})
      (let [err (first (filter #(= :error (:level %)) @log/captured-logs))]
        (should= {:input "data"} (:arguments err))))

    (it "includes arguments in :tool/execute-failed log when handler returns isError"
      (sut/register! {:name "failer" :handler (fn [_] {:isError true :error "bad input"})})
      (sut/execute "failer" {:key "val"})
      (let [err (first (filter #(= :error (:level %)) @log/captured-logs))]
        (should= {:key "val"} (:arguments err))))

    (describe "cwd in log events"

      #_{:clj-kondo/ignore [:invalid-arity]}
      (around [it]
        (store-helper/with-memory-store (it)))

      (it "includes :cwd on :tool/start when session_key is present"
        (let [root "/test/registry-cwd"
              _         (store-helper/create-session! root "s1" {:cwd "/tmp"})]
          (nexus/-with-nested-nexus {:root root}
            (sut/register! {:name "echo" :handler (fn [_] "ok")})
            (sut/execute "echo" {"session_key" "s1"})
            (let [start (first (filter #(= :tool/start (:event %)) @log/captured-logs))]
              (should= "/tmp" (:cwd start))))))

      (it "includes :cwd on :tool/result when session_key is present"
        (let [root "/test/registry-cwd-result"
              _         (store-helper/create-session! root "s1" {:cwd "/tmp"})]
          (nexus/-with-nested-nexus {:root root}
            (sut/register! {:name "echo" :handler (fn [_] "ok")})
            (sut/execute "echo" {"session_key" "s1"})
            (let [result (first (filter #(= :tool/result (:event %)) @log/captured-logs))]
              (should= "/tmp" (:cwd result))))))

      (it "includes :cwd on :tool/execute-failed when session_key is present"
        (let [root "/test/registry-cwd-fail"
              _         (store-helper/create-session! root "s1" {:cwd "/tmp"})]
          (nexus/-with-nested-nexus {:root root}
            (sut/register! {:name "boom" :handler (fn [_] (throw (Exception. "oops")))})
            (sut/execute "boom" {"session_key" "s1"})
            (let [err (first (filter #(= :tool/execute-failed (:event %)) @log/captured-logs))]
              (should= "/tmp" (:cwd err))))))

      (it "logs :cwd nil when no session_key"
        (sut/register! {:name "echo" :handler (fn [_] "ok")})
        (sut/execute "echo" {})
        (let [start (first (filter #(= :tool/start (:event %)) @log/captured-logs))]
          (should (contains? start :cwd))
          (should-be-nil (:cwd start))))

      (it "strips session_key from :arguments in :tool/start"
        (sut/register! {:name "echo" :handler (fn [_] "ok")})
        (sut/execute "echo" {"session_key" "s1" :path "/foo"})
        (let [start (first (filter #(= :tool/start (:event %)) @log/captured-logs))]
          (should-not-contain :session_key (:arguments start))
          (should-not-contain "session_key" (:arguments start))))

      (it "strips session_key from :arguments in :tool/execute-failed"
        (sut/register! {:name "boom" :handler (fn [_] (throw (Exception. "oops")))})
        (sut/execute "boom" {"session_key" "s1" :data "x"})
        (let [err (first (filter #(= :tool/execute-failed (:event %)) @log/captured-logs))]
          (should-not-contain :session_key (:arguments err)))))

    )

  ;; endregion ^^^^^ Logging ^^^^^

  ))
