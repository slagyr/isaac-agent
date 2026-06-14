(ns isaac.bridge.prompt-cli-spec
  (:require
    [clojure.string :as str]
    [isaac.marigold :as marigold]
    [isaac.bridge.core :as bridge]
    [isaac.charge :as charge]
    [isaac.comm.protocol :as comm]
    [isaac.bridge.prompt-cli :as sut]
    [isaac.config.loader :as loader]
    [isaac.config.runtime :as runtime]
    [isaac.server.routes]
    [isaac.session.spec-helper :as helper]
    [isaac.session.context :as session-ctx]
    [isaac.session.store.spi :as store]
    [isaac.tool.builtin :as builtin]
    [speclj.core :refer :all]))

(def crew-name marigold/captain)
(def crew-soul (:soul (marigold/crew-cfg crew-name)))

(def base-opts
  {:root "/test/prompt"
   :crew      crew-name})

;; A complete cfg with both crews (atticus + ketch) so tests that exercise
;; --session / --crew dispatch against both can share one stub. Loader stubs
;; in the run describe return this; the missing-config test resets the atom
;; to a missing-config result.
(def synthetic-config
  {:crew   {crew-name {:name crew-name :soul crew-soul :model "grover"}
            "ketch"   {:name "ketch" :soul "You are a pirate." :model "grover2"}}
   :models {"grover"  {:alias "grover"  :model "echo"     :provider "grover" :context-window 32768}
            "grover2" {:alias "grover2" :model "echo-alt" :provider "grover" :context-window 16384}}})

(def loader-stub (atom {:config synthetic-config}))

(defn- fake-dispatch! [text]
  (fn [charge]
    (comm/on-text-chunk (:comm charge) (:session-key charge) text)
    {}))

(defn- fake-charge [request]
  (let [cfg       (:config request)
        crew-id   (or (:crew request) crew-name)
        crew-cfg  (get-in cfg [:crew crew-id])
        model-id  (:model crew-cfg)
        model-cfg (get-in cfg [:models model-id])]
    (merge request
           {:model (:model model-cfg)
            :soul  (:soul crew-cfg)})))

