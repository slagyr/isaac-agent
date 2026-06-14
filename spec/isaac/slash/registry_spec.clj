(ns isaac.slash.registry-spec
  (:require
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.logger :as log]
    [isaac.module.loader :as module-loader]
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

  (it "logs :slash/registered when a new command is registered"
    (log/capture-logs
      (sut/register! {:name "echo" :description "Echo" :handler identity})
      (should= [{:level :info :event :slash/registered :command "echo"}]
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
