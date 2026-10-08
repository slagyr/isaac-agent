(ns isaac.agent.slash.registry-spec
  (:require
    [isaac.foundation.fs :as fs]
    [isaac.foundation.main :as main]
    [isaac.foundation.nexus :as nexus]
    [isaac.foundation.logger :as log]
    [isaac.foundation.module.loader :as module-loader]
    [isaac.agent.slash.builtin :as builtin]
    [isaac.agent.slash.registry :as sut]
    [speclj.core :refer :all]))

(defn echo-command []
  {:description "Echo"
   :handler     identity})

(defn echo-provider []
  {:commands (fn [_] [{:name "echo" :description "Echo"}])
   :handle (fn [_ _ _ _] {:message "Echo"})})

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

  (it "lists commands offered by a registered provider"
    (sut/register! {:id :echo :rank 500
                    :commands (fn [_] [{:name "echo" :description "Echo"}])
                    :handle (fn [_ _ _ _] {:message "Echo"})})
    (should= "Echo" (:description (sut/lookup "echo"))))

  (it "chooses the built-in over a module at default rank"
    (sut/register! {:id :module :rank 500
                    :commands (fn [_] [{:name "status" :description "Module status"}])})
    (should= "Show session status" (:description (sut/lookup "status"))))

  (it "chooses an explicitly lower-ranked command over a built-in"
    (sut/register! {:id :module :rank 500
                    :commands (fn [_] [{:name "status" :rank 50 :description "Module status"}])})
    (should= "Module status" (:description (sut/lookup "status"))))

  (it "chooses the prompt-template command over a higher-ranked module command"
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (write-file! (str root "/prompts/commands/work.md")
                   "---\ntype: command\ndescription: Template work\n---\n\nStart work.")
      (sut/register! {:id :module :rank 950
                      :commands (fn [_] [{:name "work" :description "Module work"}])})
      (should= "Template work" (:description (sut/lookup "work" nil {:root root :fs (nexus/get :fs)})))))

  (it "asks the next provider when the lower-ranked one declines a command"
    (sut/register! {:id :first :rank 40
                    :commands (fn [_] [{:name "echo" :description "First echo"}])
                    :handle (fn [_ _ _ _] nil)})
    (sut/register! {:id :second :rank 500
                    :commands (fn [_] [{:name "echo" :description "Second echo"}])
                    :handle (fn [_ _ _ _] {:message "Second echo"})})
    (should= {:message "Second echo"}
             (sut/answer "echo" "session" {:args "hi"} nil nil)))

  (it "advertises the winning command only"
    (sut/register! {:id :module :rank 500
                    :commands (fn [_] [{:name "status" :description "Module status"}])})
    (should= [{:name "status" :description "Show session status"}]
             (->> (sut/all-commands) (filter #(= "status" (:name %)))
                  (mapv #(select-keys % [:name :description])))))

  (it "registers a provider through its berth factory"
    (sut/register-slash-entry! [:echo {:factory 'isaac.agent.slash.registry-spec/echo-provider}])
    (should= "Echo" (:description (sut/lookup "echo"))))

  (it "includes resolved prompt-template commands when listing advertised commands"
    (nexus/-with-nested-nexus {:fs (fs/mem-fs)}
      (write-file! (str root "/prompts/commands/work.md")
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
      (write-file! (str root "/prompts/commands/status.md")
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
