(ns isaac.agent.module-steps
  (:require
    [clojure.string :as str]
    [gherclj.core :as g :refer [defthen defwhen helper!]]
    [isaac.bridge.status :as bridge]
    [isaac.config.loader :as loader]
    [isaac.agent.config.runtime :as runtime]
    [isaac.foundation.cli-steps :as fcli]
    [isaac.fs :as fs]
    [isaac.module.loader :as module-loader]
    [isaac.nexus :as nexus]
    [isaac.slash.registry :as slash-registry]
    [isaac.tool.memory :as memory]))

(helper! isaac.agent.module-steps)

(defn- root-dir []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn- mem-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (mem-fs)}
    (f)))

(defn- current-reply []
  (let [result (g/get :llm-result)]
    (or (when-let [message (:message result)]
          (cond
            (string? message) message
            (map? message)    (:content message)
            :else             nil))
        (when (= :status (:command result))
          (bridge/format-status (:data result)))
        (:content result)
        (get-in result [:response :message :content])
        (fcli/current-output)
        "")))

(defn- with-current-time [f]
  (if-let [current-time (g/get :current-time)]
    (binding [memory/*now* current-time]
      (f))
    (f)))

(defn isaac-process-started []
  (with-feature-fs
    (fn []
      (let [root (root-dir)
            fs*  (mem-fs)
            cfg  (loader/load-config! root fs* "feature: Isaac process started")]
        (runtime/install! {:config cfg})
        (g/assoc! :runtime-root-dir root)))))

(defn manifest-berths-processed-for-loaded-config []
  (with-feature-fs
    (fn []
      (let [root (root-dir)
            fs*  (mem-fs)
            {:keys [config]} (loader/load-config-result {:root root :fs fs*})
            module-index (:module-index config)]
        (when module-index
          (module-loader/process-manifest-berths! module-index))))))

(defn available-slash-commands-include [table]
  (let [cfg       (loader/snapshot "feature: available slash commands")
        commands  (slash-registry/all-commands (:module-index cfg))
        headers   (:headers table)]
    (doseq [row (:rows table)]
      (let [expected (zipmap headers row)
            matched? (some (fn [entry]
                             (every? (fn [[k v]] (= v (get entry (keyword k)))) expected))
                           commands)]
        (g/should matched?)))))

(defn reply-contains [expected]
  (let [expected (fcli/unescape-expected expected)
        output   (fcli/await-text current-reply #(str/includes? % expected))]
    (g/should (str/includes? output expected))))

(defn reply-matches [table]
  (let [output   (or (current-reply) "")
        patterns (fcli/extract-patterns table)]
    (doseq [pattern patterns]
      (g/should (re-find (re-pattern pattern) output)))))

(defn reply-does-not-contain [expected]
  (let [output   (current-reply)
        expected (fcli/unescape-expected expected)]
    (g/should-not (str/includes? (or output "") expected))))

;; region ----- Routing -----

(defwhen "the Isaac process is started" isaac.agent.module-steps/isaac-process-started
  "Boots the agent runtime without an HTTP server: load-config! commits
   config and reconciles eager-load modules; install! ensures the session
   store. Comm/service reconcile is server-only (isaac-95lv).")

(defwhen "manifest berths are processed for the loaded config"
  isaac.agent.module-steps/manifest-berths-processed-for-loaded-config
  "Runs process-manifest-berths! for the feature's loaded module index.")

(defthen "the available slash commands include:" isaac.agent.module-steps/available-slash-commands-include
  "Asserts each table row matches a registered slash command (built-ins
   and module-declared) after lazy module activation.")

(defthen "the reply contains {expected:string}" isaac.agent.module-steps/reply-contains
  "Polls up to 1s for the user-visible reply (bridge/comm result or CLI
   output) to contain the substring.")

(defthen "the reply matches:" isaac.agent.module-steps/reply-matches
  "Comm-neutral regex match against the current reply text.")

(defthen "the reply does not contain {expected:string}" isaac.agent.module-steps/reply-does-not-contain
  "Asserts the current reply omits the substring.")

;; endregion ^^^^^ Routing ^^^^^
