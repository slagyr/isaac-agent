(ns isaac.session.store.sidecar-spec
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.session.store.spi :as store]
    [isaac.session.store.sidecar :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer [describe it should should-not should-not= should-throw should=]]))

(def test-dir "/test/sidecar-store")

(describe "sidecar session store"

  (it "uses the installed runtime fs without binding a thread-local fs"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem}
        (let [fs-store (sut/create-store test-dir)]
          (store/open-session! fs-store "friday-debug" {:crew "main"})
          (should= "friday-debug" (:id (store/get-session fs-store "friday-debug")))
          (should (fs/exists? mem (str test-dir "/sessions/main/friday-debug/session.edn")))
          (should (fs/exists? mem (str test-dir "/sessions/main/friday-debug/current.ednl")))))))

  (it "updates a leftover flat session without creating a nested twin"
    (let [mem      (fs/mem-fs)
          flat-dir (str test-dir "/sessions/engine-room")]
      (nexus/-with-nexus {:fs mem}
        (fs/mkdirs mem flat-dir)
        (fs/spit mem (str flat-dir "/session.edn")
                 (pr-str {:id "engine-room" :key "engine-room" :name "Engine Room"
                          :crew "scrapper" :created-at "2026-09-11T03:00:00"
                          :updated-at "2026-09-11T03:00:00" :origin {:kind :cli} :tags #{}}))
        (fs/spit mem (str flat-dir "/current.ednl")
                 (str (pr-str {:type "message" :id "old"}) "\n"))
        (let [fs-store (sut/create-store test-dir)]
          (store/update-session! fs-store "engine-room" {:updated-at "2026-09-11T04:07:00"})
          (store/append-message! fs-store "engine-room" {:role "user" :content "Seal the leak."})
          (store/record-turn-marker! fs-store "engine-room" {:source :hail})
          (should (fs/exists? mem (str flat-dir "/session.edn")))
          (should (fs/exists? mem (str flat-dir "/current.ednl")))
          (should (fs/exists? mem (str flat-dir "/turn.edn")))
          (let [ids (->> (fs/slurp mem (str flat-dir "/current.ednl"))
                         str/split-lines
                         (map read-string)
                         (map :id)
                         vec)]
            (should= "old" (first ids))
            (should= 2 (count ids)))
          (should-not (fs/exists? mem (str test-dir "/sessions/scrapper/engine-room")))))))

  (it "reads one session without reading every session on the host"
    ;; get-session/get-transcript/update-session! run on every turn cycle;
    ;; the sidecar store used to read+conform EVERY session.edn under the
    ;; root to answer for one id (456 files on zanebot, ~650ms, twice per
    ;; compaction check). The scan is the tell: a single-session read never
    ;; needs it once the index knows the session.
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem}
        (let [fs-store (sut/create-store test-dir)]
          (store/open-session! fs-store "friday-debug" {:crew "main"})
          (store/append-message! fs-store "friday-debug" {:role "user" :content "hi"})
          (with-redefs [isaac.session.store.impl-common/scan-session-dirs
                        (fn [& _] (throw (ex-info "scanned the host to read one session" {})))]
            (should= "friday-debug" (:id (store/get-session fs-store "friday-debug")))
            (should (some #(= "message" (:type %)) (store/get-transcript fs-store "friday-debug")))
            (store/update-session! fs-store "friday-debug" {:updated-at "2026-09-17T20:00:00"})
            (should= "2026-09-17T20:00:00" (:updated-at (store/get-session fs-store "friday-debug"))))))))

  (it "get-session is nil for an unknown id and still throws for an unreadable existing one"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem}
        (let [fs-store (sut/create-store test-dir)]
          (should= nil (store/get-session fs-store "never-made"))
          (store/open-session! fs-store "torn" {:crew "main"})
          (fs/spit mem (str test-dir "/sessions/main/torn/session.edn") "")
          (should-throw clojure.lang.ExceptionInfo
            (store/get-session fs-store "torn"))))))

  (it "get-session never resolves a blank or nil identifier to the literal 'session' entry"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem}
        (let [fs-store (sut/create-store test-dir)]
          (store/open-session! fs-store "session" {:crew "main"})
          (should= nil (store/get-session fs-store ""))
          (should= nil (store/get-session fs-store nil))))))

  (it "open-session! with a blank identifier mints a fresh session instead of colliding with 'session'"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem}
        (let [fs-store (sut/create-store test-dir)]
          (store/register-store! fs-store)
          (let [main-session (store/open-session! fs-store "session" {:crew "main"})
                opened       (store/open-session! fs-store "" {:crew "marvin"})]
            (should-not= (:id main-session) (:id opened))
            (should= "marvin" (:crew opened)))))))

  (it "does not create a flat marker directory for an unpersisted session"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem}
        (let [fs-store (sut/create-store test-dir)]
          (store/record-turn-marker! fs-store "hook:sleep" {:source :hook :crew "scrapper"})
          (should-not (fs/exists? mem (str test-dir "/sessions/hook:sleep")))))))

  (it "repair-transcript! truncates a torn trailing line and reports it"
    (let [mem (fs/mem-fs)]
      (nexus/-with-nexus {:fs mem}
        (let [fs-store (sut/create-store test-dir)
              path     (str test-dir "/sessions/main/torn/current.ednl")]
          (store/open-session! fs-store "torn" {:crew "main"})
          (store/append-message! fs-store "torn" {:role "user" :content "Begin"})
          (fs/spit mem path (str (fs/slurp mem path) "{:type \"mess"))
          (should (store/repair-transcript! fs-store "torn"))
          (should= ["Begin"] (->> (store/get-transcript fs-store "torn")
                                  (filter #(= "message" (:type %)))
                                  (map #(get-in % [:message :content 0 :text]))))
          (should-not (store/repair-transcript! fs-store "torn")))))))
