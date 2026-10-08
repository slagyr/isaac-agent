;; mutation-tested: 2026-05-06
(ns isaac.agent.tool.session
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.agent.config.defaults :as defaults]
    [isaac.foundation.config.loader :as loader]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.tool.fs-bounds :as bounds]))

(defn- ->z [ts]
  (when ts
    (if (str/ends-with? ts "Z") ts (str ts "Z"))))

(defn- model-name [m]
  (if (keyword? m) (name m) (when m (str m))))

(defn- resolve-model-cfg [models lookup-key]
  (let [lookup-key (model-name lookup-key)]
    (or (get models lookup-key)
        (some (fn [[alias cfg]]
                (when (= lookup-key (:model cfg))
                  (assoc cfg :alias alias)))
              models))))

(defn- resolve-model-alias [session crew-cfg cfg]
  (model-name (or (:model session) (:model crew-cfg) (defaults/model-id cfg))))

(defn- build-session-state [session model-alias cfg]
  (let [models    (or (:models cfg) {})
        model-cfg (resolve-model-cfg models model-alias)
        alias     (or (:alias model-cfg) model-alias)
        provider  (model-name (:provider model-cfg))]
    {:result (json/generate-string
                {:crew        (or (:crew session) (defaults/crew-id cfg))
                 :model       {:alias    alias
                               :upstream (:model model-cfg)}
                 :provider    (or provider "")
                 :session     (:id session)
                :cwd         (:cwd session)
                :origin      (or (:origin session) {:kind "cli"})
                :created_at  (->z (:created-at session))
                :updated_at  (->z (:updated-at session))
                :context     {:used   (or (:total-tokens session) 0)
                              :window (:context-window model-cfg)}
                :compactions (or (:compaction-count session) 0)})}))

(defn session-info-tool
  "Report the current session's crew, model, provider, origin, timing, context, and compaction count.
   Args: session_key (runtime-injected)."
  [args]
  (let [args        (bounds/string-key-map args)
         session-key (get args "session_key")
         session     (store/get-session (bounds/session-store args) session-key)]
    (if (nil? session)
      {:isError true :error (str "session not found: " session-key)}
      (let [cfg      (loader/snapshot "session_info tool: model/crew resolution")
            crew-id  (or (:crew session) (defaults/crew-id cfg))
            crew-cfg (or (get-in cfg [:crew crew-id]) {})]
        (build-session-state session (resolve-model-alias session crew-cfg cfg) cfg)))))

(defn session-model-tool
  "Switch or reset the calling session's model.
   Args: model, reset, session_key (runtime-injected)."
  [args]
  (let [args        (bounds/string-key-map args)
        model       (get args "model")
        reset?      (bounds/arg-bool args "reset" false)
        session-key (get args "session_key")
        model       (when-not (str/blank? (str model)) model)]
    (cond
      (and model reset?)
      {:isError true :error "model and reset are mutually exclusive"}

      :else
      (let [session-store (bounds/session-store args)
            session       (store/get-session session-store session-key)]
        (if (nil? session)
          {:isError true :error (str "session not found: " session-key)}
          (let [cfg        (loader/snapshot "session_model tool: model/crew resolution")
                crew-id    (or (:crew session) (defaults/crew-id cfg))
                crew-cfg   (or (get-in cfg [:crew crew-id]) {})
                models     (or (:models cfg) {})
                crew-alias (model-name (or (:model crew-cfg) (defaults/model-id cfg)))]
            (cond
              (and model (not (contains? models model)))
              {:isError true :error (str "unknown model: " model)}

              model
              (do
                (store/update-session! session-store session-key {:model model})
                (build-session-state (assoc session :model model) model cfg))

              reset?
              (do
                (store/update-session! session-store session-key {:model crew-alias})
                (build-session-state (assoc session :model crew-alias) crew-alias cfg))

              :else
              (build-session-state session (resolve-model-alias session crew-cfg cfg) cfg))))))))

(defn- parse-time [s]
  (when s
    (java.time.Instant/parse (if (str/ends-with? s "Z") s (str s "Z")))))

