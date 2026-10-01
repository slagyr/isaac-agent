(ns isaac.agent.tool.builtin-spec
  (:require
    [isaac.foundation.fs :as fs]
    [isaac.foundation.logger :as log]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.tool.builtin :as sut]
    [isaac.agent.tool.grep :as grep]
    [isaac.agent.tool.registry :as registry]
    [speclj.core :refer :all]))

(describe "Built-in tool registration"

  (around [it]
    (nexus/-with-nexus {:fs (fs/mem-fs) :config (atom {})}
      (it)))

  (before (registry/clear!))
  (after (registry/clear!))

  (it "registers only the explicitly allowed tools when an allow list is provided"
    (sut/register-all! #{:fs/read :fs/write})
    (should= #{"fs__read" "fs__write"} (set (map :name (registry/all-tools)))))

  (it "skips grep registration and logs a warning when rg is not on path"
    (with-redefs [grep/available? (constantly false)]
      (log/capture-logs
        (sut/register-all! #{:fs/grep})
        (should= [] (registry/all-tools))
        (should= 1 (count @log/captured-logs))
        (let [entry (first @log/captured-logs)]
          (should= :warn (:level entry))
          (should= :tool/register-skipped (:event entry))
          (should= "fs__grep" (:tool entry))
          (should= "available? returned false" (:reason entry))))))

  (it "registers glob when it is allowed"
    (sut/register-all! #{:fs/glob})
    (should= #{"fs__glob"} (set (map :name (registry/all-tools)))))

  (it "registers web_fetch when it is allowed"
    (sut/register-all! #{:web/fetch})
    (should= #{"web__fetch"} (set (map :name (registry/all-tools)))))

  (it "registers web_search when it is allowed"
    (sut/register-all! #{:web/search})
    (should= #{"web__search"} (set (map :name (registry/all-tools)))))

  (it "registers a namespace glob as the whole family"
    (sut/register-all! #{:fs/*})
    (should= #{"fs__read" "fs__write" "fs__edit" "fs__multi_edit" "fs__grep" "fs__glob"}
             (set (map :name (registry/all-tools)))))

  (it "skips tools already present in the registry"
    (sut/register-all! #{:fs/read})
    (let [count-before (count (registry/all-tools))]
      (sut/register-all! #{:fs/read})
      (should= count-before (count (registry/all-tools)))))

  (it "describes memory__write as durable knowledge, never work state"
    (let [description (:description (sut/memory-write-tool-factory nil))]
      (should-contain "durable facts, preferences, and discoveries" description)
      (should-contain "never task status" description)
      (should-contain "never instructions or advice to your future self" description)))

  (it "marks prompt list and load as built-ins"
    (sut/register-all! #{:prompt/list :prompt/load})
    (should= #{"prompt__list" "prompt__load"}
             (set (map :name (registry/all-tools))))
    (should (:builtin? (registry/lookup "prompt__list")))
    (should (:builtin? (registry/lookup "prompt__load"))))

  (it "marks hail send as a built-in"
    (sut/register-all! #{:hail/send})
    (should= #{"hail__send"}
             (set (map :name (registry/all-tools))))
    (should (:builtin? (registry/lookup "hail__send"))))

  (it "registers turn_get only for crews that allow turn/get"
    (sut/register-all! #{:turn/get})
    (should= #{"turn__get"} (set (map :name (registry/all-tools))))
    (should (:builtin? (registry/lookup "turn__get"))))

  (it "registers the advertised permissions.feature built-ins"
    (sut/register-all!)
    (should (contains? (set (map :name (registry/all-tools))) "prompt__list"))
    (should (contains? (set (map :name (registry/all-tools))) "prompt__load"))
    (should (contains? (set (map :name (registry/all-tools))) "hail__send"))
    (should (contains? (set (map :name (registry/all-tools))) "comm__send"))))
