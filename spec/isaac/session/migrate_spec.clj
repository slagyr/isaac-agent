(ns isaac.session.migrate-spec
  (:require
    [cheshire.core :as json]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.session.migrate :as sut]
    [isaac.session.store.impl-common :as c]
    [speclj.core :refer :all]))

(def test-dir "/test/migrate")

(defn- fs* [] (nexus/get :fs))

(defn- read-session-edn [id]
  (edn/read-string (fs/slurp (fs*) (c/session-edn-path test-dir id))))

(defn- write-jsonl! [id entries]
  (let [path (c/flat-jsonl-path test-dir id)]
    (fs/mkdirs (fs*) (c/sessions-dir test-dir))
    (fs/spit (fs*) path (str (str/join "\n" (map json/generate-string entries)) "\n"))))

(describe "session migrate"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "turns a never-compacted jsonl into current.ednl"
    (write-jsonl! "quiet" [{:type "session" :id "h1"}
                           {:type "message" :id "m1" :message {:role "user" :content "hi"}}])
    (fs/spit (fs*) (c/flat-sidecar-path test-dir "quiet")
             (pr-str {:id "quiet" :key "quiet" :name "quiet" :session-file "quiet.jsonl"}))
    (should= :migrated (:status (sut/migrate-session! test-dir "quiet" (fs*))))
    (should (fs/exists? (fs*) (c/current-transcript-path test-dir "quiet")))
    (should-not (fs/exists? (fs*) (c/flat-jsonl-path test-dir "quiet")))
    (should= ["session" "message"] (mapv :type (c/read-transcript-raw test-dir "quiet" (fs*))))
    (should= 0 (:segment (read-session-edn "quiet"))))

  (it "splits compaction spans into frozen files and current.ednl"
    (write-jsonl! "long"
                  [{:type "session" :id "h1"}
                   {:type "message" :id "m1" :message {:role "user" :content "old"}}
                   {:type "compaction" :id "c1" :summary "s1"}
                   {:type "message" :id "m2" :message {:role "user" :content "kept"}}])
    (should= :migrated (:status (sut/migrate-session! test-dir "long" (fs*))))
    (should= ["session" "message"] (mapv :type (c/read-ednl (fs*) (c/frozen-transcript-path test-dir "long" 0))))
    (should= ["compaction" "message"] (mapv :type (c/read-transcript-raw test-dir "long" (fs*))))
    (should= 1 (:segment (read-session-edn "long"))))

  (it "skips an already migrated session"
    (write-jsonl! "done" [{:type "session" :id "h1"}])
    (sut/migrate-session! test-dir "done" (fs*))
    (should= :skipped (:status (sut/migrate-session! test-dir "done" (fs*)))))

  (it "skips a nested sessions/<crew>/<id> directory as already migrated"
    (let [fs* (fs*)
          dir (c/session-dir test-dir "main" "done")]
      (c/mkdirs*! fs* dir)
      (c/atomic-spit! fs* (c/session-edn-path test-dir "main" "done")
                      (c/write-edn {:id "done" :crew "main" :session-policy :chronicle}))
      (c/write-ednl! fs* (c/current-transcript-path test-dir "main" "done")
                     [{:type "session" :id "h1"}])
      (c/upsert-index-row! fs* test-dir "done" {:crew "main" :session-policy :chronicle})
      (should= :skipped (:status (sut/migrate-session! test-dir "done" fs*)))
      (should-not (some #{"done"} (sut/leftover-ids test-dir fs*)))))

  (it "still migrates leftover jsonl whose id appears in sessions/index.edn"
    (write-jsonl! "quiet" [{:type "session" :id "h1"}])
    (c/write-index! (fs*) test-dir {"quiet" {:id "quiet" :name "quiet"}})
    (should= :migrated (:status (sut/migrate-session! test-dir "quiet" (fs*))))
    (should (fs/exists? (fs*) (c/current-transcript-path test-dir "quiet"))))

  (it "reports missing when the id has no leftover files"
    (should= :missing (:status (sut/migrate-session! test-dir "ghost" (fs*)))))

  (it "migrate-all converts every leftover and leaves an already-migrated dir alone"
    (write-jsonl! "a" [{:type "session" :id "ha"}])
    (write-jsonl! "b" [{:type "session" :id "hb"}])
    (sut/migrate-session! test-dir "a" (fs*))
    (let [results (sut/migrate-all! test-dir (fs*))
          by-id   (into {} (map (juxt :id :status) results))]
      (should-not (contains? by-id "a"))
      (should= :migrated (get by-id "b"))
      (should (fs/exists? (fs*) (c/current-transcript-path test-dir "a")))))
  )
