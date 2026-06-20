(ns isaac.slash.registry-spec
  (:require
    [isaac.fs :as fs]
    [isaac.main :as main]
    [isaac.nexus :as nexus]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
    [isaac.slash.builtin :as builtin]
    [isaac.slash.registry :as sut]
    [speclj.core :refer :all]))

(defn echo-command []
  {:description "Echo"
   :handler     identity})

(def ^:private root "/test-state")

(defn- write-file! [path content]
  (let [fs* (nexus/get :fs)]
    (fs/mkdirs fs* (fs/parent path))
    (fs/spit fs* path content)))

(describe "slash registry"

  (before (sut/clear!))
  (after (sut/clear!))

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "registers and looks up a command by name"
    (sut/register! {:name "echo" :description "Echo" :handler identity})
    (let [command (sut/lookup "echo")]
      (should= "echo" (:name command))
      (should= "Echo" (:description command))))

  (it "returns commands sorted by name without handlers"
    (sut/register! {:name "zecho" :description "ZEcho" :handler identity})
    (sut/register! {:name "echo" :description "Echo" :handler identity})
    (let [commands (mapv #(select-keys % [:name :description]) (sut/all-commands))]
      (should= [{:name "echo" :description "Echo"}
                {:name "zecho" :description "ZEcho"}]
               (filterv #(contains? #{"echo" "zecho"} (:name %)) commands))))

  (it "does not log when a new command is registered (berth pass logs :berth/registered)"
    (log/capture-logs
      (sut/register! {:name "echo" :description "Echo" :handler identity})
      (should= []
               (mapv #(select-keys % [:level :event :command]) @log/captured-logs))))

  (it "logs :slash/override and keeps the replacement when a name collides"
    (sut/register! {:name "echo" :description "Built-in" :handler (constantly :builtin)})
    (log/capture-logs
      (sut/register! {:name "echo" :description "Module override" :handler (constantly :override)})
      (should= :override ((:handler (sut/lookup "echo")) nil))
      (should= [{:level :warn :event :slash/override :command "echo"}]
               (->> @log/captured-logs
                    (filter #(= :slash/override (:event %)))
                    (mapv #(select-keys % [:level :event :command]))))))

  (it "does not warn when the same handler is registered twice"
    (let [handler (constantly :same)]
      (sut/register! {:name "echo" :description "Echo" :handler handler})
      (log/capture-logs
        (sut/register! {:name "echo" :description "Echo again" :handler handler})
        (should= []
                 (->> @log/captured-logs
                      (filter #(= :slash/override (:event %)))
                      (mapv #(select-keys % [:level :event :command])))))))

  (it "does not warn when the same factory re-registers with a different handler ref"
    (sut/register! {:name        "echo"
                    :description "A"
                    :handler     (constantly :a)
                    :factory     'isaac.slash.registry-spec/echo-command})
    (log/capture-logs
      (sut/register! {:name        "echo"
                      :description "B"
                      :handler     (constantly :b)
                      :factory     'isaac.slash.registry-spec/echo-command})
      (should= []
               (->> @log/captured-logs
                    (filter #(= :slash/override (:event %)))
                    (mapv #(select-keys % [:level :event :command]))))))

  (it "does not warn when built-in slash commands are berth-processed twice"
    (log/capture-logs
      (module-loader/process-manifest-berths! (module-loader/builtin-index))
      (module-loader/process-manifest-berths! (module-loader/builtin-index))
      (should= 0 (count (filter #(= :slash/override (:event %)) @log/captured-logs)))))

  (it "does not warn when CLI-init and server-boot both berth-process builtins"
    (log/capture-logs
      (let [mem (fs/mem-fs)]
        (@#'main/register-module-cli-commands! nil mem "server")
        (module-loader/process-manifest-berths! (module-loader/builtin-index)))
      (should= 0 (count (filter #(= :slash/override (:event %)) @log/captured-logs)))))

  (it "does not warn when berth processing precedes ensure-registered! and a second berth pass"
    (log/capture-logs
      (module-loader/process-manifest-berths! (module-loader/builtin-index))
      (builtin/ensure-registered!)
      (module-loader/process-manifest-berths! (module-loader/builtin-index))
      (should= 0 (count (filter #(= :slash/override (:event %)) @log/captured-logs)))))

  (it "does not warn when lookup activates builtins before a second berth pass"
    (log/capture-logs
      (module-loader/process-manifest-berths! (module-loader/builtin-index))
      (sut/lookup "crew" (module-loader/builtin-index))
      (module-loader/process-manifest-berths! (module-loader/builtin-index))
      (should= 0 (count (filter #(= :slash/override (:event %)) @log/captured-logs)))))

  (it "activates slash command modules before listing all commands"
    (let [module-index {:isaac.slash.echo {:manifest {:slash-commands {:echo {}}}}}]
      (with-redefs [module-loader/activate! (fn [_ _]
                                              (sut/register! {:name "echo" :description "Echo" :handler identity})
                                              :activated)]
        (should= [{:name "echo" :description "Echo"}]
                 (->> (sut/all-commands module-index)
                      (map #(select-keys % [:name :description]))
                      (filterv #(= "echo" (:name %))))))))

  (it "activates slash command modules before lookup"
    (let [module-index {:isaac.slash.echo {:manifest {:slash-commands {:echo {}}}}}]
      (with-redefs [module-loader/activate! (fn [_ _]
                                              (sut/register! {:name "echo" :description "Echo" :handler identity})
                                              :activated)]
        (should= "echo" (:name (sut/lookup "echo" module-index))))))

  (it "registers a berth command under its berth-key name"
    (let [module-index {:isaac.slash.echo {:manifest {:isaac.agent/slash-commands {:echo {:factory 'isaac.slash.registry-spec/echo-command}}}}}]
      (nexus/-with-nexus {:config (atom {})
                           :fs (fs/mem-fs)}
        (module-loader/clear-activations!)
        (should= "echo" (:name (sut/lookup "echo" module-index))))))

  (it "includes resolved prompt-template commands when listing advertised commands"
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (write-file! (str root "/config/commands/work.md")
                   (str "---\n"
                        "type: command\n"
                        "description: Start work on a ready bean\n"
                        "params: [bean]\n"
                        "---\n\n"
                        "Start work on bean {{bean}}."))
      (should= {:description "Start work on a ready bean"
                :name        "work"
                :params      ["bean"]}
               (->> (apply sut/all-commands [{} {:fs        (nexus/get :fs)
                                                :root root}])
                    (filter #(= "work" (:name %)))
                    first
                    (#(select-keys % [:description :name :params]))))))

  (it "keeps a registered slash command when a prompt-template command has the same name"
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (write-file! (str root "/config/commands/status.md")
                   (str "---\n"
                        "type: command\n"
                        "description: Prompt template status\n"
                        "params: [detail]\n"
                        "---\n\n"
                        "Status {{detail}}."))
      (should= {:description "Show session status"
                :name        "status"}
               (->> (apply sut/all-commands [{} {:fs        (nexus/get :fs)
                                                :root root}])
                    (filter #(= "status" (:name %)))
                    first
                    (#(select-keys % [:description :name])))))))
