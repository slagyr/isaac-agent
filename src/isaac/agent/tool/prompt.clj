(ns isaac.agent.tool.prompt
  (:require
    [isaac.foundation.config.loader :as loader]
    [isaac.agent.prompt.catalog :as prompt-catalog]
    [isaac.agent.session.store.spi :as store]
    [isaac.agent.tool.fs-bounds :as bounds]))

(defn- session-entry [args]
  (let [session-key   (get args "session_key")
        session-store (bounds/session-store args)]
    (when session-key
      (some-> session-store
              (store/get-session session-key)))))

(defn- missing-session-error [session-key]
  {:isError true :error (str "session not found: " session-key)})

(defn- catalog-opts [args]
  (let [cfg     (or (loader/snapshot "prompt tools: prompt catalog resolution") {})
        session (session-entry args)]
    {:config    cfg
     :cwd       (:cwd session)
     :fs        (bounds/filesystem args)
     :root (or (:root cfg) (bounds/root args))}))

(defn- requested-kind [args]
  (some-> (get args "kind") not-empty keyword))

(defn load-prompt-tool
  "Load the full body of a discovered skill, command, or rule for the
   calling session. A command renders with the bodies of the skills it
   declares. An optional resource arg fetches a bundled file from a
   skill's own directory."
  [arguments]
  (let [args        (bounds/string-key-map arguments)
        session-key (get args "session_key")
        name        (get args "name")
        kind        (requested-kind args)
        resource    (some-> (get args "resource") not-empty)]
    (cond
      (nil? (session-entry args))
      (missing-session-error session-key)

      resource
      (let [result (prompt-catalog/resolve-skill-resource (catalog-opts args) name resource)]
        (cond
          (:body result)
          {:result (:body result)}

          (= :path-outside-skill (:error result))
          {:isError true :error (str "resource path escapes the skill directory: " resource)}

          (= :resource-not-found (:error result))
          {:isError true :error (str "skill resource not found: " name "/" resource)}

          :else
          {:isError true :error (str "unknown prompt: " name)}))

      :else
      (let [catalog (prompt-catalog/resolve-catalog (catalog-opts args))
            entry   (prompt-catalog/find-entry catalog kind name)]
        (cond
          (nil? entry)
          {:isError true :error (str "unknown prompt: " name)}

          (= :command (:type entry))
          (if-let [{:keys [input]} (prompt-catalog/resolve-command-prompt (catalog-opts args) name "")]
            {:result input}
            {:isError true :error (str "unknown prompt: " name)})

          :else
          (if-let [body (prompt-catalog/resolve-entry-body entry)]
            {:result body}
            {:isError true :error (str "unknown prompt: " name)}))))))

(defn list-prompts-tool
  "List every discovered skill, command, and rule by name, kind, and
   description for the calling session."
  [arguments]
  (let [args        (bounds/string-key-map arguments)
        session-key (get args "session_key")]
    (if (nil? (session-entry args))
      (missing-session-error session-key)
      {:result (or (prompt-catalog/resolve-prompt-menu (catalog-opts args))
                   "No prompts discovered.")})))

(defn load-prompt-tool-factory [_]
  {:builtin?    true
   :description "Load the full body of a discovered skill, command, or rule by name, or a bundled resource from a skill's directory."
   :parameters  {:type       "object"
                 :properties {"name"     {:type "string" :description "Prompt name to load"}
                              "kind"     {:type "string" :description "Optional kind to disambiguate a name shared across kinds: skill, command, or rule"}
                              "resource" {:type "string" :description "Optional bundled resource path within a skill's directory"}}
                 :required   ["name"]}
   :handler     #'load-prompt-tool})

(defn list-prompts-tool-factory [_]
  {:builtin?    true
   :description "List available prompts (skills, commands, rules) by name, kind, and description."
   :parameters  {:type "object" :properties {}}
   :handler     #'list-prompts-tool})
