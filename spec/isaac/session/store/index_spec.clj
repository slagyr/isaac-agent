(ns isaac.session.store.index-spec
  (:require
    [isaac.fs :as fs]
    [isaac.session.store.spi :as store]
    [isaac.session.store.index :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer [describe it should should=]]))

(def test-dir "/test/index-store")

(describe "index session store"

  (it "uses the installed runtime fs without binding fs/*fs*"
    (let [mem      (fs/mem-fs)
          fs-store (nexus/-with-nexus {:fs mem}
                     (sut/create-store test-dir))]
      (store/open-session! fs-store "friday-debug" {:crew "main"})
      (should= "friday-debug" (:id (store/get-session fs-store "friday-debug")))
      (should (fs/exists? mem (str test-dir "/sessions/index.edn")))
      (should (fs/exists? mem (str test-dir "/sessions/friday-debug.jsonl"))))))
