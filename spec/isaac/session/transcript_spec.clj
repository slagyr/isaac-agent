(ns isaac.session.transcript-spec
  (:require
    [isaac.session.transcript :as sut]
    [speclj.core :refer :all]))

(describe "isaac.session.transcript"

  (describe "content->text"

    (it "returns string content as-is"
      (should= "hello" (sut/content->text "hello")))

    (it "joins text blocks from vector content"
      (should= "hello world"
               (sut/content->text [{:type "text" :text "hello "}
                                   {:type "image" :url "https://example.com/cat.png"}
                                   {:type "text" :text "world"}])))

    (it "returns nil for unsupported content shapes"
      (should-be-nil (sut/content->text {:type "text" :text "hello"}))))

  (describe "tool-calls"

    (it "extracts a top-level tool call message"
      (should= [{:type "toolCall" :id "tc-1" :name "read" :arguments {:path "a.txt"}}]
               (sut/tool-calls {:type "toolCall" :id "tc-1" :name "read" :arguments {:path "a.txt"}})))

    (it "extracts tool calls from vector content"
      (should= [{:type "toolCall" :id "tc-2" :name "grep" :arguments {:pattern "lettuce"}}]
               (sut/tool-calls {:content [{:type "toolCall" :id "tc-2" :name "grep" :arguments {:pattern "lettuce"}}]})))

    (it "returns ALL toolCall blocks when content vector has multiple tool calls"
      (should= [{:type "toolCall" :id "tc-a" :name "read" :arguments {}}
                {:type "toolCall" :id "tc-b" :name "write" :arguments {}}]
               (sut/tool-calls {:content [{:type "toolCall" :id "tc-a" :name "read" :arguments {}}
                                          {:type "toolCall" :id "tc-b" :name "write" :arguments {}}]})))

    (it "filters to only toolCall blocks when vector has mixed content types"
      (should= [{:type "toolCall" :id "tc-c" :name "exec" :arguments {}}]
               (sut/tool-calls {:content [{:type "text" :text "thinking..."}
                                          {:type "toolCall" :id "tc-c" :name "exec" :arguments {}}]})))

    (it "returns nil for non-tool-call content"
      (should-be-nil (sut/tool-calls {:content "plain text"}))
      (should-be-nil (sut/tool-calls {:content [{:type "text" :text "no tools"}]}))))

  (describe "first-tool-call"

    (it "returns the first parsed tool call"
      (should= {:type "toolCall" :id "tc-4" :name "write" :arguments {:content "hi"}}
               (sut/first-tool-call {:content [{:type "toolCall" :id "tc-4" :name "write" :arguments {:content "hi"}}
                                              {:type "toolCall" :id "tc-5" :name "read" :arguments {:path "x"}}]})))

    (it "returns nil when no tool calls are present"
      (should-be-nil (sut/first-tool-call {:content "hello"})))))
