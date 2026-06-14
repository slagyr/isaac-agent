(ns isaac.tool.glob-spec
  (:require
    [clojure.java.io :as io]
    [isaac.config.api :as config]
    [isaac.marigold :as marigold]
    [isaac.spec-helper :as helper]
    [isaac.session.spec-helper :as store-helper]
    [isaac.nexus :as nexus]
    [isaac.tool.glob :as sut]
    [isaac.tool.support :as support]
    [speclj.core :refer :all]))

(def ^:private crew-name marigold/captain)
(def ^:private session-key "atticus-session")

(describe "Glob tool"
  (before (support/clean!))

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (store-helper/with-memory-store
      (nexus/-with-nested-nexus {:root support/test-dir}
        (example))))

  (it "returns matching file paths"
    (support/write-file! "src/core.clj" "")
    (support/write-file! "src/util.clj" "")
    (support/write-file! "src/notes.md" "")
    (support/set-mtime! "src/core.clj" "2026-04-20T00:00:01Z")
    (support/set-mtime! "src/util.clj" "2026-04-20T00:00:02Z")
    (support/set-mtime! "src/notes.md" "2026-04-20T00:00:03Z")
    (let [result (sut/glob-tool {"pattern" "**/*.clj"})]
      (should-be-nil (:isError result))
      (should= "src/util.clj\nsrc/core.clj" (:result result))))

  (it "sorts results by modification time descending with alphabetical tiebreaker"
    (support/write-file! "src/b.clj" "")
    (support/write-file! "src/a.clj" "")
    (support/set-mtime! "src/a.clj" "2026-04-20T00:00:00Z")
    (support/set-mtime! "src/b.clj" "2026-04-20T00:00:00Z")
    (let [result (sut/glob-tool {"pattern" "src/*.clj"})]
      (should-be-nil (:isError result))
      (should= "src/a.clj\nsrc/b.clj" (:result result))))

  (it "returns no matches when nothing matches the pattern"
    (support/write-file! "README.md" "")
    (let [result (sut/glob-tool {"pattern" "**/*.clj"})]
      (should-be-nil (:isError result))
      (should= "no matches" (:result result))))

  (it "uses the default head limit with truncation metadata"
    (support/write-file! "a.clj" "")
    (support/write-file! "b.clj" "")
    (support/write-file! "c.clj" "")
    (support/write-file! "d.clj" "")
    (support/write-file! "e.clj" "")
    (doseq [[name instant] [["a.clj" "2026-04-20T00:00:01Z"]
                            ["b.clj" "2026-04-20T00:00:02Z"]
                            ["c.clj" "2026-04-20T00:00:03Z"]
                            ["d.clj" "2026-04-20T00:00:04Z"]
                            ["e.clj" "2026-04-20T00:00:05Z"]]]
      (support/set-mtime! name instant))
    (binding [sut/*default-head-limit* 3]
      (let [result (sut/glob-tool {"pattern" "*.clj"})]
        (should-be-nil (:isError result))
        (should= "e.clj\nd.clj\nc.clj\nResults truncated. 5 total matches." (:result result)))))

  (it "defaults the search path to the session cwd"
    (let [cwd         (str support/test-dir "/workspace")
          session-key session-key]
      (store-helper/create-session! support/test-dir session-key {:crew crew-name :cwd cwd})
      (support/write-file! "workspace/src/core.clj" "")
      (config/dangerously-install-config! {:defaults {}
                                           :crew {crew-name {:tools {:allow ["glob"]
                                                                     :directories [:cwd]}}}
                                           :models {}
                                           :providers {}} "spec")
      (let [result (sut/glob-tool {"pattern" "**/*.clj"
                                   "session_key" session-key})]
        (should-be-nil (:isError result))
        (should= "src/core.clj" (:result result)))))

  (it "rejects glob outside allowed directories"
    (let [session-key session-key]
      (store-helper/create-session! support/test-dir session-key {:crew crew-name :cwd "/work/project"})
      (let [result (helper/with-config {:defaults {} :crew {crew-name {:tools {:allow ["glob"]}}} :models {} :providers {}}
                     (sut/glob-tool {"pattern" "*.clj"
                                      "path" "/tmp/secret-stash"
                                      "session_key" session-key}))]
        (should (:isError result))
        (should (re-find #"path outside allowed directories" (:error result))))))

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (describe "path resolution against session cwd"
    (with session-key "glob-res-session")
    (with cwd (str support/test-dir "/crew/" crew-name "/workspace"))

    (before
      (.mkdirs (io/file @cwd))
      (store-helper/create-session! support/test-dir @session-key {:crew crew-name :cwd @cwd}))

    (it "resolves '.' to session cwd"
      (spit (str @cwd "/dot.clj") "(ns dot)")
      (let [result (helper/with-config {:defaults {} :crew {} :models {} :providers {}}
                     (sut/glob-tool {"pattern" "*.clj" "path" "." "session_key" @session-key}))]
        (should-be-nil (:isError result))
        (should= "dot.clj" (:result result))))

    (it "resolves an empty path to session cwd"
      (spit (str @cwd "/empty.clj") "(ns empty)")
      (let [result (helper/with-config {:defaults {} :crew {} :models {} :providers {}}
                     (sut/glob-tool {"pattern" "*.clj" "path" "" "session_key" @session-key}))]
        (should-be-nil (:isError result))
        (should= "empty.clj" (:result result))))

    (it "resolves a relative path against session cwd"
      (.mkdirs (io/file @cwd "src"))
      (spit (str @cwd "/src/core.clj") "(ns core)")
      (let [result (helper/with-config {:defaults {} :crew {} :models {} :providers {}}
                     (sut/glob-tool {"pattern" "*.clj" "path" "src" "session_key" @session-key}))]
        (should-be-nil (:isError result))
        (should= "core.clj" (:result result))))))
