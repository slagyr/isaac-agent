(ns isaac.episodes.store-spec
  (:require
    [clojure.edn :as edn]
    [isaac.episodes.store :as sut]
    [isaac.fs :as fs]
    [speclj.core :refer :all]))

(describe "isaac.episodes.store"

  (with root "/tmp-episodes-root")
  (with mem (fs/mem-fs))

  (before
    ;; Fresh mem-fs each example — mem-fs is empty until mkdirs/spit.
    (fs/mkdirs @mem @root))

  (it "writes episode.edn and scene files under crew/episode-id"
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
                 :start-id "a" :end-id "b"}
          mem (fs/mem-fs)]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root episode [scene])
      (let [ep-path (sut/episode-path @root "cordelia" "2026-01-02-0304-ab")
            edn-path (str ep-path "/episode.edn")
            sc-path (str ep-path "/2026-01-02-0304-s1.edn")]
        (should (fs/exists? mem edn-path))
        (should (fs/exists? mem sc-path))
        (should= "quiet-regatta" (:migrated-from (edn/read-string (fs/slurp mem edn-path))))
        (should= "Wine" (:gist (edn/read-string (fs/slurp mem sc-path)))))))

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

  (it "replaces scenes on rewrite (force)"
    (let [mem (fs/mem-fs)
          ep {:id "epx" :crew "cordelia" :migrated-from "x" :status :closed
              :scene-ids ["old"]}
          old {:id "old" :gist "old" :text "old"}
          new {:id "new" :gist "new" :text "new"}]
      (fs/mkdirs mem @root)
      (sut/write-episode! mem @root ep [old])
      (sut/write-episode! mem @root (assoc ep :scene-ids ["new"]) [new] {:replace-scenes? true})
      (let [dir (sut/episode-path @root "cordelia" "epx")]
        (should-not (fs/exists? mem (str dir "/old.edn")))
        (should (fs/exists? mem (str dir "/new.edn"))))))
  )
