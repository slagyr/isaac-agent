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
