(ns isaac.session.store.impl-common-spec
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.session.store.impl-common :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private test-dir "/test/impl-common")
(def ^:private session-id "sess")

(defn- fs* [] (nexus/get :fs))

(def ^:private entries
  [{:type "message" :id "a" :role "user"      :content "first"}
   {:type "message" :id "b" :role "assistant" :content "second reported a CI regression"}
   {:type "message" :id "c" :role "user"      :content "third"}])

(describe "impl-common ednl transcript"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (sut/write-transcript! test-dir session-id entries (fs*))
      (example)))

  (it "round-trips EDN maps as one object per line"
    (should= entries (sut/read-transcript-raw test-dir session-id (fs*))))

  (it "read-edn-line reads a keyword map from pr-str"
    (should= {:type "message" :id "a"}
             (sut/read-edn-line (pr-str {:type "message" :id "a"}))))

  (it "reads the last EDNL object from a tail window"
    (should= (last entries)
             (sut/last-transcript-entry (fs*) (sut/current-transcript-path test-dir session-id))))

  (it "grows the tail window when the last object is larger than the first probe"
    (let [big {:type "message" :id "z" :content (apply str (repeat 5000 "x"))}
          all (conj entries big)]
      (sut/write-transcript! test-dir session-id all (fs*))
      (should= big (sut/last-transcript-entry (fs*) (sut/current-transcript-path test-dir session-id)))))

  (it "returns nil when the transcript file is missing"
    (should-be-nil (sut/last-transcript-entry (fs*) (sut/current-transcript-path test-dir "missing")))))

(describe "impl-common write-edn"

  (it "serializes a session header with pr-str"
    (should= "{:type \"session\", :id \"abc12345\", :cwd \"/tmp\"}\n"
             (sut/write-edn {:type "session" :id "abc12345" :cwd "/tmp"})))

  (it "write-transcript! persists EDN lines"
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (sut/write-transcript! test-dir session-id [{:type "session" :id "abc12345"}] (fs*))
      (should= ["{:type \"session\", :id \"abc12345\"}"]
               (str/split-lines (fs/slurp (fs*) (sut/current-transcript-path test-dir session-id)))))))

(describe "impl-common turn markers"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "reads a legacy sessions/turns/<id>.edn marker when the product path is absent"
    (let [fs*    (fs*)
          marker {:source :comm :started-at "2026-04-21T09:59:30Z"}]
      (fs/mkdirs fs* (str test-dir "/sessions/turns"))
      (fs/spit fs* (sut/legacy-turn-marker-path test-dir session-id) (pr-str marker))
      (let [found (sut/turn-markers* test-dir fs*)]
        (should= 1 (count found))
        (should= :comm (:source (first found)))
        (should= session-id (:session-id (first found))))))

  (it "prefers the product sessions/<id>/turn.edn marker over the legacy path"
    (let [fs*     (fs*)
          product {:source :hail :started-at "2026-04-21T10:00:00Z"}
          legacy  {:source :comm :started-at "2026-04-21T09:00:00Z"}]
      (fs/mkdirs fs* (str test-dir "/sessions/" session-id))
      (fs/mkdirs fs* (str test-dir "/sessions/turns"))
      (fs/spit fs* (sut/turn-marker-path test-dir session-id) (pr-str product))
      (fs/spit fs* (sut/legacy-turn-marker-path test-dir session-id) (pr-str legacy))
      (let [found (sut/turn-markers* test-dir fs*)]
        (should= 1 (count found))
        (should= :hail (:source (first found)))))))

(describe "impl-common chronicle-transcript"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "concatenates the frozen compacted prefix then compacted current"
    (let [fs*     (fs*)
          header  {:type "session" :id "hdr"}
          m1      {:type "message" :id "m1" :message {:role "user" :content "First"}}
          m2      {:type "message" :id "m2" :message {:role "assistant" :content "Second"}}
          m3      {:type "message" :id "m3" :message {:role "user" :content "Third"}}
          compact {:type "compaction" :id "c1" :summary "Summary"}]
      (sut/write-ednl! fs* (sut/frozen-transcript-path test-dir session-id 0)
                       [header m1 m2])
      (sut/write-transcript! test-dir session-id [compact m3] fs*)
      (let [chronicle (sut/read-chronicle test-dir session-id fs*)
            types     (mapv :type chronicle)]
        ;; retain freezes only the compacted prefix; kept tail lives only
        ;; in the new current.
        (should= ["session" "message" "message" "compaction" "message"] types)
        (should= 5 (count chronicle))
        (should= "m3" (:id (last chronicle)))))))
