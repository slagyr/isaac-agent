(ns isaac.tool.hail-spec
  (:require
    [isaac.fs :as fs]
    [isaac.hail.queue :as queue]
    [isaac.nexus :as nexus]
    [isaac.session.spec-helper :as helper]
    [isaac.tool.hail :as sut]
    [speclj.core :refer :all]))

(describe "hail tool"

  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (around [example]
    (nexus/-with-nested-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (helper/with-memory-store
        (example))))

  (it "sends a hail from the calling crew and returns the hail id"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main"})
    (let [sent* (atom nil)]
      (with-redefs [queue/send! (fn [record]
                                  (reset! sent* record)
                                  (assoc record :id "hail-1"))]
        (should= {:result "hail-1"}
                 (sut/hail-send-tool {"session_key" "work-sess"
                                      "frequency"   {:band "bean-pickup"}
                                      "payload"     {:n 1}}))
        (should= {:frequency {:band "bean-pickup"}
                  :payload   {:n 1}
                  :from      :crew/main}
                 @sent*))))

  (it "normalizes JSON-shaped frequency keys before persisting"
    (helper/create-session! "/test/isaac" "work-sess" {:crew "main"})
    (let [sent* (atom nil)]
      (with-redefs [queue/send! (fn [record]
                                  (reset! sent* record)
                                  (assoc record :id "hail-1"))]
        (sut/hail-send-tool {"session_key" "work-sess"
                             "frequency"   {"band" "bean-pickup"}
                             "payload"     {"n" 1}})
        (should= {:frequency {:band "bean-pickup"}
                  :payload   {"n" 1}
                  :from      :crew/main}
                 @sent*))))

  (it "errors when the session does not exist"
    (let [result (sut/hail-send-tool {"session_key" "missing"
                                      "frequency"   {:band "bean-pickup"}})]
      (should (:isError result))
      (should= "session not found: missing" (:error result)))))
