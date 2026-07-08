(ns isaac.llm.api.claude-cli
  (:require
    [babashka.process :as process]
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.llm.api.protocol :as api]
    [isaac.llm.followup :as followup]
    [isaac.llm.prompt.builder :as prompt]))

;; region ----- Test Hooks -----

(defonce ^:private stub-state* (atom nil))
(defonce ^:private invocations* (atom []))

(defn- record-invocation! [invocation]
  (swap! invocations* conj invocation))

(defn set-stub! [f]
  (reset! stub-state* f))

(defn clear-stub! []
  (reset! stub-state* nil))

(defn clear-invocations! []
  (reset! invocations* []))

(defn invocations []
  (vec @invocations*))

;; endregion ^^^^^ Test Hooks ^^^^^

;; region ----- Prompt / Response -----

(def ^:private tool-call-open "<tool_call>")
(def ^:private tool-call-close "</tool_call>")

(defn- content->text [content]
  (cond
    (string? content) content
    (vector? content) (->> content
                           (filter #(= "text" (:type %)))
                           (map :text)
                           (str/join))
    :else (str content)))

(defn- messages->prompt-text [request]
  (let [tool-lines (when (seq (:tools request))
                     [(str "## Tools\n" (json/generate-string (:tools request)))])
        role-lines (for [{:keys [role content]} (:messages request)]
                     (str (str/capitalize (name role)) ": " (content->text content)))]
    (str/join "\n\n" (remove str/blank? (concat role-lines tool-lines)))))

(defn- tool-call-text [name args]
  (str tool-call-open (json/generate-string {:name name :arguments args}) tool-call-close))

(defn- parse-tool-calls [text]
  (loop [remaining (or text "")
         calls     []]
    (if-let [start (str/index-of remaining tool-call-open)]
      (let [after-open (subs remaining (+ start (count tool-call-open)))
            end        (str/index-of after-open tool-call-close)]
        (if end
          (let [payload (subs after-open 0 end)
                parsed  (json/parse-string payload true)]
            (recur (subs after-open (+ end (count tool-call-close)))
                   (conj calls {:id        (str (java.util.UUID/randomUUID))
                                :name      (:name parsed)
                                :arguments (or (:arguments parsed) {})
                                :raw       parsed})))
          calls))
      calls)))

(defn- visible-text [text]
  (let [open-idx (str/index-of (or text "") tool-call-open)]
    (if open-idx (subs text 0 open-idx) text)))

(defn- success-response [model text]
  (let [tool-calls (parse-tool-calls text)
        content    (visible-text text)]
    {:message    (cond-> {:role "assistant" :content content}
                   (seq tool-calls) (assoc :tool_calls tool-calls))
     :model      model
     :tool-calls tool-calls
     :usage      {:input-tokens 0 :output-tokens 0}}))

(defn- error-response [message]
  {:error :llm-error :message message})

;; endregion ^^^^^ Prompt / Response ^^^^^

;; region ----- CLI Invocation -----

(defn- flag-args [streaming?]
  (cond-> ["--print"
           "--output-format" (if streaming? "stream-json" "text")]
    streaming? (conj "--include-partial-messages")
    :always (into ["--disallowed-tools" "all"
                   "--no-session-persistence"])))

(defn- extra-args [cfg]
  (vec (or (:extra-args cfg) (:extraArgs cfg) [])))

(defn- command-path [cfg]
  (or (:command cfg) "claude"))

(defn- subprocess-env []
  (dissoc (into {} (System/getenv)) "ANTHROPIC_API_KEY"))

(defn- build-argv [cfg request streaming?]
  (let [prompt (messages->prompt-text request)]
    (into [(command-path cfg)]
          (concat (extra-args cfg)
                  (flag-args streaming?)
                  ["--model" (:model request)]
                  [prompt]))))

(defn- run-process! [argv env]
  (if-let [stub @stub-state*]
    (stub {:argv argv :env env})
    (let [result @(process/shell {:command argv
                                  :env     env
                                  :err     :string
                                  :out     :string})]
      {:exit (:exit result)
       :out  (:out result)
       :err  (:err result)})))

(defn- invoke! [cfg request streaming?]
  (let [argv (build-argv cfg request streaming?)
        env  (subprocess-env)]
    (record-invocation! {:argv argv :env env})
    (run-process! argv env)))

(defn- stream-json-deltas [lines]
  (mapcat (fn [line]
            (when (seq (str/trim line))
              (try
                (let [event (json/parse-string line true)]
                  (cond
                    (get-in event [:delta :text])
                    [(get-in event [:delta :text])]

                    (get-in event [:content_block_delta :delta :text])
                    [(get-in event [:content_block_delta :delta :text])]

                    (get-in event [:message :content 0 :text])
                    [(get-in event [:message :content 0 :text])]

                    (string? (:text event))
                    [(:text event)]

                    :else nil))
                (catch Exception _ nil))))
          lines))

;; endregion ^^^^^ CLI Invocation ^^^^^

;; region ----- Public API -----

(defn chat [request _provider-name cfg]
  (let [result (invoke! cfg request false)]
    (if (zero? (:exit result))
      (success-response (:model request) (:out result))
      (error-response (or (not-empty (:err result)) (:out result) "claude binary failed")))))

(defn chat-stream [request on-chunk _provider-name cfg]
  (let [result (invoke! cfg request true)]
    (if (zero? (:exit result))
      (let [lines   (str/split-lines (:out result))
            deltas  (vec (stream-json-deltas lines))
            content (str/join deltas)]
        (doseq [delta deltas]
          (when (seq delta)
            (on-chunk {:message {:role "assistant" :content delta} :done false})))
        (let [response (success-response (:model request) content)]
          (on-chunk {:done true})
          response))
      (error-response (or (not-empty (:err result)) (:out result) "claude binary failed")))))

(defn followup-messages [request response tool-calls tool-results]
  (let [assistant-msg (or (:message response)
                          {:role "assistant" :content ""})
        result-msgs   (mapv (fn [tc result]
                              {:role    "user"
                               :content (str "Tool result for " (:name tc) ": " result)})
                            tool-calls
                            tool-results)]
    (followup/append-followup-messages request assistant-msg result-msgs)))

(deftype ClaudeCliAPI [provider-name cfg]
  api/Api
  (chat [_ req] (chat req provider-name cfg))
  (chat-stream [_ req on-chunk] (chat-stream req on-chunk provider-name cfg))
  (followup-messages [_ req resp tcs trs] (followup-messages req resp tcs trs))
  (config [_] cfg)
  (display-name [_] provider-name)
  (format-tools [_ tools] (when (seq tools) (mapv api/wrapped-function-tool tools)))
  (build-prompt [_ opts] (prompt/build opts)))

(defn make [name cfg]
  (->ClaudeCliAPI name cfg))

;; endregion ^^^^^ Public API ^^^^^