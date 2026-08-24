(ns isaac.tool.names-spec
  (:require
    [isaac.tool.names :as sut]
    [speclj.core :refer :all]))

(describe "tool names"

  (context "wire-name"

    (it "renders :fs/read as fs__read"
      (should= "fs__read" (sut/wire-name :fs/read)))

    (it "renders :exec/run as exec__run"
      (should= "exec__run" (sut/wire-name :exec/run)))

    (it "renders a namespaced string token as the wire name"
      (should= "fs__read" (sut/wire-name "fs/read")))

    (it "passes through an already-canonical wire name"
      (should= "fs__read" (sut/wire-name "fs__read")))

    (it "renders an unqualified keyword as its name"
      (should= "spyglass" (sut/wire-name :spyglass)))

    (it "renders :fs/* as the namespace glob prefix fs__"
      (should= "fs__" (sut/wire-name :fs/*)))
    )

  (context "config-token"

    (it "renders fs__read as :fs/read"
      (should= :fs/read (sut/config-token "fs__read")))

    (it "passes through :exec/run"
      (should= :exec/run (sut/config-token :exec/run)))

    (it "renders a namespaced string token as the config keyword"
      (should= :fs/read (sut/config-token "fs/read")))

    (it "renders the glob prefix fs__ as :fs/*"
      (should= :fs/* (sut/config-token "fs__")))
    )

  (context "config-token?"

    (it "accepts a namespaced keyword"
      (should (sut/config-token? :fs/read)))

    (it "accepts a namespace glob"
      (should (sut/config-token? :fs/*)))

    (it "accepts :all as the exempt policy token"
      (should (sut/config-token? :all)))

    (it "rejects an unqualified keyword"
      (should-not (sut/config-token? :read)))

    (it "rejects a bare string"
      (should-not (sut/config-token? "read")))

    (it "rejects a wildcard that is not the name of a namespaced keyword"
      (should-not (sut/config-token? :*)))
    )

  (context "matches?"

    (it "matches an exact namespaced token to its wire name"
      (should (sut/matches? :fs/read "fs__read")))

    (it "does not match an exact token against a sibling in the family"
      (should-not (sut/matches? :fs/read "fs__write")))

    (it "matches a namespace glob against every wire name in that family"
      (should (sut/matches? :fs/* "fs__write"))
      (should (sut/matches? :fs/* "fs__read")))

    (it "does not match a namespace glob against another family"
      (should-not (sut/matches? :fs/* "exec__run")))

    (it "treats a wire glob prefix as a glob token"
      (should (sut/glob-token? "fs__"))
      (should (sut/glob-token? :fs/*)))

    (it "does not treat an exact wire name as a glob"
      (should-not (sut/glob-token? "fs__read")))

    (it "does not treat bare * as a glob"
      (should-not (sut/matches? :* "fs__read")))

    (it "matches a wire glob prefix against every name in that family"
      (should (sut/matches? "fs__" "fs__write"))
      (should (sut/matches? "fs__" "fs__read")))

    (it "does not match a wire glob prefix against another family"
      (should-not (sut/matches? "fs__" "exec__run")))
    )

  (context "allowed?"

    (it "allows a wire name covered by an exact token"
      (should (sut/allowed? [:fs/read] "fs__read")))

    (it "denies a wire name not covered by any token"
      (should-not (sut/allowed? [:fs/read] "fs__write")))

    (it "allows a wire name covered by a namespace glob"
      (should (sut/allowed? [:fs/*] "fs__grep")))

    (it "allows a wire name covered by a wire glob prefix"
      (should (sut/allowed? ["fs__"] "fs__grep")))

    (it "allows an unqualified token as an exact wire name"
      (should (sut/allowed? [:spyglass] "spyglass")))

    (it "returns nil policy as deny-all"
      (should-not (sut/allowed? nil "fs__read")))
    )

  (context "policy-list"
    (it "treats the keyword :all as a policy list, not a seq of tokens"
      (should= [:all] (sut/policy-list :all)))

    (it "returns a sequential of tokens as-is"
      (should= [:fs/read :exec/run] (sut/policy-list [:fs/read :exec/run])))

    (it "treats nil as an empty policy list"
      (should= [] (sut/policy-list nil)))
    )

  (context "covers?"
    (it "treats :all as covering every wire name"
      (should (sut/covers? :all "exec__run"))
      (should (sut/covers? :all "fs__read")))

    (it "does not treat a vector of tokens as :all"
      (should-not (sut/covers? [:fs/read] "exec__run")))

    (it "matches an exact token or namespace glob"
      (should (sut/covers? [:fs/read] "fs__read"))
      (should (sut/covers? [:fs/*] "fs__grep"))
      (should-not (sut/covers? [:fs/read] "fs__write")))
    )

  (context "cascade"
    (it "denies every tool when both layers are empty"
      (should-not (sut/cascade-allowed? {} {} "fs__read")))

    (it "inherits a global :allow :all when the crew omits :tools"
      (should (sut/cascade-allowed? {:allow :all} nil "exec__run"))
      (should (sut/cascade-allowed? {:allow :all} nil "fs__read")))

    (it "inherits a global deny of exec"
      (should-not (sut/cascade-allowed? {:allow :all :deny [:exec/run]} nil "exec__run"))
      (should (sut/cascade-allowed? {:allow :all :deny [:exec/run]} nil "fs__read")))

    (it "lets crew allow re-enable a globally denied tool"
      (should (sut/cascade-allowed? {:allow :all :deny [:exec/run]}
                                    {:allow [:exec/run]}
                                    "exec__run")))

    (it "overlays crew deny without dropping a global deny"
      (let [global {:allow :all :deny [:exec/run]}
            crew   {:deny [:fs/*]}]
        (should-not (sut/cascade-allowed? global crew "exec__run"))
        (should-not (sut/cascade-allowed? global crew "fs__read"))
        (should (sut/cascade-allowed? global crew "web__fetch"))))

    (it "lets crew deny :all then allow memory leave only memory tools"
      (let [global {:allow :all}
            crew   {:deny :all :allow [:memory/*]}]
        (should (sut/cascade-allowed? global crew "memory__get"))
        (should-not (sut/cascade-allowed? global crew "fs__read"))
        (should-not (sut/cascade-allowed? global crew "exec__run"))))

    (it "overlays a crew family deny while other global allows remain"
      (let [global {:allow :all}
            crew   {:deny [:web/*]}]
        (should-not (sut/cascade-allowed? global crew "web__fetch"))
        (should-not (sut/cascade-allowed? global crew "web__search"))
        (should (sut/cascade-allowed? global crew "fs__read"))
        (should (sut/cascade-allowed? global crew "exec__run"))))

    (it "lets crew allow beat a later-listed crew deny of the same family (crew allow is last)"
      (should (sut/cascade-allowed? {:allow :all}
                                    {:deny [:linear/delete_issue] :allow [:linear/*]}
                                    "linear__delete_issue")))
    )
  )
