(ns isaac.llm.claude-cli-steps
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen helper!]]
    [isaac.fs :as fs]
    [isaac.tool.tools-steps :as tools-steps]
    [isaac.llm.api.claude-cli :as claude-cli]
    [isaac.nexus :as nexus]
    [isaac.session.session-steps :as session-steps]))

(helper! isaac.llm.claude-cli-steps)

;; region ----- Helpers -----

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- expand-home [path]
  (if (str/starts-with? path "~/")
    (str (System/getProperty "user.home") (subs path 1))
    path))

(defn- parse-stream-chunks [raw]
  (let [trimmed (str/trim raw)]
    (if (str/starts-with? trimmed "[")
      (edn/read-string trimmed)
      (edn/read-string (str "[" trimmed "]")))))

(defn- argv->arg-map [argv]
  (loop [args (vec argv)
         m    {}]
    (if (empty? args)
      m
      (let [a (first args)]
        (if (str/starts-with? a "--")
          (let [next (second args)
                has-value? (and next (not (str/starts-with? next "--")))]
            (recur (drop (if has-value? 2 1) args)
                   (if has-value?
                     (assoc m a next)
                     (assoc m a ""))))
          (recur (rest args) m))))))

(defn- prompt-arg [argv]
  (when (seq argv) (last argv)))