(defn- history-context [args]
  (let [session-store (bounds/session-store args)
        caller        (store/get-session session-store (get args "session_key"))]
    [session-store (:crew caller)]))

(defn- history-messages [session-store session since until]
  (->> (store/chronicle-transcript session-store (:id session))
       (filter #(= "message" (:type %)))
       (remove #(= "toolResult" (get-in % [:message :role])))
       (filter (fn [{:keys [timestamp]}]
                 (let [time (parse-time timestamp)]
                   (and time (not (.isBefore time since))
                        (or (nil? until) (.isBefore time until))))))
       vec))

(defn- iso [timestamp]
  (str (parse-time timestamp)))

(defn- text-of [message]
  (let [content (:content message)]
    (cond
      (string? content) content
      (sequential? content) (->> content
                                 (map (fn [{:keys [type text name]}]
                                        (case type
                                          "text" text
                                          "toolCall" (str "(tool " name ")")
                                          nil)))
                                 (remove nil?)
                                 (str/join " "))
      :else "")))

(defn- message-line [{:keys [timestamp message]}]
  (str (iso timestamp) " " (:role message) ": " (text-of message)))

(defn session-list-tool [raw-args]
  (let [args (bounds/string-key-map raw-args)]
    (try
      (let [[session-store crew] (history-context args)
            since (parse-time (get args "since"))
            until (parse-time (get args "until"))]
        (if (or (nil? crew) (nil? since))
          {:isError true :error "session and since are required"}
          (let [lines (keep (fn [session]
                              (let [messages (history-messages session-store session since until)]
                                (when (seq messages)
                                  (str (or (:key session) (:id session)) " " (count messages) " messages "
                                       (iso (:timestamp (first messages))) ".."
                                       (iso (:timestamp (last messages)))))))
                            (store/list-sessions-by-agent session-store crew))]
            {:result (str/join "\n" lines)})))
      (catch Exception _ {:isError true :error "invalid time window"}))))

(defn- byte-count [s]
  (alength (.getBytes ^String s "UTF-8")))

(defn- page [messages offset max-lines max-bytes]
  (let [total (count messages)
        lines (mapv message-line (drop offset messages))
        line-limit (if (>= (count lines) max-lines) (max 1 (dec max-lines)) max-lines)
        footer (fn [n] (str (+ offset n) " of " total " messages; next offset " (+ offset n)))]
    (loop [selected [] remaining lines]
      (if-let [line (first remaining)]
        (let [candidate (conj selected line)
              more? (< (+ offset (count candidate)) total)
              output (str/join "\n" (cond-> candidate more? (conj (footer (count candidate)))))]
          (if (and (seq selected) (or (> (count candidate) line-limit)
                                      (> (byte-count output) max-bytes)))
            {:result (str/join "\n" (conj selected (footer (count selected)))) :already-capped? true}
            (if (and (empty? selected) (or (> (count (str/split-lines line)) max-lines)
                                           (> (byte-count line) max-bytes)))
              {:result line}
              (recur candidate (next remaining)))))
        {:result (str/join "\n" selected) :already-capped? true}))))

(defn session-read-tool [raw-args]
  (let [args (bounds/string-key-map raw-args)]
    (try
      (let [[session-store crew] (history-context args)
            session-name (get args "session")
            session (when crew (store/get-session session-store session-name))
            since (parse-time (get args "since"))
            until (parse-time (get args "until"))]
        (cond
          (or (nil? session) (not= crew (:crew session)))
          {:isError true :error (str "no session " session-name)}
          (nil? since) {:isError true :error "since is required"}
          :else (let [cfg (loader/snapshot "session history output caps")
                      caps (defaults/tool-caps cfg)
                      messages (history-messages session-store session since until)
                      offset (max 0 (or (bounds/arg-int args "offset" 0) 0))]
                  (page messages offset
                        (or (get args "max_lines") (:max-lines caps) 1000)
                        (or (get args "max_bytes") (:max-bytes caps) 131072)))))
      (catch Exception _ {:isError true :error "invalid time window"}))))
