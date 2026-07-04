(ns isaac.config.checks-spec
  (:require
    [isaac.config.checks :as sut]
    [isaac.config.root :as root]
    [speclj.core :refer [describe it should should=]]))

(describe "config checks"

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