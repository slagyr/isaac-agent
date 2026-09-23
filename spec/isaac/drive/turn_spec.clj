(ns isaac.drive.turn-spec
  (:require
    [isaac.llm.turn-instructions :as turn-instructions]
    [isaac.api]
    [isaac.bridge.cancellation :as bridge]
    [isaac.comm.memory :as memory-comm]
    [isaac.comm.null :as null-comm]
    [isaac.config.api :as config]
    [isaac.drive.dispatch :as dispatch]
    [isaac.drive.observer :as observer]
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
    [isaac.session.spec-helper :as helper]
    [isaac.nexus :as nexus]
    [isaac.tool.builtin :as builtin]
    [isaac.tool.registry :as tool-registry]
    [isaac.turnstile :as turnstile]
    [speclj.core :refer [around describe it should should-be-nil should-not should-not-be-nil should-throw should=]]))

(def test-dir marigold/home)

(defn lens-provider
  "Tool-provider fixture: registers the lens namespace on demand."
  [ns-str _module-index]
  (when (= "lens" ns-str)
    (tool-registry/register! {:name "lens__catalog" :description "Catalog" :parameters {} :handler (fn [_] {:result "ok"})})
    (tool-registry/register! {:name "lens__read" :description "Read" :parameters {} :handler (fn [_] {:result "ok"})})
    ["lens__catalog" "lens__read"]))

