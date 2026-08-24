(ns isaac.tool.builtin-spec
  (:require
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.tool.builtin :as sut]
    [isaac.tool.grep :as grep]
    [isaac.tool.registry :as registry]
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
  )
