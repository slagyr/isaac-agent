(ns isaac.tool.builtin-spec
  (:require
    [isaac.logger :as log]
    [isaac.tool.builtin :as sut]
    [isaac.tool.grep :as grep]
    [isaac.tool.registry :as registry]
    [speclj.core :refer :all]))

(describe "Built-in tool registration"

  (before (registry/clear!))
  (after (registry/clear!))

  (it "registers only the explicitly allowed tools when an allow list is provided"
    (sut/register-all! #{"read" "write"})
    (should= #{"read" "write"} (set (map :name (registry/all-tools)))))

  (it "skips grep registration and logs a warning when rg is not on path"
    (with-redefs [grep/available? (constantly false)]
      (log/capture-logs
        (sut/register-all! #{"grep"})
        (should= [] (registry/all-tools))
        (should= 1 (count @log/captured-logs))
        (let [entry (first @log/captured-logs)]
          (should= :warn (:level entry))
          (should= :tool/register-skipped (:event entry))
          (should= "grep" (:tool entry))
          (should= "available? returned false" (:reason entry))))))

  (it "registers glob when it is allowed"
    (sut/register-all! #{"glob"})
    (should= #{"glob"} (set (map :name (registry/all-tools)))))

  (it "registers web_fetch when it is allowed"
    (sut/register-all! #{"web_fetch"})
    (should= #{"web_fetch"} (set (map :name (registry/all-tools)))))

  (it "registers web_search when it is allowed"
    (sut/register-all! #{"web_search"})
    (should= #{"web_search"} (set (map :name (registry/all-tools))))))