(describe "turn ending classification"
  (it "reports provider unavailability separately from context exhaustion"
    (should= :provider-unavailable
             (:ended-by (#'sut/finalize-turn-result {:unavailable? true}))))

  (it "keeps genuine context exhaustion"
    (should= :context-exhausted
             (:ended-by (#'sut/finalize-turn-result {:unavailable? true :reason :context-exhausted}))))

  (it "keeps errors ahead of provider unavailability"
    (should= :error
             (:ended-by (#'sut/finalize-turn-result {:unavailable? true :error :provider-error}))))

  (it "keeps :provider-unavailable when the drive loop weather-suspends a wall"
    (should= :provider-unavailable
             (:ended-by (#'sut/finalize-turn-result {:unavailable? true
                                                     :stopReason    "suspended"
                                                     :ended-by      :suspended
                                                     :reason        :wall}))))
  )

(defn- event
  ([events kind]
   (first (filter #(= kind (:event %)) @events)))
  ([events kind bulletin-kind]
   (first (filter #(and (= kind (:event %)) (= bulletin-kind (:kind %))) @events))))

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
  (chat [_ _] {:content "ok" :model "test-model" :tool-calls [] :stop-reason :end-turn
                 :usage {:prompt-tokens 1 :output-tokens 1}})
  (chat-stream [_ _ _] {:content "ok" :model "test-model" :tool-calls [] :stop-reason :end-turn
                          :usage {:prompt-tokens 1 :output-tokens 1}})
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
  (chat [_ _] {:content "ok" :model "test-model" :tool-calls [] :stop-reason :end-turn
                 :usage {:prompt-tokens 1 :output-tokens 1}})
  (chat-stream [_ _ _] {:content "ok" :model "test-model" :tool-calls [] :stop-reason :end-turn
                          :usage {:prompt-tokens 1 :output-tokens 1}})
  (followup-messages [_ request _ _ _] (:messages request))
  (config [_] cfg)
  (display-name [_] name)
  (build-prompt [_ opts]
    (prompt/build opts)))

(defn- normalize-scripted-response [response]
  (if (:error response)
    (api/normalize-error response)
    (let [message    (:message response)
          tool-calls (or (:tool-calls response)
                         (mapv (fn [tool-call]
                                 {:id (or (:id tool-call) (str (java.util.UUID/randomUUID)))
                                  :name (get-in tool-call [:function :name])
                                  :arguments (or (get-in tool-call [:function :arguments]) {})})
                               (:tool_calls message)))
          usage      (:usage response)]
      {:content     (or (:content response) (:content message) "")
       :model       (or (:model response) "test-model")
       :tool-calls  (vec tool-calls)
       :stop-reason (if (seq tool-calls) :tool-use :end-turn)
       :usage       {:prompt-tokens (or (:prompt-tokens usage) (:input_tokens usage) 1)
                     :output-tokens (or (:output-tokens usage) (:output_tokens usage) 1)}})))

(deftype ScriptedPromptProvider [name cfg queue captured]
  api/Api
  (chat [_ request]
    (swap! captured conj request)
    (let [resp (first @queue)]
      (when-not resp
        (throw (ex-info "scripted provider queue exhausted" {})))
      (swap! queue rest)
      (normalize-scripted-response resp)))
  (chat-stream [_ request _]
    (swap! captured conj request)
    (let [resp (first @queue)]
      (when-not resp
        (throw (ex-info "scripted provider queue exhausted" {})))
      (swap! queue rest)
      (normalize-scripted-response resp)))
  (followup-messages [_ request response tool-calls tool-results]
    (into (conj (vec (:messages request))
                {:role "assistant" :content (or (:content response) "") :tool_calls tool-calls})
          (mapv (fn [result] {:role "tool" :content result}) tool-results)))
  (config [_] cfg)
  (display-name [_] name)
  (format-tools [_ tools] (when (seq tools) (mapv api/wrapped-function-tool tools)))
  (build-prompt [_ opts]
    (prompt/build opts)))

(describe "turn usage"

  (marigold.agent/with-manifest)

  (describe "normalize-usage"
    (it "presents normalized cumulative turn usage for transcript storage"
      (should= {:prompt-tokens 112
                :output-tokens 8
                :total-tokens 120
                :cache-read-tokens 7
                :cache-write-tokens 3
                :reasoning-tokens 11}
               (sut/normalize-usage {:usage {:requests 2
                                             :prompt-tokens 112
                                             :output-tokens 8
                                             :cache-read-tokens 7
                                             :cache-write-tokens 3
                                             :reasoning-tokens 11}}))))

  (describe "process-response!"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "stores normalized turn usage and stamps context from the final request"
      (helper/create-session! test-dir "tool-loop-usage")
      (sut/process-response! "tool-loop-usage"
                             {:usage {:requests 2 :prompt-tokens 220 :output-tokens 7
                                      :cache-read-tokens 2 :cache-write-tokens 1}
                              :response {:content "Done" :model "echo" :tool-calls [] :stop-reason :end-turn
                                         :usage {:prompt-tokens 123 :output-tokens 7
                                                 :cache-read-tokens 2 :cache-write-tokens 1}}}
                             {:model "echo" :provider "grover:grok"})
      (let [assistant (-> (helper/get-transcript test-dir "tool-loop-usage") last :message)
            session   (helper/get-session test-dir "tool-loop-usage")]
        (should= 220 (get-in assistant [:usage :prompt-tokens]))
        (should= 220 (:turn-input-tokens session))
        (should= 123 (:last-input-tokens session))
        (should= 7 (:last-output-tokens session))))

    (it "a response that reports no prompt tokens leaves the tally where it was (isaac-166j)"
      ;; A refused request — "prompt is too long" — reports zero usage because no
      ;; inference happened. Writing those zeros through is how a full session comes
      ;; to read as empty, and an empty-reading session is never compacted.
      (helper/create-session! test-dir "refused")
      (helper/update-session! test-dir "refused" {:last-input-tokens 180000 :last-output-tokens 900})
      (sut/process-response! "refused"
                             {:usage {:requests 1 :prompt-tokens 0 :output-tokens 0}
                              :response {:content "" :model "echo" :tool-calls [] :stop-reason :end-turn
                                         :usage {:prompt-tokens 0 :output-tokens 0}}}
                             {:model "echo" :provider "grover:grok"})
      (let [session (helper/get-session test-dir "refused")]
        (should= 180000 (:last-input-tokens session))))

    (it "a prompt bigger than the window is over budget, not impossible (isaac-dgod)"
      ;; isaac-work-2 ran nineteen requests at 271k-304k against a 200k budget.
      ;; Every stamp was discarded for being "implausible", the gauge fell back
      ;; to its chars/4 tally of ~125k, and compaction never fired while the real
      ;; request was ~290k. A prompt over the window is the loudest compaction
      ;; trigger there is.
      (helper/create-session! test-dir "over-budget")
      (log/capture-logs
        (sut/process-response! {:charge {:context-window 200000}}
                               "over-budget"
                               {:usage    {:requests 1 :prompt-tokens 290000 :output-tokens 40}
                                :response {:content "Aye" :model "echo" :tool-calls [] :stop-reason :end-turn
                                           :usage {:prompt-tokens 290000 :output-tokens 40}}}
                               {:model "echo" :provider "grover:grok"})
        (let [session (helper/get-session test-dir "over-budget")]
          (should= 290000 (:last-input-tokens session))
          (should (compaction/should-compact? (compaction/context-gauge session) session 200000))
          (should-be-nil (first (filter #(= :session/stamp-implausible (:event %)) @log/captured-logs))))))

    (it "an adapter that cannot measure this request's prompt leaves the tally alone (isaac-dgod)"
      ;; :prompt-scope :unknown is the adapter saying so out loud. The gauge keeps
      ;; the last value it trusted and falls back to its own tally; no zero is
      ;; written (isaac-166j) and nothing is warned about.
      (helper/create-session! test-dir "declares-none")
      (helper/update-session! test-dir "declares-none" {:last-input-tokens 42000})
      (log/capture-logs
        (sut/process-response! {:charge {:context-window 200000}}
                               "declares-none"
                               {:usage    {:requests 1 :prompt-tokens 980000 :output-tokens 40}
                                :response {:content "Aye" :model "echo" :tool-calls [] :stop-reason :end-turn
                                           :usage {:prompt-tokens 980000 :output-tokens 40
                                                   :prompt-scope :unknown}}}
                               {:model "echo" :provider "grover:grok"})
        (let [session (helper/get-session test-dir "declares-none")]
          (should= 42000 (:last-input-tokens session))
          (should-be-nil (first (filter #(= :session/stamp-implausible (:event %)) @log/captured-logs))))))

    (it "a stateful provider whose adapter declares nothing is reporting a running sum (isaac-dgod)"
      ;; orchestration-verify stamped 12,031,158 of a 278,528 window with
      ;; cache-read 11,174,912 — the Responses :response-id chain's total, not a
      ;; prompt. A stateful adapter that has not been taught to say which it is
      ;; is an adapter bug, and that is all :session/stamp-implausible means now.
      (helper/create-session! test-dir "running-sum")
      (helper/update-session! test-dir "running-sum" {:last-input-tokens 42000})
      (log/capture-logs
        (sut/process-response! {:charge   {:context-window 200000}
                                :provider (->TestProvider marigold/starcore {:stateful true})}
                               "running-sum"
                               {:usage    {:requests 1 :prompt-tokens 12031158 :output-tokens 40}
                                :response {:content "Aye" :model "echo" :tool-calls [] :stop-reason :end-turn
                                           :usage {:prompt-tokens 12031158 :output-tokens 40}}}
                               {:model "echo" :provider "chatgpt"})
        (let [session (helper/get-session test-dir "running-sum")
              warned  (first (filter #(= :session/stamp-implausible (:event %)) @log/captured-logs))]
          (should= 42000 (:last-input-tokens session))
          (should= 12031158 (:prompt-tokens warned))
          (should= 200000 (:context-window warned)))))

    (it "a stateful provider whose adapter reports per request is taken at its word (isaac-dgod)"
      (helper/create-session! test-dir "stateful-declared")
      (log/capture-logs
        (sut/process-response! {:charge   {:context-window 200000}
                                :provider (->TestProvider marigold/starcore {:stateful true})}
                               "stateful-declared"
                               {:usage    {:requests 1 :prompt-tokens 150000 :output-tokens 40}
                                :response {:content "Aye" :model "echo" :tool-calls [] :stop-reason :end-turn
                                           :usage {:prompt-tokens 150000 :output-tokens 40
                                                   :prompt-scope :request}}}
                               {:model "echo" :provider "chatgpt"})
        (let [session (helper/get-session test-dir "stateful-declared")]
          (should= 150000 (:last-input-tokens session))
          (should-be-nil (first (filter #(= :session/stamp-implausible (:event %)) @log/captured-logs))))))
    )

  (describe "keep-cycle-usage! (isaac-ewxh)"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "keeps the completed cycles' sums when the turn ends in a provider error"
      ;; The seat's window closed on the eightieth cycle; the seventy-nine that
      ;; finished still cost what they cost.
      (helper/create-session! test-dir "walled")
      (helper/update-session! test-dir "walled" {:input-tokens 1000 :output-tokens 10 :last-input-tokens 15028})
      (sut/keep-cycle-usage! "walled"
                             {:error        :rate-limited
                              :unavailable? true
                              :message      "You've hit your session limit"
                              :usage        {:prompt-tokens 950 :output-tokens 21
                                             :cache-read-tokens 180 :cache-write-tokens 10}
                              :cycle-usages [{:prompt-tokens 260 :output-tokens 7}
                                             {:prompt-tokens 320 :output-tokens 7}
                                             {:prompt-tokens 370 :output-tokens 7}]})
      (let [session (helper/get-session test-dir "walled")]
        (should= 1950 (:input-tokens session))
        (should= 950 (:turn-input-tokens session))
        (should= 31 (:output-tokens session))
        (should= 370 (:last-input-tokens session))
        (should= 180 (:cache-read session))
        (should= 10 (:cache-write session))))

    (it "leaves the tally alone when the error reports no usage at all (isaac-166j)"
      (helper/create-session! test-dir "walled-cold")
      (helper/update-session! test-dir "walled-cold" {:input-tokens 1000 :last-input-tokens 15028})
      (sut/keep-cycle-usage! "walled-cold" {:error :rate-limited :unavailable? true})
      (let [session (helper/get-session test-dir "walled-cold")]
        (should= 1000 (:input-tokens session))
        (should= 15028 (:last-input-tokens session))))

    (it "keeps the sums but never stamps a zero prompt size (isaac-166j)"
      (helper/create-session! test-dir "walled-unmeasured")
      (helper/update-session! test-dir "walled-unmeasured" {:input-tokens 1000 :last-input-tokens 15028})
      (sut/keep-cycle-usage! "walled-unmeasured"
                             {:error        :rate-limited
                              :usage        {:prompt-tokens 260 :output-tokens 7}
                              :cycle-usages [{:prompt-tokens 0 :output-tokens 0}]})
      (let [session (helper/get-session test-dir "walled-unmeasured")]
        (should= 1260 (:input-tokens session))
        (should= 15028 (:last-input-tokens session))))

    (it "ignores a turn that ended cleanly — store-response! owns that tally"
      (helper/create-session! test-dir "clean")
      (helper/update-session! test-dir "clean" {:input-tokens 1000 :last-input-tokens 15028})
      (sut/keep-cycle-usage! "clean"
                             {:usage    {:prompt-tokens 950 :output-tokens 21}
                              :response {:content "done"}})
      (let [session (helper/get-session test-dir "clean")]
        (should= 1000 (:input-tokens session))
        (should= 15028 (:last-input-tokens session))))
    )

  (describe "empty terminal response guard"

    (it "retries once with a continuation nudge and accepts a non-empty follow-up"
      (let [requests (atom [])
            chat-fn  (fn [req]
                       (swap! requests conj req)
                       {:content "done." :model "test" :tool-calls [] :stop-reason :end-turn
                        :usage {:prompt-tokens 1 :output-tokens 1}})
            result   (#'sut/guard-empty-terminal-response
                       {:response {:content ""} :usage {:requests 1 :prompt-tokens 1 :output-tokens 0}}
                       chat-fn
                       {:messages [{:role "user" :content "status?"}]})]
        (should= "done." (#'sut/terminal-response-content result))
        (should= 1 (count @requests))))

    (it "fails explicitly when the continuation retry is also empty"
      (let [chat-fn (fn [_] {:content "" :model "test" :tool-calls [] :stop-reason :end-turn
                              :usage {:prompt-tokens 1 :output-tokens 0}})
            result  (#'sut/guard-empty-terminal-response
                      {:response {:content ""} :usage {:requests 1 :prompt-tokens 1 :output-tokens 0}}
                      chat-fn
                      {:messages [{:role "user" :content "status?"}]})]
        (should= :empty-terminal-response (:error result))))
    )

  (describe "streaming helpers"

    (it "reads normalized text deltas"
      (should= "hello" (#'sut/chunk-content {:text-delta "hello"}))
      (should-be-nil (#'sut/chunk-content {:reasoning-delta "thinking"})))

    (it "streams normalized deltas and returns the final normalized response"
      (let [chunks (atom [])]
        (with-redefs [dispatch/dispatch-chat-stream (fn [_ _ on-chunk]
                                                      (on-chunk {:text-delta "Hel"})
                                                      (on-chunk {:text-delta "lo"})
                                                      {:content "Hello" :tool-calls [] :stop-reason :end-turn
                                                       :model "test" :usage {:prompt-tokens 1 :output-tokens 1}})]
          (should= "Hello" (:content (sut/stream-response! :provider {:model "test"} #(swap! chunks conj %))))
          (should= ["Hel" "lo"] @chunks))))

    ))

  (describe "record-tool-call!"

    (it "records successful tool calls and emits call/result events"
      (let [events         (atom [])
            tool-count     (atom 0)
            registered     (atom nil)
            args-seen      (atom nil)]
        (with-redefs [bridge/on-cancel!           (fn [session-key cancel!]
                                                    (reset! registered [session-key cancel!])
                                                    nil)
                      tool-registry/execute       (fn [name args allowed-tools _module-index _caps]
                                                    (should= #{"search"} allowed-tools)
                                                    (reset! args-seen [name args])
                                                    {:result "ok"})
                      tool-registry/present-result (fn [raw-result]
                                                     (should= {:result "ok"} raw-result)
                                                     "ok")]
          (let [result (#'sut/record-tool-call! {:comm           (memory-comm/channel events)
                                                 :session-key    "tool-success"
                                                 :allowed-tools  #{"search"}
                                                 :tool-count     tool-count}
                                               "search"
                                               {"query" "logs"})]
            (should= "ok" result)
            (should= "search" (first @args-seen))
            (should= "logs" (get (second @args-seen) "query"))
            (should= "tool-success" (get (second @args-seen) "session_key"))
            (should (fn? (:progress! (second @args-seen))))
            (should= "tool-success" (first @registered))
            (should= 1 @tool-count)
            (should= ["tool-call" "tool-result"] (mapv :event @events)))))

    (it "emits tool-progress when the handler calls :progress!"
      (let [events     (atom [])
            tool-count (atom 0)]
        (tool-registry/clear!)
        (tool-registry/register! {:name        "test__sounding"
                                  :description "streaming mock"
                                  :parameters  {:type "object" :properties {}}
                                  :handler     (fn [args]
                                                 (let [progress! (:progress! args)]
                                                   (when progress!
                                                     (progress! "by the mark three")
                                                     (progress! "and a half three")))
                                                 {:result "depth 4"})})
        (with-redefs [bridge/on-cancel! (fn [_ _] nil)]
          (#'sut/record-tool-call! {:comm          (memory-comm/channel events)
                                    :session-key   "sounding"
                                    :allowed-tools #{"test__sounding"}
                                    :tool-count    tool-count}
                                   "test__sounding"
                                   {})
          (should= ["tool-call" "tool-progress" "tool-progress" "tool-result"]
                   (mapv :event @events))
          (should= ["by the mark three" "and a half three"]
                   (->> @events (filter #(= "tool-progress" (:event %))) (mapv :text))))))

    (it "runs after-result hooks after the tool result is emitted"
      (let [events        (atom [])
            tool-count    (atom 0)
            callback-seen (atom nil)]
        (with-redefs [bridge/on-cancel!           (fn [_ _] nil)
                      tool-registry/execute       (fn [_ _ _ _ _]
                                                    {:result "done"
                                                     :after-result! #(reset! callback-seen {:events     (mapv :event @events)
                                                                                            :tool-count @tool-count})})
                      tool-registry/present-result (fn [_] "done")]
          (#'sut/record-tool-call! {:comm           (memory-comm/channel events)
                                    :session-key    "tool-after-result"
                                    :allowed-tools  #{"search"}
                                    :tool-count     tool-count}
                                   "search"
                                   {"query" "depth"})
          (should= {:events ["tool-call" "tool-result"]
                    :tool-count 1}
                   @callback-seen))))

    (it "cancels and throws when a tool reports cancellation"
      (let [events         (atom [])
            tool-count     (atom 0)
            registered     (atom nil)]
        (with-redefs [bridge/on-cancel!     (fn [session-key cancel!]
                                              (reset! registered [session-key cancel!])
                                              nil)
                      tool-registry/execute (fn [_ _ allowed-tools module-index _caps]
                                              (should= #{"search"} allowed-tools)
                                              (should= {:modules true} module-index)
                                              {:error :cancelled})]
          (should-throw clojure.lang.ExceptionInfo
                        "cancelled"
                        (#'sut/record-tool-call! {:comm           (memory-comm/channel events)
                                                  :session-key    "tool-cancelled"
                                                  :allowed-tools  #{"search"}
                                                  :module-index   {:modules true}
                                                  :tool-count     tool-count}
                                                "search"
                                                {"query" "logs"}))
          (should= "tool-cancelled" (first @registered))
          (should= 0 @tool-count)
          (should= ["tool-call" "tool-cancel"] (mapv :event @events))))))


  (describe "allowed tools consult tool providers (isaac-vadd)"

    (it "a ns/* allow makes the provider's tools candidates for the cascade"
      (nexus/-with-nexus {:tool-registry (atom {})}
        (let [crew-members {"main" {:tools {:allow [:lens/*]}}}
              config       {:module-index {:isaac.tool.lens {:manifest {:isaac.agent/tool-providers {:lens {:ensure! 'isaac.drive.turn-spec/lens-provider}}}}}}]
          (should= #{"lens__catalog" "lens__read"}
                   (#'sut/allowed-tool-names crew-members "main" config)))))

    (it "a glob allow without a module index offers nothing"
      (nexus/-with-nexus {:tool-registry (atom {})}
        (should= #{} (#'sut/allowed-tool-names {"main" {:tools {:allow [:lens/*]}}} "main" {})))))

  (describe "mid-loop transcript flush"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "writes the assistant toolCall before the tool runs"
      (helper/create-session! test-dir "mid-flush-call")
      (let [events         (atom [])
            tool-count     (atom 0)
            seen-mid       (atom nil)
            ctx            {:session-store (store/registered-store)}]
        (with-redefs [bridge/on-cancel!           (fn [_ _] nil)
                      tool-registry/execute       (fn [_ _ _ _ _]
                                                    (reset! seen-mid
                                                            (->> (helper/get-transcript test-dir "mid-flush-call")
                                                                 (keep #(get-in % [:message :content]))
                                                                 flatten
                                                                 (filter #(= "toolCall" (:type %)))
                                                                 last))
                                                    {:result "ok"})
                      tool-registry/present-result (fn [_] "ok")]
          (#'sut/record-tool-call! {:comm           (memory-comm/channel events)
                                    :session-key    "mid-flush-call"
                                    :allowed-tools  #{"search"}
                                    :tool-count     tool-count
                                    :ctx            ctx}
                                   "search"
                                   {"query" "logs"})
          (should-not-be-nil @seen-mid)
          (should= "search" (:name @seen-mid))
          (should-not-be-nil (:id @seen-mid))
          (should= "toolCall" (:type @seen-mid)))))

    (it "writes the toolResult immediately after the tool returns"
      (helper/create-session! test-dir "mid-flush-result")
      (let [events         (atom [])
            tool-count     (atom 0)
            ctx            {:session-store (store/registered-store)}]
        (with-redefs [bridge/on-cancel!           (fn [_ _] nil)
                      tool-registry/execute       (fn [_ _ _ _ _] {:result "ok"})
                      tool-registry/present-result (fn [_] "ok")]
          (#'sut/record-tool-call! {:comm           (memory-comm/channel events)
                                    :session-key    "mid-flush-result"
                                    :allowed-tools  #{"search"}
                                    :tool-count     tool-count
                                    :ctx            ctx}
                                   "search"
                                   {"query" "logs"})
          (let [entries   (helper/get-transcript test-dir "mid-flush-result")
                messages  (mapv :message entries)
                last-two  (take-last 2 messages)
                tc-id     (get-in (first last-two) [:content 0 :id])]
            (should= "assistant" (:role (first last-two)))
            (should= "toolCall" (get-in (first last-two) [:content 0 :type]))
            (should-not-be-nil tc-id)
            (should= "toolResult" (:role (second last-two)))
            (should= tc-id (:id (second last-two)))
            (should= "ok" (:content (second last-two)))))))

    (it "marks toolResult isError when the tool-fn returns an Error: string"
      (helper/create-session! test-dir "mid-flush-error")
      (let [events     (atom [])
            tool-count (atom 0)
            ctx        {:session-store (store/registered-store)}]
        (with-redefs [bridge/on-cancel!           (fn [_ _] nil)
                      tool-registry/execute       (fn [_ _ _ _ _] {:isError true :error "unknown tool: exec__run"})
                      tool-registry/present-result (fn [_] "Error: unknown tool: exec__run")]
          (#'sut/record-tool-call! {:comm           (memory-comm/channel events)
                                    :session-key    "mid-flush-error"
                                    :allowed-tools  #{}
                                    :tool-count     tool-count
                                    :ctx            ctx}
                                   "exec__run"
                                   {"command" "ls"})
          (let [result (->> (helper/get-transcript test-dir "mid-flush-error")
                            (map :message)
                            (filter #(= "toolResult" (:role %)))
                            last)]
            (should= true (:isError result))
            (should= "Error: unknown tool: exec__run" (:content result))))))

    (it "leaves a dangling toolCall and no result when the tool reports cancelled"
      (helper/create-session! test-dir "mid-flush-cancel")
      (let [events         (atom [])
            tool-count     (atom 0)
            ctx            {:session-store (store/registered-store)}]
        (with-redefs [bridge/on-cancel!     (fn [_ _] nil)
                      tool-registry/execute (fn [_ _ _ _ _] {:error :cancelled})]
          (should-throw clojure.lang.ExceptionInfo
                        "cancelled"
                        (#'sut/record-tool-call! {:comm           (memory-comm/channel events)
                                                  :session-key    "mid-flush-cancel"
                                                  :allowed-tools  #{"search"}
                                                  :tool-count     tool-count
                                                  :ctx            ctx}
                                                "search"
                                                {"query" "logs"}))
          (let [messages (mapv :message (helper/get-transcript test-dir "mid-flush-cancel"))]
            (should= 1 (count (filter #(= "toolCall" (get-in % [:content 0 :type])) messages)))
            (should= 0 (count (filter #(= "toolResult" (:role %)) messages)))))))

    (it "does not double-write tool pairs after a two-tool turn"
      (helper/create-session! test-dir "mid-flush-two")
      (let [events         (atom [])
            tool-count     (atom 0)
            ctx            {:session-store (store/registered-store)}
            rec            {:comm           (memory-comm/channel events)
                            :session-key    "mid-flush-two"
                            :allowed-tools  #{"search" "lookup"}
                            :tool-count     tool-count
                            :ctx            ctx}]
        (with-redefs [bridge/on-cancel!           (fn [_ _] nil)
                      tool-registry/execute       (fn [_ _ _ _ _] {:result "ok"})
                      tool-registry/present-result (fn [_] "ok")]
          (#'sut/record-tool-call! rec "search" {"query" "a"})
          (#'sut/record-tool-call! rec "lookup" {"query" "b"})
          (let [messages   (mapv :message (helper/get-transcript test-dir "mid-flush-two"))
                tool-calls (filter #(= "toolCall" (get-in % [:content 0 :type])) messages)
                results    (filter #(= "toolResult" (:role %)) messages)]
            (should= 2 (count tool-calls))
            (should= 2 (count results))
            (should= ["search" "lookup"] (mapv #(get-in % [:content 0 :name]) tool-calls)))))))
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

  (describe "run-compaction-check!"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "reports the transcript size and time spent in each check step"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "timed-check"
            session-store (store/registered-store)]
        (helper/create-session! test-dir session-key)
        (helper/append-message! test-dir session-key {:role "user" :content "Measure twice — naïve bytes."})
        (helper/append-message! test-dir session-key {:role "assistant" :content "Compact once."})
        (log/capture-logs
          (#'sut/run-compaction-check! session-key {:compaction     {:threshold 0.8}
                                                    :context-window 10000
                                                    :model          "test-model"
                                                    :provider       provider
                                                    :session-store  session-store}
                                             1
                                             false)
          (let [entry      (first (filter #(= :session/compaction-check (:event %)) @log/captured-logs))
                transcript (helper/get-transcript test-dir session-key)
                bytes      (binding [*print-namespace-maps* false]
                             (reduce + (map #(+ (alength (.getBytes (pr-str %) "UTF-8")) 1) transcript)))]
            (should= (count transcript) (:entry-count entry))
            (should= bytes (:transcript-bytes entry))
            (doseq [field [:entry-ms :transcript-ms :gauge-ms :plan-ms :config-ms :provider-ms :size-ms
                           :elapsed-ms]]
              (should (number? (get entry field))))))))
    )

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
            (#'sut/perform-compaction! "attempt-limit" 4 1200 {:context-window 1000
                                                                 :model "test-model"
                                                                 :provider provider
                                                                 :soul "You are Isaac."})
            (let [entry (first (filter #(= :session/compaction-stopped (:event %)) @log/captured-logs))]
              (should-not-be-nil entry)
              (should= :max-attempts (:reason entry))
              (should= 4 (:attempt entry)))))))

    (it "records failures and blocks the conversation after too many consecutive errors"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-fail"
            session-store (store/registered-store)
            events        (atom [])]
        (helper/create-session! test-dir session-key)
        (helper/update-session! test-dir session-key {:compaction {:consecutive-failures 2}})
        (with-redefs [compaction/compact! (fn [& _] {:error :rate-limited :message "Please retry later"})]
          (#'sut/perform-compaction! session-key 1 800 {:comm          (memory-comm/channel events)
                                                        :context-window 1000
                                                        :model         "test-model"
                                                        :provider      provider
                                                        :soul          "You are Isaac."
                                                        :root     test-dir
                                                        :session-store session-store})
          (let [session (helper/get-session test-dir session-key)]
            (should= :compaction-failed (get-in session [:block :reason]))
            (should-not-be-nil (get-in session [:block :at]))
            (should-not (contains? session :compaction-disabled))
            (should= {:consecutive-failures 3} (:compaction session))
            (should= {:event "bulletin"
                      :kind "compaction/failure"
                      :session session-key
                      :consecutive-failures 3
                      :error :rate-limited
                      :message "Please retry later"}
                     (event events "bulletin" "compaction/failure"))
            (should-be-nil (event events "bulletin" "compaction/disabled"))))))

    (it "resets failure state after a successful non-chunked compact without rechecking"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-success"
            session-store (store/registered-store)
            events        (atom [])]
        (helper/create-session! test-dir session-key)
        (helper/update-session! test-dir session-key {:last-input-tokens 800
                                                      :compaction        {:consecutive-failures 2}})
        (with-redefs [compaction/compact!               (fn [& _] {:summary "Shorter now"})
                      compaction/estimate-prompt-tokens (fn [_ _] 850)
                      sut/run-compaction-check!         (fn [& _] (throw (ex-info "should not re-run" {})))]
          (#'sut/perform-compaction! session-key 2 900 {:comm           (memory-comm/channel events)
                                                        :context-window 1000
                                                        :model          "test-model"
                                                        :provider       provider
                                                        :soul           "You are Isaac."
                                                        :root      test-dir
                                                        :session-store  session-store})
          (let [session (helper/get-session test-dir session-key)
                success (event events "bulletin" "compaction/success")]
            (should-be-nil (:block session))
            (should= {:consecutive-failures 0} (:compaction session))
            (should-not-be-nil success)
            (should= "Shorter now" (:summary success))
            (should= 50 (:tokens-saved success))
            (should (number? (:duration-ms success)))))))

    (it "does not recheck a non-chunked compact even when the estimate stays over threshold"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-floor"
            session-store (store/registered-store)
            events        (atom [])]
        (helper/create-session! test-dir session-key)
        (with-redefs [compaction/compact!               (fn [& _] {:summary "Floor still high"})
                      compaction/estimate-prompt-tokens (fn [_ _] 850)
                      compaction/should-compact?        (fn [tokens _entry window]
                                                          (>= tokens (* 0.8 window)))
                      sut/run-compaction-check!         (fn [& _] (throw (ex-info "should not re-run" {})))]
          (#'sut/perform-compaction! session-key 1 900 {:comm           (memory-comm/channel events)
                                                        :context-window 1000
                                                        :model          "test-model"
                                                        :provider       provider
                                                        :soul           "You are Isaac."
                                                        :root           test-dir
                                                        :session-store  session-store})
          (should-not-be-nil (event events "bulletin" "compaction/success")))))

    (it "does not recheck when the first splice already dropped below threshold"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-once"
            session-store (store/registered-store)
            events        (atom [])]
        (helper/create-session! test-dir session-key)
        (with-redefs [compaction/compact!               (fn [& _] {:summary "One pass"})
                      compaction/estimate-prompt-tokens (fn [_ _] 200)
                      compaction/should-compact?        (fn [tokens _entry window]
                                                          (>= tokens (* 0.8 window)))
                      sut/run-compaction-check!         (fn [& _] (throw (ex-info "should not re-run" {})))]
          (#'sut/perform-compaction! session-key 1 800 {:comm           (memory-comm/channel events)
                                                        :context-window 1000
                                                        :model          "test-model"
                                                        :provider       provider
                                                        :soul           "You are Isaac."
                                                        :root           test-dir
                                                        :session-store  session-store})
          (should-not-be-nil (event events "bulletin" "compaction/success")))))

    (it "stops when compaction makes no token progress"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-stuck"
            session-store (store/registered-store)
            events        (atom [])]
        (helper/create-session! test-dir session-key)
        (helper/update-session! test-dir session-key {:last-input-tokens 800})
        (with-redefs [compaction/compact!               (fn [& _] {:summary "No progress"})
                      compaction/estimate-prompt-tokens (fn [_ _] 800)
                      sut/run-compaction-check!         (fn [& _] (throw (ex-info "should not re-run" {})))]
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
              (should-not-be-nil (event events "bulletin" "compaction/success")))))))

    (it "ignores a successor-session-key and measures the same session (isaac-mmod)"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-original"
            session-store (store/registered-store)
            events        (atom [])]
        (helper/create-session! test-dir session-key)
        (helper/update-session! test-dir session-key {:last-input-tokens 800})
        (with-redefs [compaction/compact!               (fn [_ _] {:summary "Shorter now"
                                                                   :successor-session-key "compact-successor"})
                      compaction/estimate-prompt-tokens (fn [_ _] 800)
                      sut/run-compaction-check!         (fn [& _] (throw (ex-info "should not re-run" {})))]
          (log/capture-logs
            (#'sut/perform-compaction! session-key 1 800 {:comm           (memory-comm/channel events)
                                                          :context-window 1000
                                                          :model          "test-model"
                                                          :provider       provider
                                                          :soul           "You are Isaac."
                                                          :root           test-dir
                                                          :session-store  session-store})
            (let [completed (first (filter #(= :session/compaction-completed (:event %)) @log/captured-logs))
                  stopped   (first (filter #(= :session/compaction-stopped (:event %)) @log/captured-logs))]
              (should-be-nil completed)
              (should-not-be-nil stopped)
              (should= :no-progress (:reason stopped))
              (should= session-key (:session stopped))
              (should-not-be-nil (event events "bulletin" "compaction/success")))))))

    (it "treats a successor-container chain as progress even when the live estimate stays put (isaac-jom5)"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "lantern-room"
            session-store (store/registered-store)
            events        (atom [])]
        (helper/create-session! test-dir session-key)
        (helper/update-session! test-dir session-key {:last-input-tokens 800})
        (with-redefs [compaction/compact!               (fn [_ _] {:summary "Full summary of prior"
                                                                   :successor-container "2026-03-01-1000-aaaa"})
                      compaction/estimate-prompt-tokens (fn [_ _] 800)
                      sut/run-compaction-check!         (fn [& _] (throw (ex-info "should not re-run" {})))]
          (log/capture-logs
            (#'sut/perform-compaction! session-key 1 800 {:comm           (memory-comm/channel events)
                                                          :context-window 1000
                                                          :model          "test-model"
                                                          :provider       provider
                                                          :soul           "You are Isaac."
                                                          :root           test-dir
                                                          :session-store  session-store})
            (let [completed (first (filter #(= :session/compaction-completed (:event %)) @log/captured-logs))
                  stopped   (first (filter #(= :session/compaction-stopped (:event %)) @log/captured-logs))]
              (should-not-be-nil completed)
              (should= session-key (:session completed))
              (should= "2026-03-01-1000-aaaa" (:successor completed))
              (should-be-nil stopped)
              (should-not-be-nil (event events "bulletin" "compaction/success")))))))

    (it "rechecks after a successful chunked compaction"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-chunked"
            session-store (store/registered-store)
            events        (atom [])
            follow-up     (atom nil)]
        (helper/create-session! test-dir session-key)
        (with-redefs [compaction/compact!               (fn [& _] {:summary "Chunked summary" :chunked true})
                      compaction/estimate-prompt-tokens (fn [_ _] 850)
                      sut/run-compaction-check!         (fn [next-session-key next-opts next-attempt allow-async?]
                                                          (reset! follow-up [next-session-key next-opts next-attempt allow-async?]))]
          (#'sut/perform-compaction! session-key 1 900 {:comm           (memory-comm/channel events)
                                                        :context-window 1000
                                                        :model          "test-model"
                                                        :provider       provider
                                                        :soul           "You are Isaac."
                                                        :root           test-dir
                                                        :session-store  session-store})
          (should-not-be-nil (event events "bulletin" "compaction/success"))
          (should= session-key (first @follow-up))
          (should= 2 (nth @follow-up 2))
          (should= false (nth @follow-up 3)))))

    (it "rechecks after an oversized-single splice that still sits over threshold"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-oversized"
            session-store (store/registered-store)
            events        (atom [])
            follow-up     (atom nil)]
        (helper/create-session! test-dir session-key)
        (with-redefs [compaction/compact!               (fn [& _] {:summary "Partial summary" :partial true})
                      compaction/estimate-prompt-tokens (fn [_ _] 850)
                      compaction/should-compact?        (fn [tokens _entry window]
                                                          (>= tokens (* 0.8 window)))
                      sut/run-compaction-check!         (fn [next-session-key next-opts next-attempt allow-async?]
                                                          (reset! follow-up [next-session-key next-opts next-attempt allow-async?]))]
          (#'sut/perform-compaction! session-key 1 900 {:comm           (memory-comm/channel events)
                                                        :context-window 1000
                                                        :model          "test-model"
                                                        :provider       provider
                                                        :soul           "You are Isaac."
                                                        :root           test-dir
                                                        :session-store  session-store})
          (should-not-be-nil (event events "bulletin" "compaction/success"))
          (should= session-key (first @follow-up))
          (should= 2 (nth @follow-up 2))
          (should= false (nth @follow-up 3)))))

    (it "does not increment consecutive-failures when compact! succeeds after a half-size retry"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-retry-ok"
            session-store (store/registered-store)
            events        (atom [])]
        (helper/create-session! test-dir session-key)
        (helper/update-session! test-dir session-key {:compaction {:consecutive-failures 0}})
        (with-redefs [compaction/compact!               (fn [& _] {:summary "Retried summary"})
                      compaction/estimate-prompt-tokens (fn [_ _] 200)]
          (#'sut/perform-compaction! session-key 1 800 {:comm           (memory-comm/channel events)
                                                        :context-window 1000
                                                        :model          "test-model"
                                                        :provider       provider
                                                        :soul           "You are Isaac."
                                                        :root           test-dir
                                                        :session-store  session-store})
          (let [session (helper/get-session test-dir session-key)]
            (should= {:consecutive-failures 0} (:compaction session))))))

    (it "increments consecutive-failures when compact! still errors after the half-size retry"
      (let [provider      (->TestProvider marigold/starcore {:api marigold/sky-api})
            session-key   "compact-retry-fail"
            session-store (store/registered-store)
            events        (atom [])]
        (helper/create-session! test-dir session-key)
        (helper/update-session! test-dir session-key {:compaction {:consecutive-failures 0}})
        (with-redefs [compaction/compact! (fn [& _] {:error :stream-stalled :message "closed"})]
          (#'sut/perform-compaction! session-key 1 800 {:comm           (memory-comm/channel events)
                                                        :context-window 1000
                                                        :model          "test-model"
                                                        :provider       provider
                                                        :soul           "You are Isaac."
                                                        :root           test-dir
                                                        :session-store  session-store})
          (let [session (helper/get-session test-dir session-key)]
            (should= {:consecutive-failures 1} (:compaction session)))))))

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
                                                           :tool-names #{"skill__list" "skill__load"}})]
          (let [turn (#'sut/build-turn charge)]
            (should= ["skill__list" "skill__load"] (sort (:allowed-tools turn)))))))

    (it "inherits global :allow :all when the crew omits :tools"
      (helper/create-session! test-dir "inherit-all")
      (helper/update-session! test-dir "inherit-all" {:crew "main"})
      (tool-registry/clear!)
      (tool-registry/register! {:name "fs__read" :description "Read" :handler identity})
      (tool-registry/register! {:name "exec__run" :description "Exec" :handler identity})
      (let [provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "inherit-all"
                      :input          "hi"
                      :comm           :test-comm
                      :config         {:root test-dir :defaults {:crew {:tools {:allow :all}}}}
                      :crew           "main"
                      :crew-members   {"main" {:model "spark"}}
                      :context-window 32768
                      :model          "helm-spark-1.0"
                      :provider       provider
                      :soul           "You are Isaac."}]
        (with-redefs [sut/augment-provider (fn [_root p _session-key _context-window _model-cfg-overrides] p)
                      session-ctx/read-skill-disclosure (fn [& _] {:menu-text nil :tool-names #{}})]
          (let [turn (#'sut/build-turn charge)]
            (should= ["exec__run" "fs__read"] (sort (:allowed-tools turn)))))))

    (it "lets crew allow re-enable a globally denied tool"
      (helper/create-session! test-dir "crew-reallow")
      (helper/update-session! test-dir "crew-reallow" {:crew "main"})
      (tool-registry/clear!)
      (tool-registry/register! {:name "fs__read" :description "Read" :handler identity})
      (tool-registry/register! {:name "exec__run" :description "Exec" :handler identity})
      (let [provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "crew-reallow"
                      :input          "hi"
                      :comm           :test-comm
                      :config         {:root test-dir :defaults {:crew {:tools {:allow :all :deny [:exec/run]}}}}
                      :crew           "main"
                      :crew-members   {"main" {:model "spark" :tools {:allow [:exec/run]}}}
                      :context-window 32768
                      :model          "helm-spark-1.0"
                      :provider       provider
                      :soul           "You are Isaac."}]
        (with-redefs [sut/augment-provider (fn [_root p _session-key _context-window _model-cfg-overrides] p)
                      session-ctx/read-skill-disclosure (fn [& _] {:menu-text nil :tool-names #{}})]
          (let [turn (#'sut/build-turn charge)]
            (should= ["exec__run" "fs__read"] (sort (:allowed-tools turn)))))))

    (it "overlays crew deny without dropping a global deny"
      (helper/create-session! test-dir "crew-overlay")
      (helper/update-session! test-dir "crew-overlay" {:crew "main"})
      (tool-registry/clear!)
      (tool-registry/register! {:name "fs__read" :description "Read" :handler identity})
      (tool-registry/register! {:name "exec__run" :description "Exec" :handler identity})
      (tool-registry/register! {:name "web__fetch" :description "Fetch" :handler identity})
      (let [provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "crew-overlay"
                      :input          "hi"
                      :comm           :test-comm
                      :config         {:root test-dir :defaults {:crew {:tools {:allow :all :deny [:exec/run]}}}}
                      :crew           "main"
                      :crew-members   {"main" {:model "spark" :tools {:deny [:fs/*]}}}
                      :context-window 32768
                      :model          "helm-spark-1.0"
                      :provider       provider
                      :soul           "You are Isaac."}]
        (with-redefs [sut/augment-provider (fn [_root p _session-key _context-window _model-cfg-overrides] p)
                      session-ctx/read-skill-disclosure (fn [& _] {:menu-text nil :tool-names #{}})]
          (let [turn (#'sut/build-turn charge)]
            (should= ["web__fetch"] (sort (:allowed-tools turn))))))))

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
                                                   :crew         "main"
                                                   :context-mode nil
                                                   :comm         null-comm/channel})]
        (with-redefs [tool-loop/run (fn [_ _ request _ _]
                                      (reset! captured request)
                                      {:response {:content "Try to take over the world." :model "test-model"
                                                    :tool-calls [] :stop-reason :end-turn
                                                    :usage {:prompt-tokens 1 :output-tokens 1}}
                                       :usage {:requests 1 :prompt-tokens 1 :output-tokens 1}
                                       :tool-calls []})
                      sut/process-response! (fn [& _] nil)]
          (#'sut/execute-llm-turn! "full-history" "Are the blueprints ready?" ctx))
         (should= [{:role "system" :content (str "You are Brain.\n\n" turn-instructions/parallel-tool-calls-hint "\n\nSession: full-history\nCrew: main")}
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
                                                   :crew         "pinky"
                                                   :context-mode :reset
                                                   :comm         null-comm/channel})]
        (with-redefs [tool-loop/run (fn [_ _ request _ _]
                                      (reset! captured request)
                                      {:response {:content "Logged. Narf!" :model "test-model" :tool-calls []
                                                    :stop-reason :end-turn
                                                    :usage {:prompt-tokens 1 :output-tokens 1}}
                                       :usage {:requests 1 :prompt-tokens 1 :output-tokens 1}
                                       :tool-calls []})
                      sut/process-response! (fn [& _] nil)]
          (#'sut/execute-llm-turn! "reset-history" "Brain escaped the cage." ctx))
         (should= [{:role "system" :content (str "You are Pinky.\n\n" turn-instructions/parallel-tool-calls-hint "\n\nSession: reset-history\nCrew: pinky")}
                  {:role "user" :content "Brain escaped the cage."}]
                 (:messages @captured))))

    )

  (describe "prompt-too-long overflow compact-and-retry"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "compacts and retries after a prompt-too-long 400"
      (helper/create-session! test-dir "overflow-retry")
      (helper/append-message! test-dir "overflow-retry" {:role "user" :content "older ask"})
      (helper/append-message! test-dir "overflow-retry" {:role "assistant" :content "older reply"})
      (let [calls     (atom 0)
            compact-n (atom 0)
            ctx       (base-execution-ctx
                        (->TestProvider marigold/starcore {:api marigold/sky-api})
                        {:model          "test-model"
                         :soul           "You are Isaac."
                         :crew           "main"
                         :comm           null-comm/channel
                         :context-window 200
                         :config         {}})]
        (with-redefs [tool-loop/run (fn [_ _ _ _ _]
                                      (swap! calls inc)
                                      (if (= 1 @calls)
                                        {:error :context-overflow
                                         :status 400
                                         :message "maximum prompt length is 200 but the request contains 250"}
                                        {:response {:content "here is my answer" :model "test-model" :tool-calls []
                                                    :stop-reason :end-turn
                                                    :usage {:prompt-tokens 1 :output-tokens 1}}
                                         :usage {:requests 1 :prompt-tokens 1 :output-tokens 1}
                                         :tool-calls []}))
                      compaction/compact! (fn [session-key _opts]
                                            (swap! compact-n inc)
                                            (helper/splice-compaction! test-dir session-key
                                                                       {:summary           "summary of A"
                                                                        :tokensBefore      250
                                                                        :compactedEntryIds []
                                                                        :firstKeptEntryId  nil})
                                            {:summary "summary of A"})]
          (let [result (#'sut/execute-llm-turn! "overflow-retry" "go" ctx)]
            (should= 1 @compact-n)
            (should= 2 @calls)
            (should-be-nil (:error result))
            (should-not (:unavailable? result))))))

    (it "returns context exhaustion when the compact-and-retry still overflows"
      (helper/create-session! test-dir "overflow-exhausted")
      (let [calls (atom 0)
            ctx   (base-execution-ctx
                    (->TestProvider marigold/starcore {:api marigold/sky-api})
                    {:model          "test-model"
                     :soul           "You are Isaac."
                     :crew           "main"
                     :comm           null-comm/channel
                     :context-window 200
                     :config         {}})]
        (with-redefs [tool-loop/run (fn [& _]
                                      (swap! calls inc)
                                      {:error :context-overflow
                                       :status 400
                                       :message "maximum prompt length is 200 but the request contains 250"})
                      compaction/compact! (fn [_session-key _opts] {:summary "summary of A"})]
          (let [result (#'sut/execute-llm-turn! "overflow-exhausted" "go" ctx)]
            (should= 2 @calls)
            (should= true (:unavailable? result))
            (should= :context-exhausted (:reason result))))))

    (it "returns blocked weather when overflow happens on a blocked conversation"
      (helper/create-session! test-dir "overflow-blocked")
      (helper/update-session! test-dir "overflow-blocked" {:block {:reason :compaction-failed :at "2026-09-04T10:00:00"}})
      (let [compact-n (atom 0)
            ctx       (base-execution-ctx
                        (->TestProvider marigold/starcore {:api marigold/sky-api})
                        {:model          "test-model"
                         :soul           "You are Isaac."
                         :crew           "main"
                         :comm           null-comm/channel
                         :context-window 200
                         :config         {}})]
        (with-redefs [tool-loop/run (fn [_ _ _ _ _]
                                      {:error :context-overflow
                                       :status 400
                                       :message "maximum prompt length is 200 but the request contains 250"})
                      compaction/compact! (fn [& _]
                                            (swap! compact-n inc)
                                            {:summary "should-not-run"})]
          (let [result (#'sut/execute-llm-turn! "overflow-blocked" "one more" ctx)]
            (should= 0 @compact-n)
            (should= true (:unavailable? result))
            (should= :blocked (:reason result)))))))

  (describe "maybe-blocked-conversation!"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "refuses a blocked conversation before any LLM work"
      (helper/create-session! test-dir "skybeam")
      (helper/update-session! test-dir "skybeam" {:block {:reason :compaction-failed :at "2026-09-04T10:00:00"}
                                                 :last-input-tokens 99})
      (let [ctx (base-execution-ctx
                  (->TestProvider marigold/starcore {:api marigold/sky-api})
                  {:model          "test-model"
                   :soul           "You are Isaac."
                   :crew           "main"
                   :comm           null-comm/channel
                   :context-window 100
                   :config         {}})]
        (let [result (#'sut/maybe-blocked-conversation! "skybeam" ctx)]
          (should= true (:unavailable? result))
          (should= :blocked (:reason result))))))

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
        (should= 0 @register-calls)))

    (it "records a closing error and does not rethrow when the LLM throws"
      (helper/create-session! test-dir "crashy" {:crew "main"})
      (let [provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "crashy"
                      :input          "next thing"
                      :root           test-dir
                      :session-store  (store/registered-store)
                      :comm           null-comm/channel
                      :crew           "main"
                      :model          "test-model"
                      :provider       provider
                      :soul           "You are Isaac."
                      :context-window 4096}]
        (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                      tool-loop/run         (fn [& _] (throw (Exception. "wire format mismatch")))
                      sut/process-response! (fn [& _] nil)]
          (log/capture-logs
            (let [result (sut/run-turn! charge)
                  entry  (first (filter #(= :session/turn-failed (:event %)) @log/captured-logs))
                  last-e (last (helper/get-transcript test-dir "crashy"))]
              (should= :exception (:error result))
              (should= "wire format mismatch" (:message result))
              (should-not-be-nil entry)
              (should= :error (:level entry))
              (should= "crashy" (:session entry))
              (should= "error" (:type last-e))
              (should= "wire format mismatch" (:content last-e)))))))

    (it "records a closing error when a non-Exception Throwable escapes the turn"
      (helper/create-session! test-dir "crashy-err" {:crew "main"})
      (let [provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "crashy-err"
                      :input          "next thing"
                      :root           test-dir
                      :session-store  (store/registered-store)
                      :comm           null-comm/channel
                      :crew           "main"
                      :model          "test-model"
                      :provider       provider
                      :soul           "You are Isaac."
                      :context-window 4096}]
        (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                      tool-loop/run         (fn [& _] (throw (Error. "stack overflow")))
                      sut/process-response! (fn [& _] nil)]
          (let [result (sut/run-turn! charge)
                last-e (last (helper/get-transcript test-dir "crashy-err"))]
            (should= :exception (:error result))
            (should= "stack overflow" (:message result))
            (should= "error" (:type last-e))
            (should= "stack overflow" (:content last-e))))))

    (it "fires submitted observers around a successful turn"
      (helper/create-session! test-dir "lookout-ok" {:crew "main"})
      (let [events   (atom [])
            obs      (reify observer/TurnObserver
                       (on-turn-started [_ ctx] (swap! events conj [:started (:session-key ctx)]))
                       (on-turn-ended [_ ctx outcome] (swap! events conj [:ended (:session-key ctx) outcome]))
                       (on-turn-died [_ _ _]))
            provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "lookout-ok"
                      :input          "scan"
                      :root           test-dir
                      :session-store  (store/registered-store)
                      :comm           null-comm/channel
                      :crew           "main"
                      :model          "test-model"
                      :provider       provider
                      :soul           "You are Isaac."
                      :context-window 4096
                      :observers      [obs]}]
        (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                      tool-loop/run         (fn [& _] {:response {:content "Land ho ahead" :model "test-model" :tool-calls []
                                                                    :stop-reason :end-turn
                                                                    :usage {:prompt-tokens 1 :output-tokens 1}}
                                                       :usage {:requests 1 :prompt-tokens 1 :output-tokens 1}
                                                       :tool-calls []})
                      sut/process-response! (fn [& _] nil)]
          (sut/run-turn! charge))
        (should= [[:started "lookout-ok"] [:ended "lookout-ok" :ok]] @events)))

    (it "fires observers with turn-died when the turn throws"
      (helper/create-session! test-dir "lookout-boom" {:crew "main"})
      (let [events   (atom [])
            obs      (reify observer/TurnObserver
                       (on-turn-started [_ _] (swap! events conj :started))
                       (on-turn-ended [_ _ outcome] (swap! events conj [:ended outcome]))
                       (on-turn-died [_ _ reason] (swap! events conj [:died reason])))
            provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "lookout-boom"
                      :input          "scan"
                      :root           test-dir
                      :session-store  (store/registered-store)
                      :comm           null-comm/channel
                      :crew           "main"
                      :model          "test-model"
                      :provider       provider
                      :soul           "You are Isaac."
                      :context-window 4096
                      :observers      [obs]}]
        (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                      tool-loop/run         (fn [& _] (throw (Exception. "fog rolled in")))
                      sut/process-response! (fn [& _] nil)]
          (sut/run-turn! charge))
        (should= [:started [:died "fog rolled in"]] @events)))

    (it "fires observers with turn-died when a non-Exception Throwable escapes"
      (helper/create-session! test-dir "lookout-err" {:crew "main"})
      (let [events   (atom [])
            obs      (reify observer/TurnObserver
                       (on-turn-started [_ _] (swap! events conj :started))
                       (on-turn-ended [_ _ outcome] (swap! events conj [:ended outcome]))
                       (on-turn-died [_ _ reason] (swap! events conj [:died reason])))
            provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "lookout-err"
                      :input          "scan"
                      :root           test-dir
                      :session-store  (store/registered-store)
                      :comm           null-comm/channel
                      :crew           "main"
                      :model          "test-model"
                      :provider       provider
                      :soul           "You are Isaac."
                      :context-window 4096
                      :observers      [obs]}]
        (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                      tool-loop/run         (fn [& _] (throw (Error. "stack overflow")))
                      sut/process-response! (fn [& _] nil)]
          (sut/run-turn! charge))
        (should= [:started [:died "stack overflow"]] @events)))

    (it "fires turn-ended, not turn-died, when the provider returns an error reply"
      (helper/create-session! test-dir "lookout-http" {:crew "main"})
      (let [events   (atom [])
            obs      (reify observer/TurnObserver
                       (on-turn-started [_ _] (swap! events conj :started))
                       (on-turn-ended [_ _ outcome] (swap! events conj [:ended outcome]))
                       (on-turn-died [_ _ reason] (swap! events conj [:died reason])))
            provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "lookout-http"
                      :input          "scan"
                      :root           test-dir
                      :session-store  (store/registered-store)
                      :comm           null-comm/channel
                      :crew           "main"
                      :model          "test-model"
                      :provider       provider
                      :soul           "You are Isaac."
                      :context-window 4096
                      :observers      [obs]}]
        (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                      tool-loop/run         (fn [& _] {:error :http-error :status 403 :message "fog rolled in"})
                      sut/process-response! (fn [_ _ result _] result)]
          (sut/run-turn! charge))
        (should= :started (first @events))
        (should= :ended (first (second @events)))
        (should= :error (get-in @events [1 1 :kind]))
        (should= 2 (count @events))))

    (it "fires an ambient observer with turn-died when the turn throws"
      (helper/create-session! test-dir "ambient-died" {:crew "main"})
      (let [events   (atom [])
            ambient  (reify observer/TurnObserver
                       (on-turn-started [_ ctx] (swap! events conj [:started (:session-key ctx)]))
                       (on-turn-ended [_ _ outcome] (swap! events conj [:ended outcome]))
                       (on-turn-died [_ ctx reason] (swap! events conj [:died (:session-key ctx) reason])))
            provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "ambient-died"
                      :input          "scan"
                      :root           test-dir
                      :session-store  (store/registered-store)
                      :comm           null-comm/channel
                      :crew           "main"
                      :model          "test-model"
                      :provider       provider
                      :soul           "You are Isaac."
                      :context-window 4096}]
        (try
          (observer/attach! ambient)
          (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                        tool-loop/run         (fn [& _] (throw (Exception. "crow's nest exploded")))
                        sut/process-response! (fn [& _] nil)]
            (sut/run-turn! charge))
          (finally
            (observer/clear-ambient!)))
        (should= [[:started "ambient-died"] [:died "ambient-died" "crow's nest exploded"]] @events)))

    (it "isolates a throwing died observer so finalization continues"
      (helper/create-session! test-dir "died-boom" {:crew "main"})
      (let [events    (atom [])
            exploding (reify observer/TurnObserver
                        (on-turn-started [_ _])
                        (on-turn-ended [_ _ _])
                        (on-turn-died [_ _ _] (throw (Exception. "lookout exploded"))))
            witness   (reify observer/TurnObserver
                        (on-turn-started [_ _])
                        (on-turn-ended [_ _ outcome] (swap! events conj [:ended outcome]))
                        (on-turn-died [_ _ reason] (swap! events conj [:died reason])))
            provider  (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge    {:charge/type    :charge
                       :session-key    "died-boom"
                       :input          "scan"
                       :root           test-dir
                       :session-store  (store/registered-store)
                       :comm           null-comm/channel
                       :crew           "main"
                       :model          "test-model"
                       :provider       provider
                       :soul           "You are Isaac."
                       :context-window 4096
                       :observers      [exploding witness]}]
        (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                      tool-loop/run         (fn [& _] (throw (Exception. "fog rolled in")))
                      sut/process-response! (fn [& _] nil)]
          (log/capture-logs
            (let [result (sut/run-turn! charge)
                  entry  (first (filter #(= :turn/observer-failed (:event %)) @log/captured-logs))]
              (should= :exception (:error result))
              (should= :warn (:level entry))
              (should= "lookout exploded" (:error entry)))))
        (should= [[:died "fog rolled in"]] @events)))

    (it "fires an ambient observer on a turn with no submitted observers"
      (helper/create-session! test-dir "ambient-ok" {:crew "main"})
      (let [events   (atom [])
            ambient  (reify observer/TurnObserver
                       (on-turn-started [_ ctx] (swap! events conj [:started (:session-key ctx)]))
                       (on-turn-ended [_ ctx outcome] (swap! events conj [:ended (:session-key ctx) outcome]))
                       (on-turn-died [_ _ _]))
            provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type    :charge
                      :session-key    "ambient-ok"
                      :input          "scan"
                      :root           test-dir
                      :session-store  (store/registered-store)
                      :comm           null-comm/channel
                      :crew           "main"
                      :model          "test-model"
                      :provider       provider
                      :soul           "You are Isaac."
                      :context-window 4096}]
        (try
          (observer/attach! ambient)
          (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                        tool-loop/run         (fn [& _] {:response {:content "Land ho ahead" :model "test-model" :tool-calls []
                                                                      :stop-reason :end-turn
                                                                      :usage {:prompt-tokens 1 :output-tokens 1}}
                                                         :usage {:requests 1 :prompt-tokens 1 :output-tokens 1}
                                                         :tool-calls []})
                        sut/process-response! (fn [& _] nil)]
            (sut/run-turn! charge))
          (finally
            (observer/clear-ambient!)))
        (should= [[:started "ambient-ok"] [:ended "ambient-ok" :ok]] @events)))

    (it "fires ambient and submitted observers together on the same turn"
      (helper/create-session! test-dir "both-watch" {:crew "main"})
      (let [events    (atom [])
            ambient   (reify observer/TurnObserver
                        (on-turn-started [_ _] (swap! events conj :ambient-started))
                        (on-turn-ended [_ _ _] (swap! events conj :ambient-ended))
                        (on-turn-died [_ _ _]))
            submitted (reify observer/TurnObserver
                        (on-turn-started [_ _] (swap! events conj :submitted-started))
                        (on-turn-ended [_ _ _] (swap! events conj :submitted-ended))
                        (on-turn-died [_ _ _]))
            provider  (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge    {:charge/type    :charge
                       :session-key    "both-watch"
                       :input          "scan"
                       :root           test-dir
                       :session-store  (store/registered-store)
                       :comm           null-comm/channel
                       :crew           "main"
                       :model          "test-model"
                       :provider       provider
                       :soul           "You are Isaac."
                       :context-window 4096
                       :observers      [submitted]}]
        (try
          (observer/attach! ambient)
          (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                        tool-loop/run         (fn [& _] {:response {:content "Land ho ahead" :model "test-model" :tool-calls []
                                                                      :stop-reason :end-turn
                                                                      :usage {:prompt-tokens 1 :output-tokens 1}}
                                                         :usage {:requests 1 :prompt-tokens 1 :output-tokens 1}
                                                         :tool-calls []})
                        sut/process-response! (fn [& _] nil)]
            (sut/run-turn! charge))
          (finally
            (observer/clear-ambient!)))
        (should= [:ambient-started :submitted-started :ambient-ended :submitted-ended] @events)))

    (it "isolates a throwing ambient observer so the turn still finishes"
      (helper/create-session! test-dir "ambient-boom" {:crew "main"})
      (let [events    (atom [])
            ambient   (reify observer/TurnObserver
                        (on-turn-started [_ _] (throw (Exception. "crow's nest exploded")))
                        (on-turn-ended [_ _ _] (swap! events conj :ambient-ended))
                        (on-turn-died [_ _ _]))
            submitted (reify observer/TurnObserver
                        (on-turn-started [_ _] (swap! events conj :submitted-started))
                        (on-turn-ended [_ _ outcome] (swap! events conj [:submitted-ended outcome]))
                        (on-turn-died [_ _ _]))
            provider  (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge    {:charge/type    :charge
                       :session-key    "ambient-boom"
                       :input          "scan"
                       :root           test-dir
                       :session-store  (store/registered-store)
                       :comm           null-comm/channel
                       :crew           "main"
                       :model          "test-model"
                       :provider       provider
                       :soul           "You are Isaac."
                       :context-window 4096
                       :observers      [submitted]}]
        (try
          (observer/attach! ambient)
          (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                        tool-loop/run         (fn [& _] {:response {:content "Land ho ahead" :model "test-model" :tool-calls []
                                                                      :stop-reason :end-turn
                                                                      :usage {:prompt-tokens 1 :output-tokens 1}}
                                                         :usage {:requests 1 :prompt-tokens 1 :output-tokens 1}
                                                         :tool-calls []})
                        sut/process-response! (fn [& _] nil)]
            (log/capture-logs
              (let [result (sut/run-turn! charge)
                    entry  (first (filter #(= :turn/observer-failed (:event %)) @log/captured-logs))]
                (should-be-nil (:error result))
                (should= :warn (:level entry))
                (should= "crow's nest exploded" (:error entry)))))
          (finally
            (observer/clear-ambient!)))
        (should= [:submitted-started :ambient-ended [:submitted-ended :ok]] @events)))

    (it "releases acquired turnstile tokens after a successful turn"
      (helper/create-session! test-dir "gate-ok" {:crew "main"})
      (let [events   (atom [])
            gate     (reify turnstile/Turnstile
                       (admit? [_ _] :pass)
                       (release! [_ token] (swap! events conj [:release token])))
            token    (turnstile/->ReleaseToken "tok-ok")
            provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type       :charge
                      :session-key       "gate-ok"
                      :input             "scan"
                      :root              test-dir
                      :session-store     (store/registered-store)
                      :comm              null-comm/channel
                      :crew              "main"
                      :model             "test-model"
                      :provider          provider
                      :soul              "You are Isaac."
                      :context-window    4096
                      :turnstile-tokens  [{:turnstile gate :token token}]}]
        (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                      tool-loop/run         (fn [& _] {:response {:content "Land ho ahead" :model "test-model" :tool-calls []
                                                                    :stop-reason :end-turn
                                                                    :usage {:prompt-tokens 1 :output-tokens 1}}
                                                       :usage {:requests 1 :prompt-tokens 1 :output-tokens 1}
                                                       :tool-calls []})
                      sut/process-response! (fn [& _] nil)]
          (sut/run-turn! charge))
        (should= [[:release token]] @events)))

    (it "prefers charge cycle limit over crew and defaults"
      (should= 12 (#'sut/resolve-cycle-limit {:cycle       {:limit 12}
                                              :crew-cfg    {:cycle {:limit 3}}
                                              :crew        "oscar"
                                              :config      {:defaults {:crew {:cycle {:limit 5}}}
                                                            :crew     {"oscar" {:cycle {:limit 3}}}}}))
      (should= 3 (#'sut/resolve-cycle-limit {:crew-cfg {:cycle {:limit 3}}
                                            :crew     "oscar"
                                            :config   {:defaults {:crew {:cycle {:limit 5}}}
                                                       :crew     {"oscar" {:cycle {:limit 8}}}}}))
      (should= 5 (#'sut/resolve-cycle-limit {:crew   "oscar"
                                            :config {:defaults {:crew {:cycle {:limit 5}}}}}))
      (should= tool-loop/default-max-loops
               (#'sut/resolve-cycle-limit {:crew "oscar" :config {}})))

    (it "layers charge cycle map over crew and defaults"
      (let [cycle (#'sut/resolve-cycle {:cycle    {:checkpoint-every 1}
                                        :crew-cfg {:cycle {:limit 10 :checkpoint-every 5}}
                                        :crew     "oscar"
                                        :config   {:defaults {:crew {:cycle {:limit 100}}}}})]
        (should= 10 (:limit cycle))
        (should= 1 (:checkpoint-every cycle))))

    (it "the continuation budget defaults to 2 and layers like the rest of :cycle"
      (should= sut/default-continuations
               (:continuations (#'sut/resolve-cycle {:crew "oscar" :config {}})))
      (should= 2 sut/default-continuations)
      (should= 5 (:continuations (#'sut/resolve-cycle {:crew   "oscar"
                                                       :config {:defaults {:crew {:cycle {:continuations 5}}}}})))
      (should= 3 (:continuations (#'sut/resolve-cycle {:crew-cfg {:cycle {:continuations 3}}
                                                       :crew     "oscar"
                                                       :config   {:defaults {:crew {:cycle {:continuations 5}}}}})))
      (should= 1 (:continuations (#'sut/resolve-cycle {:cycle    {:continuations 1}
                                                       :crew-cfg {:cycle {:continuations 3}}
                                                       :crew     "oscar"
                                                       :config   {:defaults {:crew {:cycle {:continuations 5}}}}})))
      (should= 4 (:continuations (#'sut/resolve-cycle {:crew   "oscar"
                                                       :config {:defaults {:crew {:cycle {:continuations "4"}}}}}))))

    (it "reads the continuation count off the charge's origin"
      (should= 0 (#'sut/continuation-count {}))
      (should= 0 (#'sut/continuation-count {:origin {:kind :memory}}))
      (should= 2 (#'sut/continuation-count {:origin {:kind :memory :continuation 2}})))

    (it "plans a continuation only for a wrapped-up turn, and only within budget"
      (let [wrapped {:ended-by :cycle-limit :exhaustion :wrapped-up}]
        (should-be-nil (#'sut/continuation-plan {:ended-by :reply} 0 2))
        (should-be-nil (#'sut/continuation-plan {:ended-by :cycle-limit :exhaustion :stopped} 0 2))
        (should-be-nil (#'sut/continuation-plan {:ended-by :error :error :exception} 0 2))
        (should= {:action :continue :continuation 1 :budget 2}
                 (#'sut/continuation-plan wrapped 0 2))
        (should= {:action :continue :continuation 2 :budget 2}
                 (#'sut/continuation-plan wrapped 1 2))
        (should= {:action :exhausted :continuation 2 :budget 2}
                 (#'sut/continuation-plan wrapped 2 2))
        (should= {:action :exhausted :continuation 0 :budget 0}
                 (#'sut/continuation-plan wrapped 0 0))))

    (it "the continuation note tells the model to continue from its own wrap-up note"
      (should (re-find #"continue" sut/continuation-note))
      (should (re-find #"(?i)note" sut/continuation-note)))

    (it "default wrap-up and checkpoint prompts are task-agnostic"
      (doseq [word ["git" "commit" "branch" "bean" "test" "verify"]]
        (should-not (re-find (re-pattern (str "(?i)\\b" word "\\b")) sut/default-wrap-up-prompt))
        (should-not (re-find (re-pattern (str "(?i)\\b" word "\\b")) sut/default-checkpoint-prompt)))
      (should (re-find #"cycle budget for this turn is exhausted" sut/default-wrap-up-prompt))
      (should-not (re-find #"Your next turn resumes from this note" sut/default-wrap-up-prompt))
      (should (re-find #"Checkpoint: save work in progress" sut/default-checkpoint-prompt)))

    (it "a checkpoint is due on every Nth cycle and never when unset"
      (should (#'sut/checkpoint-due? 2 2))
      (should-not (#'sut/checkpoint-due? 2 1))
      (should-not (#'sut/checkpoint-due? nil 2))
      (should-not (#'sut/checkpoint-due? 0 2)))

    (it "coerces a string wrap-up answer from the comm"
      (let [ch (memory-comm/channel (atom []) ":wrap-up")]
        (should= :wrap-up (#'sut/exhaustion-policy ch "s" {:cycle-limit 1}))))

    (it "extracts grover-shaped pending tool calls from the exhausting response"
      (let [result {:response {:content "" :model "test" :stop-reason :tool-use
                                 :usage {:prompt-tokens 1 :output-tokens 1}
                                 :tool-calls [{:id "tc1" :name "exec__run"
                                               :arguments {:command "echo checkpoint"}}]}
                    :loop-request? true}]
        (should= [{:name "exec__run" :arguments {:command "echo checkpoint"}}]
                 (mapv #(select-keys % [:name :arguments])
                       (#'sut/pending-tool-calls result)))))

    (it "releases acquired turnstile tokens when the turn throws"
      (helper/create-session! test-dir "gate-boom" {:crew "main"})
      (let [events   (atom [])
            gate     (reify turnstile/Turnstile
                       (admit? [_ _] :pass)
                       (release! [_ token] (swap! events conj [:release token])))
            token    (turnstile/->ReleaseToken "tok-boom")
            provider (->TestProvider marigold/quantum-anvil {:api marigold/anvil-api})
            charge   {:charge/type       :charge
                      :session-key       "gate-boom"
                      :input             "scan"
                      :root              test-dir
                      :session-store     (store/registered-store)
                      :comm              null-comm/channel
                      :crew              "main"
                      :model             "test-model"
                      :provider          provider
                      :soul              "You are Isaac."
                      :context-window    4096
                      :turnstile-tokens  [{:turnstile gate :token token}]}]
        (with-redefs [sut/build-turn        (fn [c] (base-execution-ctx provider c))
                      tool-loop/run         (fn [& _] (throw (Exception. "fog rolled in")))
                      sut/process-response! (fn [& _] nil)]
          (sut/run-turn! charge))
        (should= [[:release token]] @events)))
    )

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
            result   {:response   {:content     "ok"
                                   :model       "test-model"
                                   :stop-reason :end-turn
                                   :tool-calls  []
                                   :usage       {:output-tokens 1 :prompt-tokens 1}}
                      :tool-calls []
                      :usage      {:output-tokens 1 :prompt-tokens 1 :requests 1}}]
        (config/dangerously-install-config! {:defaults {:frequencies {:crew "main"} :crew {:model "test"}}
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
              (should (number? (:build-ms request-entry)))
              (should= 1 (:selected-tools-count request-entry))
              (should= ["logbook-entry"] (:selected-tools request-entry))
              (should-not-be-nil response-entry)
              (should= "log-turn" (:session response-entry))
              (should= 2 (:assistant-content-chars response-entry))
              (should= 0 (:tool-calls-count response-entry))
              (should= 0 (:executed-tools-count response-entry)))))
        (config/dangerously-install-config! nil "spec"))))

  (describe "mid-turn compaction"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "compacts after a large tool result lands and rebuilds the next LLM request"
      (helper/create-session! test-dir "mid-compact")
      (let [captured  (atom [])
            queue     (atom [{:tool-calls [{:id "tc1" :name "dump" :arguments {}}]
                              :message    {:role "assistant" :content ""}
                              :model      "test-model"
                              :usage      {}}
                             {:message {:role "assistant" :content "done after compact"}
                              :model   "test-model"
                              :usage   {}}])
            estimate* (atom 10)
            compact-n (atom 0)
            provider  (->ScriptedPromptProvider marigold/starcore
                                                {:api marigold/sky-api :stream-supports-tool-calls false}
                                                queue captured)
            payload   (apply str (repeat 40 "HUGE-TOOL-PAYLOAD "))
            ctx       (assoc (base-execution-ctx provider
                                                 {:model          "test-model"
                                                  :soul           "You are Isaac."
                                                  :crew           "main"
                                                  :comm           null-comm/channel
                                                  :context-window 100})
                        :allowed-tools #{"dump"})]
        (tool-registry/clear!)
        (tool-registry/register! {:name        "dump"
                                  :description "Dump a large payload"
                                  :parameters  {:type "object"}
                                  :handler     (fn [_]
                                                 (reset! estimate* 200)
                                                 {:result payload})})
        (with-redefs [compaction/estimate-prompt-tokens (fn [_ _] @estimate*)
                      compaction/should-compact?        (fn [tokens _ _] (>= tokens 80))
                      compaction/compact!               (fn [session-key _opts]
                                                          (swap! compact-n inc)
                                                          (helper/splice-compaction! test-dir session-key
                                                                                     {:summary           "prior tools summarized"
                                                                                      :tokensBefore      200
                                                                                      :compactedEntryIds []
                                                                                      :firstKeptEntryId  nil})
                                                          (reset! estimate* 20)
                                                          {:summary "prior tools summarized"})]
          (#'sut/execute-llm-turn! "mid-compact" "go" ctx))
        (should= 1 @compact-n)
        (should= 2 (count @captured))
        (let [second (pr-str (:messages (second @captured)))]
          (should (.contains second "prior tools summarized"))
          (should-not (.contains second "HUGE-TOOL-PAYLOAD")))))

    (it "does not compact when the live estimate stays under the threshold"
      (helper/create-session! test-dir "mid-small")
      (let [captured  (atom [])
            queue     (atom [{:tool-calls [{:id "tc1" :name "ping" :arguments {}}]
                              :message    {:role "assistant" :content ""}
                              :model      "test-model"
                              :usage      {}}
                             {:message {:role "assistant" :content "still small"}
                              :model   "test-model"
                              :usage   {}}])
            compact-n (atom 0)
            provider  (->ScriptedPromptProvider marigold/starcore
                                                {:api marigold/sky-api :stream-supports-tool-calls false}
                                                queue captured)
            ctx       (assoc (base-execution-ctx provider
                                                 {:model          "test-model"
                                                  :soul           "You are Isaac."
                                                  :crew           "main"
                                                  :comm           null-comm/channel
                                                  :context-window 1000})
                        :allowed-tools #{"ping"})]
        (tool-registry/clear!)
        (tool-registry/register! {:name        "ping"
                                  :description "Tiny ping"
                                  :parameters  {:type "object"}
                                  :handler     (fn [_] {:result "pong"})})
        (with-redefs [compaction/estimate-prompt-tokens (fn [_ _] 12)
                      compaction/should-compact?        (fn [_ _ _] false)
                      compaction/compact!               (fn [& _]
                                                          (swap! compact-n inc)
                                                          {:summary "should-not-run"})]
          (#'sut/execute-llm-turn! "mid-small" "go" ctx))
        (should= 0 @compact-n)
        (should= 2 (count @captured))))

    (it "blocks the turn when the conversation is blocked and the live estimate crosses the guard"
      (helper/create-session! test-dir "mid-exhaust")
      (helper/update-session! test-dir "mid-exhaust" {:block {:reason :compaction-failed :at "2026-09-04T10:00:00"}})
      (let [captured  (atom [])
            queue     (atom [{:tool-calls [{:id "tc1" :name "dump" :arguments {}}]
                              :message    {:role "assistant" :content ""}
                              :model      "test-model"
                              :usage      {}}
                             {:message {:role "assistant" :content "should not be hit"}
                              :model   "test-model"
                              :usage   {}}])
            estimate* (atom 10)
            provider  (->ScriptedPromptProvider marigold/starcore
                                                {:api marigold/sky-api :stream-supports-tool-calls false}
                                                queue captured)
            ctx       (assoc (base-execution-ctx provider
                                                 {:model          "test-model"
                                                  :soul           "You are Isaac."
                                                  :crew           "main"
                                                  :comm           null-comm/channel
                                                  :context-window 100
                                                  :config         {}})
                        :allowed-tools #{"dump"})]
        (tool-registry/clear!)
        (tool-registry/register! {:name        "dump"
                                  :description "Dump a large payload"
                                  :parameters  {:type "object"}
                                  :handler     (fn [_]
                                                 (reset! estimate* 99)
                                                 {:result (apply str (repeat 40 "HUGE-TOOL-PAYLOAD "))})})
        (with-redefs [compaction/estimate-prompt-tokens (fn [_ _] @estimate*)
                      compaction/should-compact?        (fn [_ _ _] false)
                      compaction/compact!               (fn [& _] (throw (ex-info "should not compact" {})))]
          (let [result (#'sut/execute-llm-turn! "mid-exhaust" "go" ctx)]
            (should= true (:unavailable? result))
            (should= :blocked (:reason result))
            (should= 1 (count @captured)))))))

  (describe "cancel after a tool cycle"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "returns stopReason cancelled when the tool-loop stops with :cancelled? after tools"
      (helper/create-session! test-dir "loop-cancel")
      (let [provider (->TestProvider marigold/starcore {:api marigold/sky-api :stream-supports-tool-calls false})
            ctx      (assoc (base-execution-ctx provider
                                                {:model  "test-model"
                                                 :soul   "You are Isaac."
                                                 :crew   "main"
                                                 :comm   null-comm/channel})
                       :allowed-tools #{"ping"})]
        (tool-registry/clear!)
        (tool-registry/register! {:name        "ping"
                                  :description "Tiny ping"
                                  :parameters  {:type "object"}
                                  :handler     (fn [_] {:result "pong"})})
        (with-redefs [tool-loop/run (fn [_chat _followup _request _tool-fn & _]
                                      {:response    nil
                                       :tool-calls  [{:id "tc1" :name "ping"}]
                                       :usage {:requests 1 :prompt-tokens 1 :output-tokens 1}
                                       :cancelled?  true})]
          (let [result (#'sut/execute-llm-turn! "loop-cancel" "go" ctx)]
            (should= "cancelled" (:stopReason result)))))))

  (describe "wrap-up note persistence"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (around [example]
      (nexus/-with-nexus {:root test-dir :fs (fs/mem-fs)}
        (helper/with-memory-store
          (example))))

    (it "persists a no-tool wrap-up note as the final assistant message and ends :cycle-limit :wrapped-up"
      (helper/create-session! test-dir "wrap-note")
      (let [captured (atom [])
            queue    (atom [{:message {:role    "assistant"
                                       :content ""
                                       :tool_calls [{:id       "tc1"
                                                     :function {:name "logbook-entry" :arguments {}}}]}
                             :model   "test-model"
                             :usage   {}}
                            {:message {:role "assistant" :content "Done: counted one can. Next: count lids"}
                             :model   "test-model"
                             :usage   {}}])
            provider (->ScriptedPromptProvider marigold/starcore
                                               {:api marigold/sky-api :stream-supports-tool-calls false}
                                               queue captured)
            ctx      (assoc (base-execution-ctx provider
                                                {:model          "test-model"
                                                 :soul           "You are Isaac."
                                                 :crew           "main"
                                                 :comm           (memory-comm/channel (atom []) :wrap-up)
                                                 :context-window 4096
                                                 :cycle          {:limit 0}})
                       :allowed-tools #{"logbook-entry"})]
        (tool-registry/clear!)
        (tool-registry/register! {:name        "logbook-entry"
                                  :description "Append to the ship's log"
                                  :parameters  {:type "object"}
                                  :handler     (fn [_] {:result "ok"})})
        (let [result   (#'sut/execute-llm-turn! "wrap-note" "count the cans" ctx)
              last-msg (:message (last (helper/get-transcript test-dir "wrap-note")))]
          (should= :cycle-limit (:ended-by result))
          (should= :wrapped-up (:exhaustion result))
          (should= "assistant" (:role last-msg))
          (should= "Done: counted one can. Next: count lids" (:content last-msg)))))
    )