(defn- match-invocation-table [argv env table]
  (let [arg-map  (argv->arg-map argv)
        prompt   (prompt-arg argv)
        failures (atom [])]
    (doseq [row (:rows table)]
      (let [[arg value] row]
        (cond
          (= arg "(full prompt text as arg)")
          (when (or (nil? prompt) (str/starts-with? prompt "--"))
            (swap! failures conj "expected full prompt text as final arg"))

          (= arg "(prompt arg contains full history)")
          (when-not (and prompt
                         (str/includes? prompt "previous turn")
                         (str/includes? prompt "previous reply"))
            (swap! failures conj "prompt missing prior transcript history"))

          (= arg "(no --continue or --resume)")
          (when (or (some #(= % "--continue") argv)
                    (some #(= % "--resume") argv))
            (swap! failures conj "found --continue or --resume in argv"))

          (= arg "(no ANTHROPIC_API_KEY in env)")
          (when (contains? env "ANTHROPIC_API_KEY")
            (swap! failures conj "ANTHROPIC_API_KEY present in subprocess env"))

          (str/starts-with? arg "--")
          (if (str/blank? value)
            (when-not (contains? arg-map arg)
              (swap! failures conj (str "missing flag " arg)))
            (when-not (= value (get arg-map arg))
              (swap! failures conj (str "expected " arg " = " value " got " (get arg-map arg)))))

          :else
          (swap! failures conj (str "unknown table arg " arg)))))
    @failures))

(defn- install-stub! [f]
  (g/dissoc! :feature-config)
  (claude-cli/clear-invocations!)
  (claude-cli/set-stub! f))

(defn- stream-json-out [chunks]
  (str/join "\n"
            (map (fn [text]
                   (json/generate-string {:type "content_block_delta"
                                          :delta {:text text}}))
                 chunks)))

(defn- stub-return [text]
  {:exit 0 :out text :err ""})

(defn- stub-fail [exit message]
  {:exit exit :out "" :err message})

;; endregion ^^^^^ Helpers ^^^^^

;; region ----- Given: Claude binary stub -----

(defn claude-binary-stubbed-return [text]
  (install-stub! (constantly (stub-return text))))

(defn claude-binary-at-stubbed-return [path text]
  (g/assoc! :claude-binary-path path)
  (install-stub! (fn [{:keys [argv]}]
                   (g/should= path (first argv))
                   (stub-return text))))

(defn claude-binary-stubbed-stream [raw]
  (let [chunks (parse-stream-chunks raw)]
    (g/should (pos? (count chunks)))
    (install-stub! (constantly (stub-return (stream-json-out chunks))))))

(defn claude-binary-stubbed-tool-then-text []
  (let [state (atom 0)]
    (install-stub!
      (fn [_]
        (case (swap! state inc)
          1 (stub-return (str "<tool_call>"
                              (json/generate-string {:name "exec" :arguments {:command "ls"}})
                              "</tool_call>"))
          2 (stub-return "done")
          (stub-return "done"))))))

(defn claude-binary-stubbed-fail [exit message]
  (install-stub! (constantly (stub-fail (parse-long exit) message))))

;; endregion ^^^^^ Given: Claude binary stub ^^^^^

;; region ----- Then: Claude binary invocation -----

(defn claude-binary-invoked-once-with [table]
  (session-steps/await-turn!)
  (let [invocations (claude-cli/invocations)]
    (g/should= 1 (count invocations))
    (let [{:keys [argv env]} (first invocations)
          failures (match-invocation-table argv env table)]
      (g/should= [] failures))))

(defn claude-binary-at-invoked-once-with [path table]
  (session-steps/await-turn!)
  (let [invocations (claude-cli/invocations)]
    (g/should= 1 (count invocations))
    (let [{:keys [argv env]} (first invocations)]
      (g/should= path (first argv))
      (g/should= [] (match-invocation-table argv env table)))))

(defn claude-binary-invoked-exactly [n]
  (session-steps/await-turn!)
  (g/should= (parse-long n) (count (claude-cli/invocations))))

(defn second-invocation-includes-tool-result []
  (session-steps/await-turn!)
  (let [invocations (claude-cli/invocations)]
    (g/should (<= 2 (count invocations)))
    (let [argv   (:argv (second invocations))
          prompt (prompt-arg argv)]
      (g/should (and prompt (str/includes? prompt "Tool result for exec"))))))

;; endregion ^^^^^ Then: Claude binary invocation ^^^^^

;; region ----- Supporting steps -----

(defn response-is [expected]
  (session-steps/await-turn!)
  (let [output (g/get :output)
        result (g/get :llm-result)]
    (g/should= expected (or output
                            (:content result)
                            (get-in result [:message :content])
                            (get-in result [:response :message :content])))))

(defn response-streams-as [raw]
  (session-steps/await-turn!)
  (let [expected (parse-stream-chunks raw)
        events   (->> (or (some-> (g/get :channel-events) deref) [])
                      (filter #(= "text-chunk" (:event %)))
                      (mapv :text))
        joined   (apply str expected)]
    (g/should= expected events)))

(defn crew-has-tools [raw]
  (tools-steps/builtin-tools-registered)
  (let [tools (-> raw
                  (str/replace #"[\[\]]" "")
                  (str/split #",\s*")
                  (->> (map str/trim)
                       (remove str/blank?)
                       vec))]
    (session-steps/crew-tool-allow "thinker" (str/join "," tools))))

(defn exec-tool-executed []
  (session-steps/await-turn!)
  (let [events (->> (or (some-> (g/get :channel-events) deref) [])
                    (filter #(= "tool-call" (:event %))))]
    (g/should (some #(= "exec" (get-in % [:tool :name])) events))))

(defn claude-binary-error-reported []
  (session-steps/await-turn!)
  (let [result (g/get :llm-result)]
    (g/should (or (= :llm-error (:error result))
                  (some? (:error result))))))

(defn error-message-contains [fragment]
  (session-steps/await-turn!)
  (let [result  (g/get :llm-result)
        message (or (:message result) (:output (g/get :llm-result)) "")]
    (g/should (str/includes? message fragment))))

(defn error-classified-auth []
  (session-steps/await-turn!)
  (let [result (or (g/get :llm-result) (g/get :dispatch-result))]
    (g/should (:unavailable? result))
    (g/should= :auth (:reason result))))

(defn credentials-file-exists [_path]
  (let [path (expand-home "~/.claude/.credentials.json")
        fs*  (mem-fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path "{\"claudeAiOauth\":{\"accessToken\":\"sub-token\"}}")))

(defn anthropic-api-key-unset []
  (g/assoc! :anthropic-api-key-cleared? true))

(g/before-scenario
  (fn []
    (claude-cli/clear-stub!)
    (claude-cli/clear-invocations!)))

(g/after-scenario
  (fn []
    (g/dissoc! :anthropic-api-key-cleared?)
    (claude-cli/clear-stub!)
    (claude-cli/clear-invocations!)))

;; endregion ^^^^^ Supporting steps ^^^^^

;; region ----- Routing -----

(defgiven #"the claude binary is stubbed to return \"([^\"]+)\"" isaac.llm.claude-cli-steps/claude-binary-stubbed-return)

(defgiven #"the claude binary at \"([^\"]+)\" is stubbed to return \"([^\"]+)\""
  isaac.llm.claude-cli-steps/claude-binary-at-stubbed-return)

(defgiven #"the claude binary is stubbed to stream (.+)" isaac.llm.claude-cli-steps/claude-binary-stubbed-stream)

(defgiven "the claude binary is stubbed to first return tool call text for exec, then \"done\""
  isaac.llm.claude-cli-steps/claude-binary-stubbed-tool-then-text)

(defgiven #"the claude binary is stubbed to fail with exit code (\d+) and message \"([^\"]+)\""
  isaac.llm.claude-cli-steps/claude-binary-stubbed-fail)

(defthen "the claude binary was invoked exactly once with:" isaac.llm.claude-cli-steps/claude-binary-invoked-once-with)

(defthen #"the claude binary at \"([^\"]+)\" was invoked exactly once with:"
  isaac.llm.claude-cli-steps/claude-binary-at-invoked-once-with)

(defn claude-binary-invoked-twice []
  (claude-binary-invoked-exactly "2"))

(defthen "the claude binary was invoked exactly twice" isaac.llm.claude-cli-steps/claude-binary-invoked-twice)

(defthen "the second invocation included the tool result serialized in the prompt text"
  isaac.llm.claude-cli-steps/second-invocation-includes-tool-result)

(defthen #"the response is \"([^\"]+)\"" isaac.llm.claude-cli-steps/response-is)

(defthen #"the response streams as (.+)" isaac.llm.claude-cli-steps/response-streams-as)

(defgiven #"the crew has tools: (.+)" isaac.llm.claude-cli-steps/crew-has-tools)

(defthen "the exec tool is executed" isaac.llm.claude-cli-steps/exec-tool-executed)

(defthen "an error is reported indicating the claude binary failed"
  isaac.llm.claude-cli-steps/claude-binary-error-reported)

(defthen #"the error message contains \"([^\"]+)\"" isaac.llm.claude-cli-steps/error-message-contains)

(defthen "the error is classified as auth-unavailable" isaac.llm.claude-cli-steps/error-classified-auth)

(defgiven #"the file \"([^\"]+)\" exists with the subscription login"
  isaac.llm.claude-cli-steps/credentials-file-exists)

(defgiven "ANTHROPIC_API_KEY is not set in the environment" isaac.llm.claude-cli-steps/anthropic-api-key-unset)

;; endregion ^^^^^ Routing ^^^^^