(describe "CLI Prompt"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example] (helper/with-memory-store (example)))

  (describe "PromptComm"

    (it "renders compaction lifecycle and tool events to stderr while keeping response text separate"
      (let [collector   (#'sut/make-prompt-comm)
            channel     (:comm collector)
            err-writer  (java.io.StringWriter.)]
        (binding [*err* err-writer]
          (comm/on-compaction-start channel "prompt-default" {:total-tokens 95})
          (comm/on-tool-call channel "prompt-default" {:id "tc" :name "grep" :arguments {:pattern "lettuce" :path "src"}})
          (comm/on-tool-result channel "prompt-default" {:id "tc" :name "grep" :arguments {:pattern "lettuce" :path "src"}} "ok")
          (comm/on-compaction-success channel "prompt-default" {:tokens-saved 40})
          (comm/on-compaction-failure channel "prompt-default" {:error :llm-error :consecutive-failures 2})
          (comm/on-compaction-disabled channel "prompt-default" {:reason :too-many-failures})
          (comm/on-text-chunk channel "prompt-default" "here is the answer"))
        (should= "here is the answer" @(:text collector))
        (let [stderr (str err-writer)]
          (should (str/includes? stderr "🥬 compacting"))
          (should (str/includes? stderr "95"))
          (should (str/includes? stderr "🔍 grep"))
          (should (str/includes? stderr "lettuce"))
          (should (str/includes? stderr "← grep"))
          (should (str/includes? stderr "✨ compacted"))
          (should (str/includes? stderr "🥀 compaction failed"))
          (should (str/includes? stderr "llm-error"))
          (should (str/includes? stderr "🪦 compaction disabled"))
          (should (str/includes? stderr "too-many-failures"))))))

  (describe "tool-icon"

    (it "renders distinct icons for the known built-in tools"
      (should= "🔍" (@#'sut/tool-icon "grep"))
      (should= "📖" (@#'sut/tool-icon "read"))
      (should= "✏️" (@#'sut/tool-icon "write"))
      (should= "✏️" (@#'sut/tool-icon "edit"))
      (should= "⚙️" (@#'sut/tool-icon "exec"))
      (should= "🌐" (@#'sut/tool-icon "web_fetch"))
      (should= "💾" (@#'sut/tool-icon "memory_save"))
      (should= "🧰" (@#'sut/tool-icon "unknown"))))

  (describe "run"

    (before (reset! loader-stub {:config synthetic-config}))
    (redefs-around [loader/load-config!        (fn [& _] (:config @loader-stub))
                    loader/load-config-result  (fn [& _] @loader-stub)
                    runtime/install!           (fn [_] nil)
                    builtin/register-all!      (fn [] nil)
                    charge/build               fake-charge
                    session-ctx/create-with-resolved-behavior!
                    (fn [session-key opts]
                      (store/open-session! (:session-store opts)
                                           session-key
                                           (select-keys opts [:crew :tags :cwd :origin])))])

    (it "returns 1 and mentions 'required' when --message is missing"
      (let [output (with-out-str
                     (should= 1 (sut/run base-opts)))]
        (should (str/includes? output "required"))))

    (it "accepts a positional message through run-fn"
      (let [captured (atom nil)]
        (with-redefs [sut/run (fn [opts]
                                (reset! captured opts)
                                0)]
          (should= 0 (sut/run-fn (assoc base-opts :_raw-args ["Hello there"])))
          (should= "Hello there" (:message @captured)))))

    (it "fails clearly when no config exists"
      (reset! loader-stub {:missing-config? true
                           :errors          [{:key   "config"
                                              :value "no config found at /tmp/missing-config/.isaac/config/isaac.edn"}]})
      (let [err (java.io.StringWriter.)]
        (binding [*err* err]
          (with-out-str
            (should= 1 (sut/run {:home "/tmp/missing-config" :message "Hi"}))))
        (should (str/includes? (str err) "no config found"))
        (should (str/includes? (str err) "/tmp/missing-config/.isaac/config/isaac.edn"))))

    (it "prints the response text and returns 0"
      (with-redefs [bridge/dispatch! (fake-dispatch! "Test response")]
        (let [output (with-out-str
                       (should= 0 (sut/run (assoc base-opts :message "Hello"))))]
          (should (str/includes? output "Test response")))))

    (it "uses prompt-default as the default session"
      (let [used-key (atom nil)]
        (with-redefs [bridge/dispatch! (fn [charge]
                                         (reset! used-key (:session-key charge))
                                         (comm/on-text-chunk (:comm charge) (:session-key charge) "Hi")
                                         {})]
          (with-out-str (sut/run (assoc base-opts :message "Hi"))))
        (should= "prompt-default" @used-key)))

    (it "uses --session when provided"
      (helper/create-session! "/test/prompt" (str "agent:" crew-name ":cli:direct:user1"))
      (let [used-key (atom nil)]
        (with-redefs [bridge/dispatch! (fn [charge]
                                         (reset! used-key (:session-key charge))
                                         (comm/on-text-chunk (:comm charge) (:session-key charge) "Ok")
                                         {})]
           (with-out-str
             (sut/run (assoc base-opts :message "Next" :session (str "agent:" crew-name ":cli:direct:user1")))))
        (should= (str "agent:" crew-name ":cli:direct:user1") @used-key)))

    (it "uses the stored session crew when --session is provided without --crew"
      (helper/create-session! "/test/prompt" (str "agent:" crew-name ":cli:direct:user1") {:crew "ketch"})
      (let [captured (atom nil)]
        (with-redefs [bridge/dispatch! (fn [charge]
                                         (reset! captured charge)
                                         (comm/on-text-chunk (:comm charge) (str "agent:" crew-name ":cli:direct:user1") "Ok")
                                         {})]
          (with-out-str
            (sut/run {:root "/test/prompt"
                      :message   "Next"
                      :session   (str "agent:" crew-name ":cli:direct:user1")})))
        (should= "echo-alt" (:model @captured))
        (should= "You are a pirate." (:soul @captured))))

    (it "lets --crew override the stored session crew"
      (helper/create-session! "/test/prompt" (str "agent:" crew-name ":cli:direct:user1") {:crew "ketch"})
      (let [captured (atom nil)]
        (with-redefs [bridge/dispatch! (fn [charge]
                                         (reset! captured charge)
                                         (comm/on-text-chunk (:comm charge) (str "agent:" crew-name ":cli:direct:user1") "Ok")
                                         {})]
          (with-out-str
            (sut/run {:root "/test/prompt"
                      :message   "Next"
                      :session   (str "agent:" crew-name ":cli:direct:user1")
                      :crew      crew-name})))
        (should= "echo" (:model @captured))
        (should= crew-soul (:soul @captured))))

    (it "stores cwd on a newly created prompt session"
      (with-redefs [bridge/dispatch! (fake-dispatch! "Hello")]
        (with-out-str
          (sut/run (assoc base-opts :message "Hi")))
        (let [session (helper/get-session "/test/prompt" "prompt-default")]
          (should= (System/getProperty "user.dir") (:cwd session)))))

    (it "writes only crew when creating a fresh prompt session"
      (with-redefs [bridge/dispatch! (fake-dispatch! "Hello")]
        (with-out-str
          (sut/run (assoc base-opts :message "Hi" :session "fresh-prompt")))
        (let [session (helper/get-session "/test/prompt" "fresh-prompt")]
          (should= crew-name (:crew session))
          (should-not (contains? session :agent)))))

    (it "outputs JSON when --json is set"
      (with-redefs [bridge/dispatch! (fake-dispatch! "Hello")]
        (let [output (with-out-str
                       (sut/run (assoc base-opts :message "Hi" :json true)))]
          (should (str/includes? output "\"response\""))
          (should (str/includes? output "Hello")))))

    (it "tags a newly created session when --tag is provided"
      (with-redefs [bridge/dispatch! (fake-dispatch! "Hello")]
        (with-out-str
          (sut/run (assoc base-opts :message "Hi" :tag ["project/chess" "wip"] :session "tagged-prompt")))
        (let [session (helper/get-session "/test/prompt" "tagged-prompt")]
          (should= #{:project/chess :wip} (:tags session)))))

    (it "returns 1 when run-turn! returns an error"
      (with-redefs [bridge/dispatch! (fn [& _] {:error {:message "context length exceeded"}})]
        (binding [*err* (java.io.StringWriter.)]
          (with-out-str
            (should= 1 (sut/run (assoc base-opts :message "Hi")))))))

    (it "prints provider errors to stderr"
      (with-redefs [bridge/dispatch! (fn [& _] {:error :api-error :message "context length exceeded"})]
        (let [err-writer (java.io.StringWriter.)]
          (binding [*err* err-writer]
            (with-out-str
              (should= 1 (sut/run (assoc base-opts :message "Hi")))))
          (should (str/includes? (str err-writer) "context length exceeded")))))

    (it "--resume uses the most recent session"
      (helper/create-session! "/test/prompt" "older"  {:cwd "/test/prompt" :updated-at "2026-04-10T10:00:00"})
      (helper/create-session! "/test/prompt" "recent" {:cwd "/test/prompt" :updated-at "2026-04-12T15:00:00"})
      (let [used-key (atom nil)]
        (with-redefs [bridge/dispatch! (fn [charge]
                                         (reset! used-key (:session-key charge))
                                         (comm/on-text-chunk (:comm charge) (:session-key charge) "Ok")
                                         {})]
          (with-out-str
            (sut/run (assoc base-opts :message "Hi" :resume true))))
        (should= "recent" @used-key)))

    (it "--resume creates a new session when none exist"
      (let [used-key (atom nil)]
        (with-redefs [bridge/dispatch! (fn [charge]
                                         (reset! used-key (:session-key charge))
                                         (comm/on-text-chunk (:comm charge) (:session-key charge) "Ok")
                                         {})]
          (with-out-str
            (sut/run (assoc base-opts :message "Hi" :resume true))))
        (should= "prompt-default" @used-key)))

    )
  )
