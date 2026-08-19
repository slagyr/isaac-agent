(ns isaac.recall.index-spec
  (:require
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.recall.embedding :as embedding]
    [isaac.recall.index :as sut]
    [isaac.recall.score :as score]
    [speclj.core :refer [before context describe it should should-not should= with]])
  (:import
    (java.nio ByteBuffer ByteOrder)))

(def ^:private root "/tmp-recall-root")

(defn- write-closed! [fs* crew episode-id scenes]
  (store/write-episode!
    fs* root
    {:id        episode-id
     :crew      crew
     :status    :closed
     :scene-ids (mapv :id scenes)
     :started-at (:started-at (first scenes))
     :ended-at   (:ended-at (last scenes))}
    scenes))

(describe "isaac.recall.index"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (with mem (fs/mem-fs))

  (before
    (fs/mkdirs @mem root))

  (it "index path is episodes/<crew>/index.edn"
    (should= (str root "/episodes/cordelia/index.edn")
             (sut/index-path root "cordelia")))

  (it "reads nothing when the index file is absent"
    (should= [] (sut/read-index @mem root "cordelia")))

  (it "writes and reads packed metadata + unit-normalized vectors"
    (let [rows [{:episode-id "ep1" :scene-id "s1" :kind :gist :model "mini-embed" :vector [3.0 0.0 4.0]}]]
      (sut/write-index! @mem root "cordelia" rows)
      (let [got (sut/read-index @mem root "cordelia")]
        (should= 1 (count got))
        (should= "ep1" (:episode-id (first got)))
        (should= :gist (:kind (first got)))
        (should= [0.6 0.0 0.8] (mapv #(Double/parseDouble (format "%.1f" %))
                                     (:vector (first got)))))))

  (it "blob bytes match independently computed little-endian float32"
    (let [rows [{:episode-id "ep1" :scene-id "s1" :kind :gist :model "mini-embed" :vector [3.0 0.0 4.0]}
                {:episode-id "ep1" :scene-id "s2" :kind :text :model "mini-embed" :vector [0.0 5.0 0.0]}]
          expected (let [bb (ByteBuffer/allocate (* 2 3 4))]
                     (.order bb ByteOrder/LITTLE_ENDIAN)
                     (doseq [v [[0.6 0.0 0.8] [0.0 1.0 0.0]]
                             x v]
                       (.putFloat bb (float x)))
                     (.array bb))]
      (sut/write-index! @mem root "cordelia" rows)
      (should= (vec expected) (vec (sut/vectors-bytes @mem root "cordelia")))))

  (it "row-key is scene-id + kind + model"
    (should= ["s1" :gist "mini-embed"]
             (sut/row-key {:scene-id "s1" :kind :gist :model "mini-embed"})))

  (context "index-crew!"
    (it "embeds gist and text for each sealed scene"
      (let [cfg {:embedding {:source :provider :provider "grover" :model "mini-embed"}}]
        (write-closed! @mem "cordelia" "2026-03-01-1000-ab12"
                       [{:id "2026-03-01-1000-s1x1"
                         :started-at "2026-03-01T10:00:00"
                         :ended-at "2026-03-01T10:05:00"
                         :gist "wine" :text "pinot"
                         :start-id "a" :end-id "b" :seal-reason :migrate}])
        (let [result (sut/index-crew! @mem root "cordelia" cfg {})
              rows   (sut/read-index @mem root "cordelia")
              norm   (fn [text] (vec (score/normalize-vector (embedding/grover-vector text))))]
          (should= 2 (:new result))
          (should= ["2026-03-01-1000-ab12" "2026-03-01-1000-s1x1" :gist "mini-embed"]
                   ((juxt :episode-id :scene-id :kind :model) (first rows)))
          (should= (norm "wine") (vec (:vector (first rows))))
          (should= (norm "pinot") (vec (:vector (second rows)))))))

    (it "is idempotent by (scene-id, kind, model)"
      (let [cfg {:embedding {:source :provider :provider "grover" :model "mini-embed"}}]
        (write-closed! @mem "cordelia" "ep1"
                       [{:id "s1" :gist "wine" :text "pinot"
                         :started-at "2026-03-01T10:00:00"
                         :ended-at "2026-03-01T10:05:00"}])
        (should= 2 (:new (sut/index-crew! @mem root "cordelia" cfg {})))
        (should= 0 (:new (sut/index-crew! @mem root "cordelia" cfg {})))))

    (it "rebuild drops existing rows and re-embeds"
      (let [cfg {:embedding {:source :provider :provider "grover" :model "mini-embed"}}]
        (write-closed! @mem "cordelia" "ep1"
                       [{:id "s1" :gist "wine" :text "pinot"
                         :started-at "2026-03-01T10:00:00"
                         :ended-at "2026-03-01T10:05:00"}])
        (sut/index-crew! @mem root "cordelia" cfg {})
        (write-closed! @mem "cordelia" "ep1"
                       [{:id "s1" :gist "grog" :text "rum"
                         :started-at "2026-03-01T10:00:00"
                         :ended-at "2026-03-01T10:05:00"}])
        (let [result (sut/index-crew! @mem root "cordelia" cfg {:rebuild? true})
              norm   (fn [text] (vec (score/normalize-vector (embedding/grover-vector text))))]
          (should= 2 (:new result))
          (should= [(norm "grog") (norm "rum")]
                   (mapv (comp vec :vector) (sut/read-index @mem root "cordelia"))))))

    (it "returns :no-embedding without writing an index"
      (write-closed! @mem "cordelia" "ep1"
                     [{:id "s1" :gist "wine" :text "pinot"}])
      (let [result (sut/index-crew! @mem root "cordelia" {} {})]
        (should= :no-embedding (:error result))
        (should-not (fs/exists? @mem (sut/index-path root "cordelia")))))

    (it "keeps old-model rows when the embedding model switches"
      (write-closed! @mem "cordelia" "ep1"
                     [{:id "s1" :gist "wine" :text "pinot"
                       :started-at "2026-03-01T10:00:00"
                       :ended-at "2026-03-01T10:05:00"}])
      (sut/index-crew! @mem root "cordelia"
                       {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
                       {})
      (let [result (sut/index-crew! @mem root "cordelia"
                                    {:embedding {:source :provider :provider "grover" :model "maxi-embed"}}
                                    {})]
        (should= 2 (:new result))
        (should= 4 (count (sut/read-index @mem root "cordelia")))))
    )

  (context "embed batching"
    (it "embeds in bounded batches so corpus-scale runs cannot time out one request"
      (let [cfg {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
            batch-sizes (atom [])
            real-embed embedding/embed-texts
            scenes (mapv (fn [i]
                           {:id (format "2026-03-01-1000-s%03d" i)
                            :started-at "2026-03-01T10:00:00"
                            :ended-at   "2026-03-01T10:05:00"
                            :gist (str "gist" i)
                            :text (str "text" i)})
                         (range 40))]
        (write-closed! @mem "cordelia" "2026-03-01-1000-ab12" scenes)
        (with-redefs [embedding/embed-texts
                      (fn [cfg texts]
                        (swap! batch-sizes conj (count texts))
                        (real-embed cfg texts))]
          (should= 80 (:new (sut/index-crew! @mem root "cordelia" cfg {}))))
        ;; capability probe (1 text) + ceil(80/64) batches
        (should= [1 64 16] @batch-sizes)))
    )
  )
