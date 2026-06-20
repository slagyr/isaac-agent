(ns isaac.drive.turn-spec
  (:require
    [isaac.api]
    [isaac.bridge.cancellation :as bridge]
    [isaac.comm.memory :as memory-comm]
    [isaac.comm.null :as null-comm]
    [isaac.config.api :as config]
    [isaac.drive.dispatch :as dispatch]
    [isaac.drive.turn :as sut]
    [isaac.fs :as fs]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.prompt.builder :as prompt]
    [isaac.session.compaction :as compaction]
    [isaac.llm.tool-loop :as tool-loop]
    [isaac.logger :as log]
    [isaac.marigold :as marigold]
    [isaac.marigold.agent :as marigold.agent]
    [isaac.session.context :as session-ctx]
    [isaac.session.store.spi :as store]
    [isaac.session.store.sidecar :as sidecar-store]
    [isaac.session.spec-helper :as helper]
    [isaac.nexus :as nexus]
    [isaac.tool.builtin :as builtin]
    [isaac.tool.registry :as tool-registry]
    [speclj.core :refer :all]))

(def test-dir marigold/home)

(defn- event [events kind]
  (first (filter #(= kind (:event %)) @events)))

(defn- base-execution-ctx [provider charge]
  {:provider      provider
   :allowed-tools []
   :boot-files    nil
   :effort        nil
   :root     test-dir
   :session-store (store/registered-store)
   :charge        charge})

(deftype TestProvider [name cfg]
  api/Api
  (chat [_ _] {:message {:role "assistant" :content "ok"} :model "test-model" :usage {}})
  (chat-stream [_ _ _] {:message {:role "assistant" :content "ok"} :model "test-model" :usage {}})
  (followup-messages [_ request _ _ _] (:messages request))
  (config [_] cfg)
  (display-name [_] name)
  (format-tools [_ tools] (when (seq tools) (mapv api/wrapped-function-tool tools)))
  (build-prompt [_ {:keys [model tools]}]
    {:model    model
     :messages [{:role "user" :content "hi"}]
     :tools    tools}))

(deftype PromptProvider [name cfg]
  api/Api
  (chat [_ _] {:message {:role "assistant" :content "ok"} :model "test-model" :usage {}})
  (chat-stream [_ _ _] {:message {:role "assistant" :content "ok"} :model "test-model" :usage {}})
  (followup-messages [_ request _ _ _] (:messages request))
  (config [_] cfg)
  (display-name [_] name)
  (build-prompt [_ opts]
    (prompt/build opts)))

(describe "turn usage"

  (marigold.agent/with-manifest)

  (describe "normalize-usage"
    (it "normalizes provider usage aliases into transcript-friendly keys"
      (should= {:input-tokens     100
                :output-tokens    50
                :total-tokens     150
                :cache-read       7
                :cache-write      3
                :reasoning-tokens 11}
               (sut/normalize-usage {:response {:usage {:input_tokens           100
                                                       :output_tokens          50
                                                       :cache_creation_input_tokens 3
                                                       :input_tokens_details   {:cached_tokens 7}
                                                       :output_tokens_details  {:reasoning_tokens 11}}}})))

    (it "prefers accumulated token counts over the last raw provider usage block"
      (should= {:input-tokens  12
                :output-tokens 8
                :total-tokens  20
                :cache-read    2
                :cache-write   1}
               (sut/normalize-usage {:token-counts {:input-tokens  12
                                                    :output-tokens 8
                                                    :cache-read    2
                                                    :cache-write   1}
                                     :response     {:usage {:input_tokens                 3
                                                            :output_tokens                4
                                                            :cache_creation_input_tokens 88
                                                            :input_tokens_details         {:cached_tokens 99}}}}))))

  (describe "process-response!"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "stores a normalized usage map even when the provider omits :usage"
      (helper/create-session! test-dir "usage-test")
      (sut/process-response! "usage-test"
                             {:content  "Hello from Marigold"
                              :response {:prompt_eval_count 20
                                         :eval_count        5}}
                             {:model "groves-13b" :provider marigold/flicker-labs})
      (let [assistant (-> (helper/get-transcript test-dir "usage-test")
                          last
                          :message)]
         (should= {:input-tokens  20
                   :output-tokens 5
                   :total-tokens  25
                   :cache-read    0
                   :cache-write   0}
                 (:usage assistant))))

    (it "stores the configured model when the provider omits :model"
      (helper/create-session! test-dir "model-test")
      (sut/process-response! "model-test"
                             {:content  "Two! Two clouds!"
                              :response {:message {:role "assistant" :content "Two! Two clouds!"}}}
                             {:model "count" :provider "grover:grok"})
      (let [assistant (-> (helper/get-transcript test-dir "model-test")
                          last
                          :message)]
        (should= "count" (:model assistant))
        (should= "grover:grok" (:provider assistant)))))

  (describe "streaming helpers"

    (it "reads content from supported chunk shapes"
      (should= "hello" (#'sut/chunk-content {:message {:content "hello"}}))
      (should= "delta" (#'sut/chunk-content {:delta {:text "delta"}}))
      (should= "choice" (#'sut/chunk-content {:choices [{:delta {:content "choice"}}]}))
      (should= "ab" (#'sut/chunk-content {:message {:content ["a" "b"]}}))
      (should= nil (#'sut/chunk-content {:message {:content nil}})))

    (it "streams only new text and returns the final response chunk"
      (let [chunks (atom [])]
        (with-redefs [dispatch/dispatch-chat-stream (fn [_ _ on-chunk]
                                                      (on-chunk {:message {:content "Hel"}})
                                                      (on-chunk {:delta {:text "Hello"} :done true})
                                                      {:message {:content "Hello"}})]
          (should= {:content "Hello"
                    :response {:delta {:text "Hello"} :done true}}
                   (sut/stream-response! :provider {:model "test"} #(swap! chunks conj %)))
          (should= ["Hel" "lo"] @chunks))))

    (it "falls back to the dispatch result content when no chunks arrive"
      (with-redefs [dispatch/dispatch-chat-stream (fn [& _] {:message {:content "Fallback"}})]
        (should= {:content "Fallback"
                  :response {:message {:content "Fallback"}}}
                 (sut/stream-response! :provider {:model "test"} (fn [_] nil)))))

    (it "returns dispatch errors unchanged"
      (with-redefs [dispatch/dispatch-chat-stream (fn [& _] {:error :timeout :message "No response"})]
        (should= {:error :timeout :message "No response"}
                 (sut/stream-response! :provider {:model "test"} (fn [_] nil)))))

    (it "emits response content chunks through comm and joins them"
      (let [events (atom [])
            comm   (memory-comm/channel events)]
        (should= "ab"
                 (#'sut/emit-response-content! comm "stream-session" {:message {:content ["a" "b"]}}))
        (should= [{:event "text-chunk" :session "stream-session" :text "a"}
                  {:event "text-chunk" :session "stream-session" :text "b"}]
                 @events)))

    (it "merges token counts from accumulated totals and a response usage block"
      (should= {:input-tokens  12
                :output-tokens 8
                :cache-read    2
                :cache-write   1}
               (#'sut/merge-response-tokens {:input-tokens 10 :output-tokens 5 :cache-read 1 :cache-write 0}
                                            {:usage {:input_tokens                 2
                                                     :output_tokens                3
                                                     :cache_creation_input_tokens 1
                                                     :input_tokens_details         {:cached_tokens 1}}}))))

  (describe "record-tool-call!"

    (it "records successful tool calls and emits call/result events"
      (let [events         (atom [])
            executed-tools (atom [])
            registered     (atom nil)
            args-seen      (atom nil)]
        (with-redefs [bridge/on-cancel!      (fn [session-key cancel!]
                                               (reset! registered [session-key cancel!])
                                               nil)
                      tool-registry/tool-fn   (fn [allowed-tools _module-index _caps]
                                                (should= #{"search"} allowed-tools)
                                                (fn [name args]
                                                  (reset! args-seen [name args])
                                                  {:result "ok"}))]
          (let [result (#'sut/record-tool-call! {:comm           (memory-comm/channel events)
                                                 :session-key    "tool-success"
                                                 :allowed-tools  #{"search"}
                                                 :executed-tools executed-tools}
                                               "search"
                                               {"query" "logs"})]
            (should= {:result "ok"} result)
            (should= "search" (first @args-seen))
            (should= {"query" "logs" "session_key" "tool-success"} (second @args-seen))
            (should= "tool-success" (first @registered))
            (should= 1 (count @executed-tools))
            (should= ["tool-call" "tool-result"] (mapv :event @events)))))

    (it "cancels and throws when a tool reports cancellation"
      (let [events         (atom [])
            executed-tools (atom [])
            registered     (atom nil)]
        (with-redefs [bridge/on-cancel!    (fn [session-key cancel!]
                                             (reset! registered [session-key cancel!])
                                             nil)
                      tool-registry/tool-fn (fn [allowed-tools module-index _caps]
                                              (should= #{"search"} allowed-tools)
                                              (should= {:modules true} module-index)
                                              (fn [_ _] {:error :cancelled}))]
          (should-throw clojure.lang.ExceptionInfo
                        "cancelled"
                        (#'sut/record-tool-call! {:comm           (memory-comm/channel events)
                                                  :session-key    "tool-cancelled"
                                                  :allowed-tools  #{"search"}
                                                  :module-index   {:modules true}
                                                  :executed-tools executed-tools}
                                                "search"
                                                {"query" "logs"}))
          (should= "tool-cancelled" (first @registered))
          (should= [] @executed-tools)
          (should= ["tool-call" "tool-cancel"] (mapv :event @events))))))

  (describe "build-chat-request"

    (it "passes nonce through to the provider prompt builder"
      (let [seen (atom nil)
            provider (reify api/Api
                       (chat [_ _] nil)
                       (chat-stream [_ _ _] nil)
                       (followup-messages [_ _ _ _ _] nil)
                       (config [_] {})
                       (display-name [_] "test")
                       (format-tools [_ tools] tools)
                       (build-prompt [_ opts]
                         (reset! seen opts)
                         {:model (:model opts) :messages []}))]
        (sut/build-chat-request provider {:model "spark" :soul "You are Isaac." :nonce "N0NCE-abc123" :transcript []})
        (should= "N0NCE-abc123" (:nonce @seen))))

    (it "passes origin and guidance through to the provider prompt builder"
      (let [seen     (atom nil)
            provider (reify api/Api
                       (chat [_ _] nil)
                       (chat-stream [_ _ _] nil)
                       (followup-messages [_ _ _ _ _] nil)
                       (config [_] {})
                       (display-name [_] "test")
                       (format-tools [_ tools] tools)
                       (build-prompt [_ opts]
                         (reset! seen opts)
                         {:model (:model opts) :messages []}))]
        (sut/build-chat-request provider {:guidance   "Autonomous hail; the user may not see your reply."
                                          :model      "spark"
                                          :nonce      "N0NCE-abc123"
                                          :origin     {:kind :hail :hail-id "hail-1"}
                                          :soul       "You are Isaac."
                                          :transcript []})
        (should= {:kind :hail :hail-id "hail-1"} (:origin @seen))
        (should= "Autonomous hail; the user may not see your reply." (:guidance @seen))))

    (it "passes skill-menu-text through to the provider prompt builder"
      (let [seen     (atom nil)
            provider (reify api/Api
                       (chat [_ _] nil)
                       (chat-stream [_ _ _] nil)
                       (followup-messages [_ _ _ _ _] nil)
                       (config [_] {})
                       (display-name [_] "test")
                       (format-tools [_ tools] tools)
                       (build-prompt [_ opts]
                         (reset! seen opts)
                         {:model (:model opts) :messages []}))]
        (sut/build-chat-request provider {:model           "spark"
                                          :skill-menu-text "Available skills:\n- greenhouse-protocol: Use when tending specimens"
                                          :soul            "You are Isaac."
                                          :transcript      []})
        (should= "Available skills:\n- greenhouse-protocol: Use when tending specimens"
                 (:skill-menu-text @seen)))))

  )

  (describe "await-async-compaction!"

    (it "returns nil when no async compaction is tracked"
      (sut/clear-async-compactions!)
      (should= nil (sut/await-async-compaction! "missing-session")))

    (it "signals splice readiness, returns the future result, and clears the state"
      (sut/clear-async-compactions!)
      (let [splice-ready (promise)
            future*      (future :done)]
        (swap! @#'sut/in-flight-compactions assoc "async-session" {:future future* :splice-ready splice-ready})
        (should= :done (sut/await-async-compaction! "async-session"))
        (should= true (deref splice-ready 1000 nil))
        (should= false (sut/async-compaction-in-flight? "async-session"))))

    (it "throws on timeout and leaves the state in place"
      (sut/clear-async-compactions!)
      (let [future*    (Object.)
            orig-deref clojure.core/deref]
        (swap! @#'sut/in-flight-compactions assoc "stuck-session" {:future future*})
        (with-redefs [clojure.core/deref (fn
                                            ([ref] (orig-deref ref))
                                            ([_ _ timeout-val] timeout-val))]
          (should-throw clojure.lang.ExceptionInfo
                        "async compaction did not complete within 30 seconds"
                        (sut/await-async-compaction! "stuck-session"))
          (should= true (sut/async-compaction-in-flight? "stuck-session"))))))

  (describe "perform-compaction!"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "stops once the attempt limit is exceeded"
      (let [provider (->TestProvider marigold/starcore {:api marigold/sky-api})]
        (with-redefs [compaction/compact! (fn [& _] (throw (ex-info "should not compact" {})))]
          (log/capture-logs
            (#'sut/perform-compaction! "attempt-limit" 6 1200 {:context-window 1000
                                                                 :model "test-model"
                                                                 :provider provider
                                                                 :soul "You are Isaac."})
            (let [entry (first (filter #(= :session/compaction-stopped (:event %)) @log/captured-logs))]
              (should-not-be-nil entry)
              (should= :max-attempts (:reason entry))
              (should= 6 (:attempt entry)))))))

    (it "records failures and disables compaction after too many consecutive errors"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-fail"
            session-store (store/registered-store)
            events        (atom [])]
        (helper/create-session! test-dir session-key)
        (helper/update-session! test-dir session-key {:compaction {:consecutive-failures 4}})
        (with-redefs [compaction/compact! (fn [& _] {:error :rate-limited :message "Please retry later"})]
          (#'sut/perform-compaction! session-key 1 800 {:comm          (memory-comm/channel events)
                                                        :context-window 1000
                                                        :model         "test-model"
                                                        :provider      provider
                                                        :soul          "You are Isaac."
                                                        :root     test-dir
                                                        :session-store session-store})
          (let [session (helper/get-session test-dir session-key)]
            (should= true (:compaction-disabled session))
            (should= {:consecutive-failures 5} (:compaction session))
            (should= {:event "compaction-failure"
                      :session session-key
                      :consecutive-failures 5
                      :error :rate-limited
                      :message "Please retry later"}
                     (event events "compaction-failure"))
            (should= {:event "compaction-disabled"
                      :session session-key
                      :reason :too-many-failures}
                     (event events "compaction-disabled"))))))

    (it "resets failure state and rechecks compaction after successful progress"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-success"
            session-store (store/registered-store)
            events        (atom [])
            follow-up     (atom nil)]
        (helper/create-session! test-dir session-key)
        (helper/update-session! test-dir session-key {:last-input-tokens   800
                                                      :compaction-disabled true
                                                      :compaction          {:consecutive-failures 2}})
        (with-redefs [compaction/compact!      (fn [& _]
                                                 (helper/update-session! test-dir session-key {:last-input-tokens 200})
                                                 {:summary "Shorter now"})
                      sut/run-compaction-check! (fn [next-session-key next-opts next-attempt allow-async?]
                                                  (reset! follow-up [next-session-key next-opts next-attempt allow-async?]))]
          (#'sut/perform-compaction! session-key 2 800 {:comm           (memory-comm/channel events)
                                                        :context-window 1000
                                                        :model          "test-model"
                                                        :provider       provider
                                                        :soul           "You are Isaac."
                                                        :root      test-dir
                                                        :session-store  session-store})
          (let [session (helper/get-session test-dir session-key)
                success (event events "compaction-success")]
            (should= false (:compaction-disabled session))
            (should= {:consecutive-failures 0} (:compaction session))
            (should-not-be-nil success)
            (should= "Shorter now" (:summary success))
            (should= 600 (:tokens-saved success))
            (should (number? (:duration-ms success)))
            (should= session-key (first @follow-up))
            (should= 3 (nth @follow-up 2))
            (should= false (nth @follow-up 3))))))

    (it "stops when compaction makes no token progress"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-stuck"
            session-store (store/registered-store)
            events        (atom [])]
        (helper/create-session! test-dir session-key)
        (helper/update-session! test-dir session-key {:last-input-tokens 800})
        (with-redefs [compaction/compact!      (fn [& _] {:summary "No progress"})
                      sut/run-compaction-check! (fn [& _] (throw (ex-info "should not re-run" {})))]
          (log/capture-logs
            (#'sut/perform-compaction! session-key 2 800 {:comm           (memory-comm/channel events)
                                                          :context-window 1000
                                                          :model          "test-model"
                                                          :provider       provider
                                                          :soul           "You are Isaac."
                                                          :root      test-dir
                                                          :session-store  session-store})
            (let [entry (first (filter #(= :session/compaction-stopped (:event %)) @log/captured-logs))]
              (should-not-be-nil entry)
              (should= :no-progress (:reason entry))
              (should-not-be-nil (event events "compaction-success"))))))))

  (describe "build-turn"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "wraps the charge and exposes per-turn derived fields"
      (helper/create-session! test-dir "wrap-test")
      (helper/update-session! test-dir "wrap-test" {:crew "main"})
      (let [provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "wrap-test"
                      :input          "hi"
                      :comm           :test-comm
                      :config         {:root test-dir}
                      :crew           "main"
                      :crew-members   {"main" {:model "spark" :tools {:allow [:spyglass]}}}
                      :context-window 32768
                      :model          "helm-spark-1.0"
                      :provider       provider
                      :soul           "You are Isaac."
                      :effort         5}]
        (with-redefs [sut/augment-provider (fn [_root p _session-key _context-window _model-cfg-overrides] p)]
          (let [turn (#'sut/build-turn charge)]
            (should= charge (:charge turn))
            (should= 5     (:effort turn))
            (should= ["spyglass"] (sort (:allowed-tools turn)))
            (should-not-be-nil (:root turn))
            (should-not-be-nil (:session-store turn))))))

    (it "auto-allows skill activation tools discovered from the prompt catalog"
      (helper/create-session! test-dir "skill-turn")
      (helper/update-session! test-dir "skill-turn" {:crew "main"})
      (let [provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "skill-turn"
                      :input          "hi"
                      :comm           :test-comm
                      :config         {:root test-dir}
                      :crew           "main"
                      :crew-members   {"main" {:model "spark"}}
                      :context-window 32768
                      :model          "helm-spark-1.0"
                      :provider       provider
                      :soul           "You are Isaac."}]
        (with-redefs [sut/augment-provider (fn [_root p _session-key _context-window _model-cfg-overrides] p)
                      session-ctx/read-skill-disclosure (fn [& _]
                                                          {:menu-text  nil
                                                           :tool-names #{"list_skills" "load_skill"}})]
          (let [turn (#'sut/build-turn charge)]
            (should= ["list_skills" "load_skill"] (sort (:allowed-tools turn))))))))

  (describe "context-mode"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "replays prior transcript entries by default"
      (helper/create-session! test-dir "full-history" {:crew "main"})
      (helper/append-message! test-dir "full-history" {:role "user" :content "What are we doing tonight?"})
      (helper/append-message! test-dir "full-history" {:role "assistant" :content "The same thing we do every night."})
      (let [provider (->PromptProvider marigold/starcore {:api marigold/sky-api})
            captured (atom nil)
            ctx      (base-execution-ctx provider {:model        "test-model"
                                                   :soul         "You are Brain."
                                                   :context-mode nil
                                                   :comm         null-comm/channel})]
        (with-redefs [tool-loop/run (fn [_ _ request _ _]
                                      (reset! captured request)
                                      {:message {:role "assistant" :content "Try to take over the world."}
                                       :model   "test-model"
                                        :usage   {}
                                       :tool-calls []})
                      sut/process-response! (fn [& _] nil)]
          (#'sut/execute-llm-turn! "full-history" "Are the blueprints ready?" ctx))
        (should= [{:role "system" :content "You are Brain."}
                  {:role "user" :content "What are we doing tonight?"}
                  {:role "assistant" :content "The same thing we do every night."}
                  {:role "user" :content "Are the blueprints ready?"}]
                 (:messages @captured))))

    (it "replays only the current user message when context-mode is reset"
      (helper/create-session! test-dir "reset-history" {:crew "pinky"})
      (helper/append-message! test-dir "reset-history" {:role "user" :content "Are you pondering what I'm pondering?"})
      (helper/append-message! test-dir "reset-history" {:role "assistant" :content "I think so, Brain."})
      (let [provider (->PromptProvider marigold/starcore {:api marigold/sky-api})
            captured (atom nil)
            ctx      (base-execution-ctx provider {:model        "test-model"
                                                   :soul         "You are Pinky."
                                                   :context-mode :reset
                                                   :comm         null-comm/channel})]
        (with-redefs [tool-loop/run (fn [_ _ request _ _]
                                      (reset! captured request)
                                      {:message {:role "assistant" :content "Logged. Narf!"}
                                       :model   "test-model"
                                        :usage   {}
                                       :tool-calls []})
                      sut/process-response! (fn [& _] nil)]
          (#'sut/execute-llm-turn! "reset-history" "Brain escaped the cage." ctx))
        (should= [{:role "system" :content "You are Pinky."}
                  {:role "user" :content "Brain escaped the cage."}]
                 (:messages @captured))))

    )

  (describe "1-arg run-turn! (charge arity)"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "delegates via session-key and input extracted from the charge"
      (helper/create-session! test-dir "charge-arity" {:crew "main"})
      (let [provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            captured (atom nil)
            charge   {:charge/type    :charge
                      :session-key    "charge-arity"
                      :input          "engage"
                      :root      test-dir
                      :session-store  (store/registered-store)
                      :comm           null-comm/channel
                      :crew           "main"
                      :model          "test-model"
                      :provider       provider
                      :soul           "You are Isaac."
                      :context-window 4096}]
        (with-redefs [sut/build-turn       (fn [c]
                                             (should= charge c)
                                             (base-execution-ctx provider c))
                      tool-loop/run        (fn [_ _ request _ _]
                                             (reset! captured request)
                                             {:message {:role "assistant" :content "ready"} :model "test-model" :usage {} :tool-calls []})
                      sut/process-response! (fn [& _] nil)]
          (sut/run-turn! charge))
        (should-not-be-nil @captured)
        (should= "test-model" (:model @captured))))

    (it "does not bulk-register built-in tools on each turn"
      (helper/create-session! test-dir "no-reregister" {:crew "main"})
      (let [provider      (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            register-calls (atom 0)
            charge        {:charge/type    :charge
                           :session-key    "no-reregister"
                           :input          "first"
                           :root      test-dir
                           :session-store  (store/registered-store)
                           :comm           null-comm/channel
                           :crew           "main"
                           :model          "test-model"
                           :provider       provider
                           :soul           "You are Isaac."
                           :context-window 4096}]
        (with-redefs [builtin/register-all! (fn [& _] (swap! register-calls inc))
                      tool-loop/run         (fn [_ _ _ _ _]
                                              {:message {:role "assistant" :content "ok"}
                                               :model   "test-model"
                                               :usage   {}
                                               :tool-calls []})
                      sut/process-response! (fn [& _] nil)]
          (sut/run-turn! charge)
          (sut/run-turn! (assoc charge :input "second")))
        (should= 0 @register-calls))))

  (describe "logging"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "logs the resolved turn context"
      (helper/create-session! test-dir "context-log")
      (helper/update-session! test-dir "context-log" {:crew "main" :cwd "/tmp/workspace"})
      (let [provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "context-log"
                      :input          "go"
                      :comm           :test-comm
                      :root      test-dir
                      :session-store  (store/registered-store)
                      :crew           "main"
                      :crew-members   {"main" {:model "spark" :soul "You are Isaac." :tools {:allow [:spyglass :sextant]}}}
                      :crew-cfg       {:model "spark" :soul "You are Isaac." :tools {:allow [:spyglass :sextant]}}
                      :context-window 32768
                      :model          "helm-spark-1.0"
                      :provider       provider
                      :soul           "You are Isaac."}]
        (with-redefs [sut/augment-provider (fn [_root p _session-key _context-window _model-cfg-overrides] p)]
          (log/capture-logs
            (#'sut/build-turn charge)
            (let [entry (first (filter #(= :turn/context-resolved (:event %)) @log/captured-logs))]
              (should-not-be-nil entry)
              (should= "context-log" (:session entry))
              (should= "main" (:crew entry))
              (should= "helm-spark-1.0" (:model entry))
              (should= marigold/quantum-anvil (:provider entry))
              (should= 32768 (:context-window entry))
              (should= #{"main"} (set (:crew-keys entry)))
              (should= #{:model :soul :tools} (set (:crew-cfg-keys entry)))
              (should= ["sextant" "spyglass"] (sort (:allowed-tools entry)))
              (should= "/tmp/workspace" (:cwd entry)))))))

    (it "logs selected tools, built request, and response summary"
      (helper/create-session! test-dir "log-turn")
      (helper/update-session! test-dir "log-turn" {:crew "main"})
      (let [provider (->TestProvider marigold/starcore {:api marigold/sky-api})
            result   {:message {:role "assistant" :content "ok"}
                      :model   "test-model"
                      :usage   {}
                      :tool-calls []}]
        (config/dangerously-install-config! {:defaults {:crew "main" :model "test"}
                               :crew     {"main" {:model "test" :soul "You are Isaac." :tools {:allow [:logbook-entry]}}}
                               :models   {"test" {:model "test-model" :provider marigold/starcore :context-window 32768}}} "spec")
        (tool-registry/clear!)
        (tool-registry/register! {:name        "logbook-entry"
                                  :description "Append to the ship's log"
                                  :parameters  {:type "object"}
                                  :handler     (fn [_] {:result "ok"})})
        (with-redefs [sut/append-message!   (fn [& _] nil)
                      sut/process-response! (fn [_ _ result _] result)
                      store/get-transcript  (fn [& _] [])
                      tool-loop/run         (fn [& _] result)]
          (log/capture-logs
            (sut/run-turn! {:charge/type    :charge
                            :session-key    "log-turn"
                            :input          "hi"
                            :root      test-dir
                            :session-store  (store/registered-store)
                            :comm           null-comm/channel
                            :crew           "main"
                            :crew-members   {"main" {:tools {:allow [:logbook-entry]}}}
                            :context-window 32768
                            :model          "test-model"
                            :provider       provider
                            :soul           "You are Isaac."})
            (let [request-entry  (first (filter #(= :turn/request-built (:event %)) @log/captured-logs))
                  response-entry (first (filter #(= :turn/model-response-summary (:event %)) @log/captured-logs))]
              (should-not-be-nil request-entry)
              (should= "log-turn" (:session request-entry))
              (should= marigold/starcore (:provider request-entry))
              (should= "test-model" (:model request-entry))
              (should= 1 (:selected-tools-count request-entry))
              (should= ["logbook-entry"] (:selected-tools request-entry))
              (should-not-be-nil response-entry)
              (should= "log-turn" (:session response-entry))
              (should= 2 (:assistant-content-chars response-entry))
              (should= 0 (:tool-calls-count response-entry))
              (should= 0 (:executed-tools-count response-entry)))))
        (config/dangerously-install-config! nil "spec")))))
