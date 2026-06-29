(ns isaac.session.cli-spec
  (:require
    [cheshire.core :as json]
    [isaac.fs :as fs]
    [isaac.tool.memory :as memory]
    [isaac.session.cli :as sut]
    [isaac.session.store.spi :as store]
    [isaac.session.spec-helper :as helper]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "session cli"

  #_{:clj-kondo/ignore [:invalid-arity :unresolved-symbol]}
  (around [it]
    (nexus/-with-nested-nexus {:root "/test/sessions"}
      (helper/with-memory-store
        (it))))

  (it "shows management help by default instead of listing sessions"
    (helper/create-session! "/test/sessions" "joe" {:crew "main"})
    (let [output (with-out-str (should= 0 (sut/run-fn {:home "/test" :_raw-args []})))]
      (should-not-contain "joe" output)
      (should-not-contain "SESSION" output)))

  (it "renders list output as JSON with sorted tags"
    (helper/create-session! "/test/sessions" "joe" {:crew "main" :tags #{:role/worker :project/chess}})
    (let [output (with-out-str (should= 0 (sut/run-fn {:home "/test" :_raw-args ["list" "--json"]})))
          rows   (json/parse-string output true)
          joe    (some #(when (= "joe" (:name %)) %) rows)]
      (should= "joe" (:name joe))
      (should= "main" (:crew joe))
      (should= ["project/chess" "role/worker"] (:tags joe))))

  (it "renders show output as JSON"
    (helper/create-session! "/test/sessions" "joe" {:crew "main" :tags #{:project/x}})
    (helper/update-session! "/test/sessions" "joe" {:crew "alice" :tags #{:project/x}})
    (let [output (with-out-str (should= 0 (sut/run-fn {:home "/test" :_raw-args ["show" "joe" "--json"]})))
          row    (json/parse-string output true)]
      (should= "joe" (:name row))
      (should= "alice" (:crew row))
      (should= ["project/x"] (:tags row))))

  (it "filters sessions by tag"
    (helper/create-session! "/test/sessions" "joe" {:crew "main" :tags #{:project/chess}})
    (helper/create-session! "/test/sessions" "sue" {:crew "main" :tags #{:project/poker}})
    (let [output (with-out-str (should= 0 (sut/run-fn {:home "/test" :_raw-args ["list" "--tag" "project/chess"]})))]
      (should-contain "joe" output)
      (should-not-contain "sue" output)))

  (it "filters idle sessions with not-in-flight"
    (helper/create-session! "/test/sessions" "joe" {:crew "main" :tags #{:project/chess}})
    (helper/create-session! "/test/sessions" "sue" {:crew "main" :tags #{:project/chess}})
    (store/mark-in-flight! (store/registered-store) "joe")
    (let [output (with-out-str (should= 0 (sut/run-fn {:home "/test" :_raw-args ["list" "--tag" "project/chess" "--not-in-flight"]})))]
      (should-contain "sue" output)
      (should-not-contain "joe" output)))

  (it "activates a missing session when sessions set targets it"
    (should= 0 (sut/run-fn {:home "/test" :_raw-args ["set" "relay.tags.role/engineer"]}))
    (should= #{:role/engineer}
             (:tags (helper/get-session "/test/sessions" "relay"))))

  (it "adds a tag with sessions set"
    (helper/create-session! "/test/sessions" "joe" {:crew "main" :tags #{:project/x}})
    (should= 0 (sut/run-fn {:home "/test" :_raw-args ["set" "joe.tags.wip"]}))
    (should= #{:project/x :wip}
             (:tags (helper/get-session "/test/sessions" "joe"))))

  (it "removes a tag with sessions unset"
    (helper/create-session! "/test/sessions" "joe" {:crew "main" :tags #{:project/x :wip}})
    (should= 0 (sut/run-fn {:home "/test" :_raw-args ["unset" "joe.tags.wip"]}))
    (should= #{:project/x}
             (:tags (helper/get-session "/test/sessions" "joe"))))

  (it "supports namespaced tag members"
    (helper/create-session! "/test/sessions" "joe" {:crew "main" :tags #{}})
    (should= 0 (sut/run-fn {:home "/test" :_raw-args ["set" "joe.tags.role/worker"]}))
    (should= #{:role/worker}
             (:tags (helper/get-session "/test/sessions" "joe"))))

  (it "reassigns the crew when the target exists"
    (helper/create-session! "/test/sessions" "joe" {:crew "main"})
    (fs/spit (nexus/get :fs) "/test/.isaac/config/isaac.edn" (pr-str {:crew {"main" {} "alice" {}}}))
    (should= 0 (sut/run-fn {:home "/test" :_raw-args ["set" "joe.crew" "alice"]}))
    (should= "alice" (:crew (helper/get-session "/test/sessions" "joe"))))

  (it "rejects immutable fields"
    (helper/create-session! "/test/sessions" "joe" {:crew "main"})
    (let [err (binding [*err* (java.io.StringWriter.)]
                (should= 1 (sut/run-fn {:home "/test" :_raw-args ["set" "joe.id" "different-id"]}))
                (str *err*))]
      (should-contain "immutable" err)))

  (it "rejects system-managed fields"
    (helper/create-session! "/test/sessions" "joe" {:crew "main"})
    (let [err (binding [*err* (java.io.StringWriter.)]
                (should= 1 (sut/run-fn {:home "/test" :_raw-args ["set" "joe.input-tokens" "42"]}))
                (str *err*))]
      (should-contain "system-managed" err)))

  (it "rejects unknown fields"
    (helper/create-session! "/test/sessions" "joe" {:crew "main"})
    (let [err (binding [*err* (java.io.StringWriter.)]
                (should= 1 (sut/run-fn {:home "/test" :_raw-args ["set" "joe.bogus" "value"]}))
                (str *err*))]
      (should-contain "bogus" err)))

  (it "bumps updated-at on successful mutation"
    (binding [memory/*now* (java.time.Instant/parse "2026-05-23T12:00:00Z")]
      (helper/create-session! "/test/sessions" "joe" {:crew "main" :updated-at "1999-12-31T23:59:59Z"}))
    (binding [memory/*now* (java.time.Instant/parse "2026-05-23T12:00:01Z")]
      (should= 0 (sut/run-fn {:home "/test" :_raw-args ["set" "joe.tags.wip"]})))
    (should= "2026-05-23T12:00:01Z"
             (:updated-at (helper/get-session "/test/sessions" "joe"))))

  (it "rejects reassignment to an unknown crew"
    (helper/create-session! "/test/sessions" "joe" {:crew "main"})
    (fs/spit (nexus/get :fs) "/test/.isaac/config/isaac.edn" (pr-str {:crew {"main" {}}}))
    (let [err (binding [*err* (java.io.StringWriter.)]
                (should= 1 (sut/run-fn {:home "/test" :_raw-args ["set" "joe.crew" "nobody"]}))
                (str *err*))]
      (should-contain "nobody" err))))
