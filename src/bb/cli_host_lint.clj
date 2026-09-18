(ns bb.cli-host-lint
  "Scoped CLI-host lint for isaac-agent hosted commands (isaac-kk0o).
   Foundation's glob is the whole src tree; this repo still has user.dir
   in session store / tools, which are not hosted CLI commands."
  (:require
    [babashka.fs :as bfs]
    [clojure.string :as str]))

(def ^:private forbidden
  [#"System/(exit|getenv|console|setProperty)"
   #"System/getProperty\s+\"user\.dir\""
   #"addShutdownHook"])

(def ^:private hosted-cli
  ["src/isaac/session/cli.clj"
   "src/isaac/bridge/prompt_cli.clj"
   "src/isaac/llm/auth/cli.clj"
   "src/isaac/crew/cli.clj"
   "src/isaac/turn/cli.clj"])

(defn lint!
  ([] (lint! nil))
  ([_args]
   (let [errors
         (vec
           (for [relative hosted-cli
                 :let [path (bfs/file relative)]
                 :when (bfs/exists? path)
                 [line-number line] (map-indexed vector (str/split-lines (slurp path)))
                 pattern forbidden
                 :when (re-find pattern line)]
             {:file relative :line (inc line-number) :text (str/trim line)}))]
     (doseq [{:keys [file line text]} errors]
       (println (str file ":" line ": forbidden CLI host bypass: " text)))
     (if (seq errors)
       (throw (ex-info "CLI host lint failed" {:findings errors}))
       (println "lint-cli-host: ok")))))
