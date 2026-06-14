(ns isaac.llm.followup-spec
  (:require
    [isaac.llm.followup :as sut]
    [speclj.core :refer :all]))

(describe "isaac.llm.followup"

  (it "zips tool calls with tool results through a builder fn"
    (should= ["read:x" "write:y"]
             (sut/map-tool-results [{:name "read"} {:name "write"}]
                                   ["x" "y"]
                                   (fn [tc result] (str (:name tc) ":" result)))))

  (it "appends assistant and result messages after the original request messages"
    (should= [{:role "user" :content "go"}
              {:role "assistant" :content "thinking"}
              {:role "tool" :content "done"}]
             (sut/append-followup-messages {:messages [{:role "user" :content "go"}]}
                                           {:role "assistant" :content "thinking"}
                                           [{:role "tool" :content "done"}])))

  (it "appends an assistant message followed by one role=tool reply per result"
    (let [request      {:messages [{:role "user" :content "Go"}]}
          assistant-msg {:role "assistant"
                         :content ""
                         :tool_calls [{:function {:name "read" :arguments {:path "x"}}}
                                      {:function {:name "write" :arguments {:path "y"}}}]}
          tool-calls   [{:id "tc1" :name "read" :arguments {:path "x"}}
                        {:id "tc2" :name "write" :arguments {:path "y"}}]
          tool-results ["file contents" "wrote ok"]
          messages     (sut/raw-tool-call-followup-messages request assistant-msg tool-calls tool-results)]
      (should= [{:role "user" :content "Go"}
                assistant-msg
                {:role "tool" :content "file contents"}
                {:role "tool" :content "wrote ok"}]
               messages)))

  (it "preserves a nil assistant content when the caller chooses not to default it"
    (let [request       {:messages []}
          assistant-msg {:role "assistant" :content nil :tool_calls []}
          messages      (sut/raw-tool-call-followup-messages request assistant-msg [{:id "tc1"}] ["ok"])]
      (should= assistant-msg (first messages)))))
