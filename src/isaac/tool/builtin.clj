;; mutation-tested: 2026-05-06
(ns isaac.tool.builtin
  (:require
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.tool.registry :as tool-registry]
    [isaac.tool.exec :as exec]
    [isaac.tool.file :as file]
    [isaac.tool.glob :as glob]
    [isaac.tool.grep :as grep]
    [isaac.tool.memory :as memory]
    [isaac.tool.names :as names]
    [isaac.tool.session :as session]
    [isaac.tool.comm-send :as comm-send]
    [isaac.tool.web-fetch :as web-fetch]
    [isaac.tool.web-search :as web-search]
    [isaac.recall.tools :as recall-tools]))

;; region ----- Registration -----

(def ^:private ordered-built-in-tools
  ["fs__read" "fs__write" "fs__edit" "fs__multi_edit" "fs__grep" "fs__glob"
   "web__fetch" "web__search" "memory__write" "memory__get" "memory__search"
   "exec__run" "session__info" "session__model" "skill__load" "skill__list"
   "comm__send" "hail__send" "recall__search" "recall__scene"])

(def ^:private built-in-tool-specs
  {"fs__read"        {:name        "fs__read"
                      :description "Read file contents or list a directory"
                      :parameters  {:type       "object"
                                    :properties {"file_path" {:type "string" :description "Path to file or directory"}
                                                 "offset"    {:type "integer" :description "Start line (1-indexed)"}
                                                 "limit"     {:type "integer" :description "Max lines to return"}}
                                    :required   ["file_path"]}
                      :handler     #'file/read-tool}
   "fs__write"       {:name        "fs__write"
                      :description "Write content to a file"
                      :parameters  {:type       "object"
                                    :properties {"file_path" {:type "string" :description "Path to write"}
                                                 "content"   {:type "string" :description "Content to write"}}
                                    :required   ["file_path" "content"]}
                      :handler     #'file/write-tool}
   "fs__edit"        {:name        "fs__edit"
                      :description "Replace text in a file"
                      :parameters  {:type       "object"
                                    :properties {"file_path"   {:type "string" :description "File to edit"}
                                                 "old_string"  {:type "string" :description "Text to replace"}
                                                 "new_string"  {:type "string" :description "Replacement text"}
                                                 "replace_all" {:type "boolean" :description "Replace all occurrences"}}
                                    :required   ["file_path" "old_string" "new_string"]}
                      :handler     #'file/edit-tool}
   "fs__multi_edit"  {:name        "fs__multi_edit"
                      :description "Apply multiple validated string replacements atomically"
                      :parameters  {:type       "object"
                                    :properties {"edits" {:type        "array"
                                                          :description "Replacement entries (same fields as edit)"
                                                          :items       {:type       "object"
                                                                        :properties {"file_path"   {:type "string"}
                                                                                     "old_string"  {:type "string"}
                                                                                     "new_string"  {:type "string"}
                                                                                     "replace_all" {:type "boolean"}}
                                                                        :required   ["file_path" "old_string" "new_string"]}}}
                                    :required   ["edits"]}
                      :handler     #'file/multi-edit-tool}
   "fs__grep"        {:name        "fs__grep"
                      :description "Search file contents with ripgrep"
                      :parameters  {:type       "object"
                                    :properties {"pattern"     {:type "string" :description "Regex pattern to search for"}
                                                 "path"        {:type "string" :description "File or directory to search"}
                                                 "glob"        {:type "string" :description "Optional file glob filter"}
                                                 "type"        {:type "string" :description "Optional file type shorthand"}
                                                 "-i"          {:type "boolean" :description "Case-insensitive search"}
                                                 "-n"          {:type "boolean" :description "Include line numbers in content mode"}
                                                 "-A"          {:type "integer" :description "Context lines after each match"}
                                                 "-B"          {:type "integer" :description "Context lines before each match"}
                                                 "-C"          {:type "integer" :description "Context lines before and after each match"}
                                                 "multiline"   {:type "boolean" :description "Enable multiline matching"}
                                                 "output_mode" {:type "string" :description "content, files_with_matches, or count"}
                                                 "head_limit"  {:type "integer" :description "Maximum rows to return; 0 means unlimited"}
                                                 "offset"      {:type "integer" :description "Rows to skip before returning results"}}
                                    :required   ["pattern" "path"]}
                      :available?  #(grep/available?)
                      :handler     #'grep/grep-tool}
   "fs__glob"        {:name        "fs__glob"
                      :description "List files matching a glob pattern"
                      :parameters  {:type       "object"
                                    :properties {"pattern"    {:type "string" :description "Glob pattern to match"}
                                                 "path"       {:type "string" :description "Directory to search; defaults to cwd or root"}
                                                 "head_limit" {:type "integer" :description "Maximum rows to return"}}
                                    :required   ["pattern"]}
                      :handler     #'glob/glob-tool}
   "web__fetch"      {:name        "web__fetch"
                      :description "Fetch URL content via HTTP GET"
                      :parameters  {:type       "object"
                                    :properties {"url"     {:type "string" :description "HTTP or HTTPS URL to fetch"}
                                                 "format"  {:type "string" :description "text or raw"}
                                                 "timeout" {:type "integer" :description "Timeout in milliseconds"}}
                                    :required   ["url"]}
                      :handler     #'web-fetch/web-fetch-tool}
   "web__search"     {:name        "web__search"
                      :description "Search the web via Brave Search"
                      :parameters  {:type       "object"
                                    :properties {"query"       {:type "string" :description "Search query"}
                                                 "num_results" {:type "integer" :description "Maximum results to return"}}
                                    :required   ["query"]}
                      :handler     #'web-search/web-search-tool}
   "memory__write"   {:name        "memory__write"
                      :description "Append content to today's crew memory note. Record durable facts, preferences, and discoveries — never task status, never instructions or advice to your future self."
                      :parameters  {:type       "object"
                                    :properties {"content" {:type "string" :description "Text to append"}}
                                    :required   ["content"]}
                      :handler     #'memory/memory-write-tool}
   "memory__get"     {:name        "memory__get"
                      :description "Read crew memory notes in an inclusive date range"
                      :parameters  {:type       "object"
                                    :properties {"start_time" {:type "string" :description "Start date YYYY-MM-DD"}
                                                 "end_time"   {:type "string" :description "End date YYYY-MM-DD"}}
                                    :required   ["start_time" "end_time"]}
                      :handler     #'memory/memory-get-tool}
   "memory__search"  {:name        "memory__search"
                      :description "Search crew memory notes"
                      :parameters  {:type       "object"
                                    :properties {"query" {:type "string" :description "Regex query to search for"}}
                                    :required   ["query"]}
                      :handler     #'memory/memory-search-tool}
   "exec__run"       {:name        "exec__run"
                      :description "Execute a shell command"
                      :parameters  {:type       "object"
                                    :properties {"command" {:type "string" :description "Command to run"}
                                                 "workdir" {:type "string" :description "Working directory"}
                                                 "timeout" {:type "integer" :description "Timeout in ms"}}
                                    :required   ["command"]}
                      :handler     #'exec/exec-tool}
   "session__info"   {:name        "session__info"
                      :description "Report the current session's crew, model, provider, origin, timing, context, and compaction count"
                      :parameters  {:type "object" :properties {}}
                      :handler     #'session/session-info-tool}
   "session__model"  {:name        "session__model"
                      :description "Switch or reset the calling session's model; returns new session state"
                      :parameters  {:type       "object"
                                    :properties {"model" {:type "string" :description "Model alias to switch to"}
                                                 "reset" {:type "boolean" :description "Revert to crew's default model"}}
                                    :required   []}
                      :handler     #'session/session-model-tool}
   "recall__search"  {:name        "recall__search"
                      :description "Rank sealed episode scenes for a query. Returns gist lines with scene ids (fetch full text with recall__scene)."
                      :parameters  {:type       "object"
                                    :properties {"query" {:type "string" :description "Search query"}}
                                    :required   ["query"]}
                      :handler     #'recall-tools/search-tool}
   "recall__scene"   {:name        "recall__scene"
                      :description "Fetch one sealed scene's distilled text by scene id."
                      :parameters  {:type       "object"
                                    :properties {"scene-id" {:type "string" :description "Scene id to fetch"}}
                                    :required   ["scene-id"]}
                      :handler     #'recall-tools/scene-tool}})

(defn- spec-for [tool-name]
  (some-> (get built-in-tool-specs tool-name)
          (dissoc :name :available?)))

(defn read-tool-factory [_] (spec-for "fs__read"))
(defn write-tool-factory [_] (spec-for "fs__write"))
(defn edit-tool-factory [_] (spec-for "fs__edit"))
(defn multi-edit-tool-factory [_] (spec-for "fs__multi_edit"))
(defn grep-tool-factory [_] (spec-for "fs__grep"))
(defn glob-tool-factory [_] (spec-for "fs__glob"))
(defn web-fetch-tool-factory [_] (spec-for "web__fetch"))
(defn web-search-tool-factory [_] (spec-for "web__search"))
(defn memory-write-tool-factory [_] (spec-for "memory__write"))
(defn memory-get-tool-factory [_] (spec-for "memory__get"))
(defn memory-search-tool-factory [_] (spec-for "memory__search"))
(defn exec-tool-factory [_] (spec-for "exec__run"))
(defn session-info-tool-factory [_] (spec-for "session__info"))
(defn session-model-tool-factory [_] (spec-for "session__model"))
(defn recall-search-tool-factory [_] (spec-for "recall__search"))
(defn recall-scene-tool-factory [_] (spec-for "recall__scene"))

(defn- allowed-tool? [allowed-tools tool-name]
  (or (= ::all allowed-tools)
      (names/allowed? allowed-tools tool-name)))

(defn hail-send-tool-factory [cfg]
  (if-let [remote (try (requiring-resolve 'isaac.tool.hail/hail-send-tool-factory)
                       (catch Throwable _ nil))]
    (remote cfg)
    {:description "Send a hail to a band or session target."
     :parameters  {:type       "object"
                   :properties {"band"     {:type "string" :description "Hail band id"}
                                "session"  {:type "array" :items {:type "string"} :description "Exact session ids"}
                                "prompt"   {:type "string" :description "Optional prompt override"}
                                "params"   {:type "object" :description "Band template parameters"}
                                "reply_to" {:type "string" :description "Optional hail id being replied to"}}}
     :handler     (fn [_] {:isError true :error "hail module is not loaded"})}))

(def ^:private extra-built-in-factories
  {"comm__send"  'isaac.tool.comm-send/comm-send-tool-factory
   "skill__list" 'isaac.tool.skill/list-skills-tool-factory
   "skill__load" 'isaac.tool.skill/load-skill-tool-factory
   "hail__send"  'isaac.tool.builtin/hail-send-tool-factory})

(defn- register-extra-built-in! [tool-name]
  (when-let [factory (get extra-built-in-factories tool-name)]
    (tool-registry/unregister! tool-name)
    (tool-registry/register-tool-entry!
      [(names/config-token tool-name) {:factory factory}])))

(defn- register-built-in-tool! [tool-name]
  (when (contains? extra-built-in-factories tool-name)
    (register-extra-built-in! tool-name))
  (when-not (tool-registry/lookup tool-name)
    (when-let [spec (get built-in-tool-specs tool-name)]
      (if-let [pred (:available? spec)]
        (if (pred)
          (module-loader/register-builtin-berth-entry! :isaac.agent/tools (names/config-token tool-name))
          (log/warn :tool/register-skipped :tool tool-name :reason "available? returned false"))
        (module-loader/register-builtin-berth-entry! :isaac.agent/tools (names/config-token tool-name))))))

(defn register-all!
  "Register all built-in tools with the tool registry.
   With 0-arity, registers every built-in tool.
   With 1-arity, registers only the tools in the allow list (nil registers none)."
  ([] (register-all! ::all))
  ([allowed-tools]
   (doseq [tool-name ordered-built-in-tools]
     (when (allowed-tool? allowed-tools tool-name)
       (register-built-in-tool! tool-name)))))

;; endregion ^^^^^ Registration ^^^^^
