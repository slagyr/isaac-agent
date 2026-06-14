(ns isaac.session.compaction-schema-spec
  (:require
    [c3kit.apron.schema :as schema]
    [isaac.session.compaction-schema :as sut]
    [speclj.core :refer [describe it should should=]]))

(describe "Compaction schema"

  (it "conforms a valid compaction config"
    (should= {:strategy :slinky :threshold 0.8 :head 0.4 :async? false}
             (schema/conform! sut/config-schema
                              {:strategy :slinky :threshold 0.8 :head 0.4 :async? false})))

  (it "allows partial compaction configs without adding a cross-field error"
    (should= {:threshold 0.8}
             (schema/conform! sut/config-schema {:threshold 0.8}))
    (should= {:head 0.3}
             (schema/conform! sut/config-schema {:head 0.3})))

  (it "returns readable field errors for out-of-range values"
    (let [result (schema/conform sut/config-schema {:threshold -0.1 :head 0.4})]
      (should (schema/error? result))
      (should= {:threshold "must be a percentage in [0.0, 1.0); e.g. 0.8 for 80% of context-window"}
               (schema/message-map result))))

  (it "returns a readable entity error when head is not smaller than threshold"
    (let [result (schema/conform sut/config-schema {:threshold 0.8 :head 0.8})]
      (should (schema/error? result))
      (should= {:head-threshold "head must be smaller than threshold"}
               (schema/message-map result))))

  (it "rejects threshold >= 1.0"
    (let [result (schema/conform sut/config-schema {:threshold 1.0 :head 0.3})]
      (should (schema/error? result))))

  (it "rejects unknown compaction strategies"
    (let [result (schema/conform sut/config-schema {:strategy :rainbow})]
      (should (schema/error? result))
      (should= {:strategy "must be one of :rubberband, :slinky"}
               (schema/message-map result)))))
