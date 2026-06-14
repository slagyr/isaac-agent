(ns isaac.step-tables-spec
  (:require
    [isaac.step-tables :as sut]
    [speclj.core :refer :all]))

;; region ----- Exact Value Matching -----

(describe "Table Matchers"

  (describe "exact value matching"

    (it "matches a single row with exact string"
      (let [result (sut/match-entries
                     {:headers ["name"] :rows [["Alice"]]}
                     [{:name "Alice"}])]
        (should (:pass? result))))

    (it "fails when value doesn't match"
      (let [result (sut/match-entries
                     {:headers ["name"] :rows [["Alice"]]}
                     [{:name "Bob"}])]
        (should-not (:pass? result))))

    (it "matches multiple columns"
      (let [result (sut/match-entries
                     {:headers ["name" "role"] :rows [["Alice" "admin"]]}
                     [{:name "Alice" :role "admin"}])]
        (should (:pass? result))))

    (it "matches numeric strings as numbers"
      (let [result (sut/match-entries
                     {:headers ["count"] :rows [["42"]]}
                     [{:count 42}])]
        (should (:pass? result))))

    (it "matches boolean strings"
      (let [result (sut/match-entries
                     {:headers ["active"] :rows [["true"]]}
                     [{:active true}])]
        (should (:pass? result))))

    (it "matches plain strings against keyword names"
      (let [result (sut/match-entries
                     {:headers ["level"] :rows [["warn"]]}
                     [{:level :warn}])]
        (should (:pass? result))))

    (it "matches subset of entries (unordered)"
      (let [result (sut/match-entries
                     {:headers ["name"] :rows [["Bob"]]}
                     [{:name "Alice"} {:name "Bob"} {:name "Carol"}])]
        (should (:pass? result)))))

  ;; endregion ^^^^^ Exact Value Matching ^^^^^

  ;; region ----- Empty Cell = Null -----

  (describe "empty cell = null"

    (it "matches nil when cell is empty"
      (let [result (sut/match-entries
                     {:headers ["name" "role"] :rows [["Alice" ""]]}
                     [{:name "Alice" :role nil}])]
        (should (:pass? result))))

    (it "fails when cell is empty but value exists"
      (let [result (sut/match-entries
                     {:headers ["role"] :rows [[""]]}
                     [{:role "admin"}])]
        (should-not (:pass? result)))))

  ;; endregion ^^^^^ Empty Cell = Null ^^^^^

  ;; region ----- Dot Notation & Array Indexing -----

  (describe "dot notation and array indexing"

    (it "accesses nested values with dot notation"
      (let [result (sut/match-entries
                     {:headers ["message.role"] :rows [["user"]]}
                     [{:message {:role "user"}}])]
        (should (:pass? result))))

    (it "accesses deeply nested values"
      (let [result (sut/match-entries
                     {:headers ["a.b.c"] :rows [["deep"]]}
                     [{:a {:b {:c "deep"}}}])]
        (should (:pass? result))))

    (it "accesses array elements"
      (let [result (sut/match-entries
                     {:headers ["items[0].name"] :rows [["bibelot"]]}
                     [{:items [{:name "bibelot"} {:name "doodad"}]}])]
        (should (:pass? result))))

    (it "accesses nested array elements"
      (let [result (sut/match-entries
                     {:headers ["message.content[0].type"] :rows [["toolCall"]]}
                     [{:message {:content [{:type "toolCall"}]}}])]
        (should (:pass? result))))

    (it "returns nil for missing paths"
      (let [result (sut/match-entries
                     {:headers ["missing.path"] :rows [[""]]}
                     [{:name "Alice"}])]
        (should (:pass? result)))))

  ;; endregion ^^^^^ Dot Notation & Array Indexing ^^^^^

  ;; region ----- Regex Pattern Matching -----

  (describe "regex pattern matching"

    (it "matches value against regex"
      (let [result (sut/match-entries
                     {:headers ["id"] :rows [["#\"[a-f0-9-]{36}\""]]}
                     [{:id "a1b2c3d4-e5f6-7890-abcd-ef1234567890"}])]
        (should (:pass? result))))

    (it "fails when regex doesn't match"
      (let [result (sut/match-entries
                     {:headers ["id"] :rows [["#\"\\d+\""]]}
                     [{:id "not-a-number"}])]
        (should-not (:pass? result))))

    (it "coerces value to string before matching"
      (let [result (sut/match-entries
                     {:headers ["timestamp"] :rows [["#\"\\d{13}\""]]}
                     [{:timestamp 1234567890123}])]
        (should (:pass? result)))))

  ;; endregion ^^^^^ Regex Pattern Matching ^^^^^

  ;; region ----- #index Positional Ordering -----

  (describe "#index positional ordering"

    (it "matches entries by position"
      (let [result (sut/match-entries
                     {:headers ["#index" "name"]
                      :rows    [["0" "Alice"] ["1" "Bob"]]}
                     [{:name "Alice"} {:name "Bob"}])]
        (should (:pass? result))))

    (it "fails when entry at index doesn't match"
      (let [result (sut/match-entries
                     {:headers ["#index" "name"]
                      :rows    [["0" "Alice"] ["1" "Bob"]]}
                     [{:name "Bob"} {:name "Alice"}])]
        (should-not (:pass? result))))

    (it "supports sparse index checks"
      (let [result (sut/match-entries
                     {:headers ["#index" "type"]
                      :rows    [["1" "message"]]}
                     [{:type "session"} {:type "message"} {:type "message"}])]
        (should (:pass? result))))

    (it "supports negative indices from the end"
      (let [result (sut/match-entries
                     {:headers ["#index" "name"]
                      :rows    [["-1" "gamma"] ["-2" "beta"]]}
                     [{:name "alpha"} {:name "beta"} {:name "gamma"}])]
        (should (:pass? result))))

    (it "reports a clear error for out-of-range negative indices"
      (let [result (sut/match-entries
                     {:headers ["#index" "name"]
                      :rows    [["-4" "alpha"]]}
                     [{:name "alpha"} {:name "beta"} {:name "gamma"}])]
        (should-not (:pass? result))
        (should= ["Row 0: index out of range: -4"] (:failures result)))))

  ;; endregion ^^^^^ #index Positional Ordering ^^^^^

  ;; region ----- #comment Meta Column -----

  (describe "#comment meta column"

    (it "ignores #comment column during matching"
      (let [result (sut/match-entries
                     {:headers ["name" "#comment"]
                      :rows    [["Alice" "this is a setup note"]]}
                     [{:name "Alice"}])]
        (should (:pass? result))))

    (it "works alongside #index"
      (let [result (sut/match-entries
                     {:headers ["#index" "name" "#comment"]
                      :rows    [["0" "Alice" "first entry"]
                                ["1" "Bob" "second entry"]]}
                     [{:name "Alice"} {:name "Bob"}])]
        (should (:pass? result)))))

  ;; endregion ^^^^^ #comment Meta Column ^^^^^

  ;; region ----- Regex Capture & Reference -----

  (describe "regex capture and reference"

    (it "captures a value with #\"regex\":name"
      (let [result (sut/match-entries
                     {:headers ["#index" "id"]
                      :rows    [["0" "#\".{36}\":header"]]}
                     [{:id "a1b2c3d4-e5f6-7890-abcd-ef1234567890"}])]
        (should (:pass? result))
        (should= "a1b2c3d4-e5f6-7890-abcd-ef1234567890" (get-in result [:captures "header"]))))

    (it "references a captured value with #name"
      (let [result (sut/match-entries
                     {:headers ["#index" "id" "parentId"]
                      :rows    [["0" "#\".+\":first" ""]
                                ["1" "#\".+\":second" "#first"]]}
                     [{:id "aaa" :parentId nil}
                      {:id "bbb" :parentId "aaa"}])]
        (should (:pass? result))))

    (it "fails when reference doesn't match"
     (let [result (sut/match-entries
                    {:headers ["#index" "id" "parentId"]
                     :rows    [["0" "#\".+\":first" ""]
                               ["1" "#\".+\":second" "#first"]]}
                    [{:id "aaa" :parentId nil}
                     {:id "bbb" :parentId "zzz"}])]
        (should-not (:pass? result)))))

  ;; endregion ^^^^^ Regex Capture & Reference ^^^^^

  ;; region ----- Wildcard Matching -----

  (describe "wildcard matching"

    (it "matches any non-nil value with #*"
      (let [result (sut/match-object
                     {:headers ["key" "value"]
                      :rows    [["result.sessionId" "#*"]]}
                     {:result {:sessionId "agent:main:acp:direct:user1"}})]
        (should (:pass? result))))

    (it "fails when #* matches nil"
      (let [result (sut/match-object
                     {:headers ["key" "value"]
                      :rows    [["result.sessionId" "#*"]]}
                     {:result {:sessionId nil}})]
        (should-not (:pass? result)))))

  ;; endregion ^^^^^ Wildcard Matching ^^^^^

  ;; region ----- Key-Value Vertical Table -----

  (describe "key-value vertical table"

    (it "matches a single object"
      (let [result (sut/match-object
                     {:headers ["key" "value"]
                      :rows    [["model" "qwen3-coder:30b"]
                                ["messages[0].role" "system"]]}
                     {:model    "qwen3-coder:30b"
                      :messages [{:role "system" :content "You are Isaac."}]})]
        (should (:pass? result))))

    (it "fails when a value doesn't match"
      (let [result (sut/match-object
                     {:headers ["key" "value"]
                      :rows    [["model" "gpt-4"]]}
                     {:model "qwen3-coder:30b"})]
        (should-not (:pass? result))))

    (it "supports regex in values"
      (let [result (sut/match-object
                     {:headers ["key" "value"]
                      :rows    [["id" "#\"[a-f0-9-]+\""]]}
                     {:id "abc-123-def"})]
        (should (:pass? result)))))

  ;; endregion ^^^^^ Key-Value Vertical Table ^^^^^

  ;; region ----- Error Reporting -----

  (describe "error reporting"

    (it "reports per-cell expected vs actual"
      (let [result (sut/match-entries
                     {:headers ["name" "role"]
                      :rows    [["Alice" "admin"]]}
                     [{:name "Alice" :role "user"}])]
        (should-not (:pass? result))
        (should-contain "role" (first (:failures result)))
        (should-contain "admin" (first (:failures result)))
        (should-contain "user" (first (:failures result))))))

  ;; endregion ^^^^^ Error Reporting ^^^^^

  )
