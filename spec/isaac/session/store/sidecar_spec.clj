(ns isaac.session.store.sidecar-spec
  (:require
    [isaac.fs :as fs]
    [isaac.session.store.spi :as store]
    [isaac.session.store.sidecar :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer [describe it should should=]]))

(def test-dir "/test/sidecar-store")

(describe "sidecar session store"

  (it "uses the installed runtime fs without binding fs/*fs*"
    (let [mem      (fs/mem-fs)
          fs-store (nexus/-with-nexus {:fs mem}
                     (sut/create-store test-dir))]
      (store/open-session! fs-store "friday-debug" {:crew "main"})
      (should= "friday-debug" (:id (store/get-session fs-store "friday-debug")))
      (should (fs/exists? mem (str test-dir "/sessions/friday-debug.edn")))
      (should (fs/exists? mem (str test-dir "/sessions/friday-debug.jsonl"))))))
