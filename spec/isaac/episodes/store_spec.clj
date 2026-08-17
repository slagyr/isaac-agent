(ns isaac.episodes.store-spec
  (:require
    [clojure.edn :as edn]
    [clojure.string :as str]
    [isaac.episodes.store :as sut]
    [isaac.fs :as fs]
    [speclj.core :refer :all]))

(describe "isaac.episodes.store"

  (with root "/tmp-episodes-root")
  (with mem (fs/mem-fs))

  (before
    ;; Fresh mem-fs each example — mem-fs is empty until mkdirs/spit.
    (fs/mkdirs @mem @root))

  (it "writes episode.edn and scene.md (YAML frontmatter + body) under crew/episode-id"
    (let [episode {:id "2026-01-02-0304-ab"
                   :crew "cordelia"
                   :migrated-from "quiet-regatta"
                   :status :closed
                   :scene-ids ["2026-01-02-0304-s1"]
                   :started-at "2026-01-02T03:04:05"
                   :ended-at "2026-01-02T03:05:00"}
          scene {:id "2026-01-02-0304-s1"
                 :gist "Wine"
                 :text "pinot noir"
                 :start-id "a"
                 :end-id "b"
                 :started-at "2026-01-02T03:04:05"
                 :ended-at "2026-01-02T03:04:30"
                 :seal-reason :migrate}
          mem (fs/mem-fs)]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root episode [scene])
      (let [ep-path (sut/episode-path @root "cordelia" "2026-01-02-0304-ab")
            edn-path (str ep-path "/episode.edn")
            sc-path (str ep-path "/2026-01-02-0304-s1.md")
            raw (fs/slurp mem sc-path)
            read-back (sut/read-scene mem @root "cordelia" "2026-01-02-0304-ab" "2026-01-02-0304-s1")]
        (should (fs/exists? mem edn-path))
        (should (fs/exists? mem sc-path))
        (should-not (fs/exists? mem (str ep-path "/2026-01-02-0304-s1.edn")))
        (should= "quiet-regatta" (:migrated-from (edn/read-string (fs/slurp mem edn-path))))
        (should (str/starts-with? raw "---\n"))
        (should (re-find #"(?m)^gist: " raw))
        (should (re-find #"pinot noir" raw))
        (should= "Wine" (:gist read-back))
        (should= "pinot noir" (:text read-back))
        (should= "a" (:start-id read-back))
        (should= "b" (:end-id read-back))
        (should= "2026-01-02-0304-s1" (:id read-back))
        (should= :migrate (:seal-reason read-back)))))

  (it "finds episode by migrated-from session id"
    (let [mem (fs/mem-fs)
          episode {:id "ep1" :crew "cordelia" :migrated-from "calm-lagoon"
                   :status :closed :scene-ids []}]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root episode [])
      (let [found (sut/find-by-migrated-from mem @root "cordelia" "calm-lagoon")]
        (should= "ep1" (:id found)))))

  (it "lists episodes for a crew sorted by id"
    (let [mem (fs/mem-fs)]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root {:id "a-ep" :crew "cordelia" :migrated-from "s1"
                                     :status :closed :scene-ids []} [])
      (sut/write-episode! mem @root {:id "b-ep" :crew "cordelia" :migrated-from "s2"
                                     :status :closed :scene-ids []} [])
      (should= ["a-ep" "b-ep"] (mapv :id (sut/list-episodes mem @root "cordelia")))))

  (it "replaces scenes on rewrite (force) — removes prior .md and legacy .edn"
    (let [mem (fs/mem-fs)
          ep {:id "epx" :crew "cordelia" :migrated-from "x" :status :closed
              :scene-ids ["old"]}
          old {:id "old" :gist "old" :text "old"}
          new {:id "new" :gist "new" :text "new"}
          dir (sut/episode-path @root "cordelia" "epx")]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root ep [old])
      ;; Stale legacy .edn left beside the new format must also be purged.
      (fs/spit mem (str dir "/stale.edn") "{:id \"stale\"}")
      (sut/write-episode! mem @root (assoc ep :scene-ids ["new"]) [new] {:replace-scenes? true})
      (should-not (fs/exists? mem (str dir "/old.md")))
      (should-not (fs/exists? mem (str dir "/old.edn")))
      (should-not (fs/exists? mem (str dir "/stale.edn")))
      (should (fs/exists? mem (str dir "/new.md")))
      (should= "new" (:gist (sut/read-scene mem @root "cordelia" "epx" "new")))))

  (it "list-scene-ids returns .md basenames"
    (let [mem (fs/mem-fs)
          ep {:id "epy" :crew "cordelia" :migrated-from "y" :status :closed
              :scene-ids ["s-a" "s-b"]}
          scenes [{:id "s-a" :gist "A" :text "alpha"}
                  {:id "s-b" :gist "B" :text "beta"}]]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root ep scenes)
      (should= ["s-a" "s-b"] (sut/list-scene-ids mem @root "cordelia" "epy"))
      (should= ["A" "B"] (mapv :gist (sut/list-scenes mem @root "cordelia" "epy")))))
  )
