(ns isaac.tool.memory-spec
  (:require
    [isaac.config.api :as config]
    [isaac.fs :as fs]
    [isaac.marigold :as marigold]
    [isaac.session.spec-helper :as helper]
    [isaac.nexus :as nexus]
    [isaac.tool.memory :as sut]
    [speclj.core :refer :all]))

(def test-dir "/test/memory")
(def test-crew marigold/captain)

(defn- seed-default-crew! []
  (config/dangerously-install-config! {:defaults {:crew test-crew}} "spec"))

(describe "Memory tools"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (helper/with-memory-store
      (nexus/-with-nested-nexus {:root test-dir :fs (fs/mem-fs)}
        (seed-default-crew!)
        (example))))

  (it "writes to today's UTC note"
    (binding [sut/*now* (java.time.Instant/parse "2026-04-21T10:00:00Z")]
      (sut/memory-write-tool {"content" "Hieronymus hates artichokes."})
      (should= "Hieronymus hates artichokes."
               (fs/slurp (nexus/get :fs) (str test-dir "/crew/" test-crew "/memory/2026-04-21.md")))))

  (it "accepts a vector of entries"
    (binding [sut/*now* (java.time.Instant/parse "2026-04-21T10:00:00Z")]
      (sut/memory-write-tool {"content" ["Orpheus" "Grandma"]})
      (should= "Orpheus\nGrandma"
               (fs/slurp (nexus/get :fs) (str test-dir "/crew/" test-crew "/memory/2026-04-21.md")))))

  (it "appends instead of overwriting"
    (binding [sut/*now* (java.time.Instant/parse "2026-04-21T10:00:00Z")]
      (sut/memory-write-tool {"content" "first"})
      (sut/memory-write-tool {"content" "second"})
      (should= "first\nsecond"
               (fs/slurp (nexus/get :fs) (str test-dir "/crew/" test-crew "/memory/2026-04-21.md")))))

  (it "reads notes across an inclusive date range"
    #_{:clj-kondo/ignore [:invalid-arity]}
    (fs/mkdirs (nexus/get :fs) (str test-dir "/crew/" test-crew "/memory"))
    (fs/spit (nexus/get :fs) (str test-dir "/crew/" test-crew "/memory/2026-04-14.md") "The moonflowers bloomed last night.")
    (fs/spit (nexus/get :fs) (str test-dir "/crew/" test-crew "/memory/2026-04-16.md") "Wind knocked over the hedgehog figurine.")
    (fs/spit (nexus/get :fs) (str test-dir "/crew/" test-crew "/memory/2026-04-19.md") "User found a geode in the attic.")
    (let [result (sut/memory-get-tool {"start_time" "2026-04-14" "end_time" "2026-04-16"})]
      (should-contain "The moonflowers bloomed last night." (:result result))
      (should-contain "Wind knocked over the hedgehog figurine." (:result result))
      (should-not-contain "User found a geode in the attic." (:result result))))

  (it "searches all memory files and returns ripgrep-style lines"
    #_{:clj-kondo/ignore [:invalid-arity]}
    (fs/mkdirs (nexus/get :fs) (str test-dir "/crew/" test-crew "/memory"))
    (fs/spit (nexus/get :fs) (str test-dir "/crew/" test-crew "/memory/2026-04-15.md") "Orpheus brought a dead mouse to the back door.")
    (fs/spit (nexus/get :fs) (str test-dir "/crew/" test-crew "/memory/2026-04-19.md") "Orpheus sulked under the porch for most of the afternoon.")
    (fs/spit (nexus/get :fs) (str test-dir "/crew/" test-crew "/memory/2026-04-20.md") "The moonflowers bloomed last night.")
    (let [result (sut/memory-search-tool {"query" "Orpheus"})]
      (should-contain "2026-04-15.md:1:Orpheus brought a dead mouse to the back door." (:result result))
      (should-contain "2026-04-19.md:1:Orpheus sulked under the porch for most of the afternoon." (:result result))
      (should-not-contain "moonflowers" (:result result))))

  (it "uses the session crew when provided"
    (helper/create-session! test-dir "crew-session" {:crew marigold/first-mate :agent marigold/first-mate :cwd test-dir})
    (binding [sut/*now* (java.time.Instant/parse "2026-04-21T10:00:00Z")]
      (sut/memory-write-tool {"content" "tea note" "session_key" "crew-session"})
      (should= "tea note"
               (fs/slurp (nexus/get :fs) (str test-dir "/crew/" marigold/first-mate "/memory/2026-04-21.md")))))

  (it "uses the installed runtime fs without binding fs/*fs*"
    (let [mem (fs/mem-fs)]
      (helper/with-memory-store
        (nexus/-with-nexus {:root test-dir :fs mem}
          (seed-default-crew!)
          (binding [sut/*now* (java.time.Instant/parse "2026-04-21T10:00:00Z")]
            (sut/memory-write-tool {"content" "runtime memory"})
            (should= "runtime memory"
                     (fs/slurp mem (str test-dir "/crew/" test-crew "/memory/2026-04-21.md")))))))))
