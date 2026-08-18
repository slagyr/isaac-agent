(ns isaac.session.store.impl-common-spec
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.fs :as fs]
    [isaac.session.store.impl-common :as sut]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def ^:private test-dir "/test/impl-common")
(def ^:private session-file "sess.jsonl")

(defn- fs* [] (nexus/get :fs))

(def ^:private entries
  [{:type "message" :id "a" :role "user"      :content "first"}
   {:type "message" :id "b" :role "assistant" :content "second reported a CI regression"}
   {:type "message" :id "c" :role "user"      :content "third"}])

(describe "impl-common read-transcript-from-offset"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (sut/write-transcript! test-dir session-file entries (fs*))
      (example)))

  (it "reads every entry from offset 0"
    (should= entries (sut/read-transcript-from-offset test-dir session-file 0 (fs*))))

  (it "reads from an exact entry boundary"
    (let [boundary (sut/transcript-byte-offset (take 1 entries))]   ; start of entry b
      (should= (drop 1 entries)
               (sut/read-transcript-from-offset test-dir session-file boundary (fs*)))))

  (it "snaps a mid-line offset back to the line start instead of parsing a partial line (isaac-63f3)"
    (let [b-start  (sut/transcript-byte-offset (take 1 entries))    ; start of entry b's line
          mid-line (+ b-start 20)]                                  ; drifted into entry b
      (should= (drop 1 entries)
               (sut/read-transcript-from-offset test-dir session-file mid-line (fs*)))))

  (it "clamps an offset past EOF to empty"
    (should= []
             (sut/read-transcript-from-offset test-dir session-file 1000000 (fs*)))))

(describe "impl-common orphan tool-result read path (isaac-0h7b)"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nexus {:fs (fs/mem-fs)}
      (example)))

  (it "drops a toolResult whose toolCall precedes the offset boundary"
    (let [entries [{:type "message" :id "call"
                    :message {:role "assistant"
                               :content [{:type "toolCall" :id "tc-1" :name "read" :arguments {}}]}}
                   {:type "message" :id "result"
                    :message {:role "toolResult" :toolCallId "tc-1" :content "ok"}}
                   {:type "message" :id "reply"
                    :message {:role "assistant" :content "done"}}]
          _       (sut/write-transcript! test-dir session-file entries (fs*))
          offset  (sut/transcript-byte-offset (take 1 entries))]
      (should= [(nth entries 2)]
               (sut/read-transcript-from-offset test-dir session-file offset (fs*)))))

  (it "preserves a paired toolCall and toolResult in the active head"
    (let [entries [{:type "message" :id "call"
                    :message {:role "assistant"
                               :content [{:type "toolCall" :id "tc-1" :name "read" :arguments {}}]}}
                   {:type "message" :id "result"
                    :message {:role "toolResult" :toolCallId "tc-1" :content "ok"}}
                   {:type "message" :id "reply"
                    :message {:role "assistant" :content "done"}}]
          _       (sut/write-transcript! test-dir session-file entries (fs*))]
      (should= entries
               (sut/read-transcript-from-offset test-dir session-file 0 (fs*))))))

(describe "impl-common write-json"

  (it "serializes a session header without cheshire (native-bb Constructor.newInstance)"
    (with-redefs [json/generate-string (fn [& _] (throw (ex-info "cheshire generate-string is banned on native bb" {})))]
      (should= "{\"type\":\"session\",\"id\":\"abc12345\",\"cwd\":\"/tmp\"}"
               (sut/write-json {:type "session" :id "abc12345" :cwd "/tmp"}))))

  (it "serializes nested tool-call content without cheshire"
    (with-redefs [json/generate-string (fn [& _] (throw (ex-info "cheshire generate-string is banned on native bb" {})))]
      (should= (str "{\"type\":\"message\",\"message\":"
                    "{\"role\":\"assistant\",\"content\":"
                    "[{\"type\":\"toolCall\",\"id\":\"tc-1\",\"name\":\"read\",\"arguments\":{\"file_path\":\"notes.txt\"}}]}}")
               (sut/write-json {:type    "message"
                                :message {:role    "assistant"
                                          :content [{:type      "toolCall"
                                                     :id        "tc-1"
                                                     :name      "read"
                                                     :arguments {:file_path "notes.txt"}}]}}))))

  (it "write-transcript! persists a session header without cheshire"
    (with-redefs [json/generate-string (fn [& _] (throw (ex-info "cheshire generate-string is banned on native bb" {})))]
      (nexus/-with-nexus {:fs (fs/mem-fs)}
        (sut/write-transcript! test-dir session-file [{:type "session" :id "abc12345"}] (fs*))
        (should= ["{\"type\":\"session\",\"id\":\"abc12345\"}"]
                 (str/split-lines (fs/slurp (fs*) (sut/transcript-path test-dir session-file)))))))

  )
