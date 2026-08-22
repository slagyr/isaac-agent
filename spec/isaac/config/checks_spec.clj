(ns isaac.config.checks-spec
  (:require
    [isaac.config.checks :as sut]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.marigold.agent :as marigold.agent]
    [isaac.nexus :as nexus]
    [speclj.core :refer [describe context it should should=]]))

(describe "config checks"

  (context "check-crew-model-aliases"

    (it "accepts a crew model that matches an existing model's provider string"
      (let [{:keys [errors]} (sut/check-crew-model-aliases
                               {:config {:crew   {"cordelia" {:model "echo"}}
                                         :models {"grover" {:model "echo" :provider "grover"}}}})]
        (should= [] errors)))

    (it "rejects a crew model that is neither a model id nor a provider model string"
      (let [{:keys [errors]} (sut/check-crew-model-aliases
                               {:config {:crew   {"cordelia" {:model "ghost"}}
                                         :models {"grover" {:model "echo" :provider "grover"}}}})]
        (should= [{:key       "crew.cordelia.model"
                   :value     "references undefined model"
                   :bad-value "ghost"}]
                 (mapv #(select-keys % [:key :value :bad-value]) errors))))

    (it "load-config accepts a crew model that matches an existing model's provider string"
      (let [fs* (fs/mem-fs)]
        (nexus/-with-nested-nexus {:fs fs*}
          (marigold.agent/with-real-manifest
            (marigold/write-config! {:defaults {:crew "main" :model "grover"}})
            (marigold/write-model! "grover" {:model "echo" :provider "grover" :context-window 32768})
            (marigold/write-crew! "main" {:model "grover" :soul "You are Atticus."})
            (marigold/write-crew! "cordelia" {:model "echo" :soul "You are Cordelia." :conversation :episodes})
            (marigold/write-provider! "grover" {})
            (let [result (loader/load-config-result {:root marigold/root :fs fs*})
                  model-errors (filter #(= "crew.cordelia.model" (:key %)) (:errors result))]
              (should= [] (mapv #(select-keys % [:key :value :bad-value]) model-errors))
              (should= "echo" (get-in result [:config :crew "cordelia" :model])))))))

    (it "load-config still rejects a crew model that matches no registered model"
      (let [fs* (fs/mem-fs)]
        (nexus/-with-nested-nexus {:fs fs*}
          (marigold.agent/with-real-manifest
            (marigold/write-config! {:defaults {:crew "main" :model "grover"}})
            (marigold/write-model! "grover" {:model "echo" :provider "grover" :context-window 32768})
            (marigold/write-crew! "main" {:model "grover" :soul "You are Atticus."})
            (marigold/write-crew! "cordelia" {:model "ghost" :soul "You are Cordelia."})
            (marigold/write-provider! "grover" {})
            (let [result (loader/load-config-result {:root marigold/root :fs fs*})
                  model-errors (filter #(= "crew.cordelia.model" (:key %)) (:errors result))]
              (should= {:key       "crew.cordelia.model"
                        :value     "references undefined model"
                        :bad-value "ghost"}
                       (select-keys (first model-errors) [:key :value :bad-value]))
              (should (seq model-errors))))))))

  (describe "check-crew-broad-directories"

    (it "warns when a crew directory equals the user home"
      (binding [root/*user-home* "/tmp/isaac-home"]
        (let [{:keys [warnings]} (sut/check-crew-broad-directories
                                   {:config {:root "/tmp/isaac-home"
                                             :crew {:scrapper {:tools {:directories ["/tmp/isaac-home"]}}}}
                                    :root   "/tmp/isaac-home/.isaac/config"})]
          (should= 1 (count warnings))
          (should (re-find #"user home" (:value (first warnings)))))))

    (it "warns when a crew directory includes the Isaac state root"
      (let [{:keys [warnings]} (sut/check-crew-broad-directories
                                 {:config {:root "/srv/isaac-state"
                                           :crew {:scrapper {:tools {:directories ["/srv/isaac-state"]}}}}
                                  :root   "/srv/isaac-state/config"})]
        (should= 1 (count warnings))
        (should (re-find #"Isaac state directory" (:value (first warnings))))
        (should (re-find #":role" (:value (first warnings))))))))