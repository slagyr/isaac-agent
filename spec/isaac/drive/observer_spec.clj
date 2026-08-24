(ns isaac.drive.observer-spec
  (:require
    [clojure.string :as str]
    [isaac.drive.observer :as sut]
    [isaac.logger :as log]
    [speclj.core :refer [after before describe it should should-be-nil should= should-contain should-not]]))

(describe "turn observer registry"

  (before (sut/clear!))
  (after (sut/clear!))

  (it "resolves a registered observer by name"
    (let [obs (reify sut/TurnObserver
                (on-turn-started [_ _])
                (on-turn-ended [_ _ _])
                (on-turn-died [_ _ _]))]
      (sut/register! :lookout (constantly obs))
      (should= obs (sut/resolve :lookout))))

  (it "returns nil for an unknown observer name"
    (should-be-nil (sut/resolve :foghorn)))

  (it "looks up a submitted name-only ref"
    (sut/register! :lookout (fn [_] :lookout-impl))
    (should= :lookout-impl (sut/resolve-ref :lookout)))

  (it "looks up a submitted [name params] ref and passes params to the factory"
    (let [seen (atom nil)]
      (sut/register! :foreman (fn [params] (reset! seen params) :foreman-impl))
      (should= :foreman-impl (sut/resolve-ref [:foreman "bean-work" "bn-7"]))
      (should= ["bean-work" "bn-7"] @seen)))

  (it "parses a CLI observer flag as a name-only keyword"
    (should= :lookout (sut/parse-ref "lookout")))

  (it "parses a CLI observer flag as name:params"
    (should= [:foreman "bean-work" "bn-7"] (sut/parse-ref "foreman:bean-work/bn-7")))

  (it "classifies a successful result as :ok"
    (should= :ok (sut/outcome {})))

  (it "classifies an error result as :error with a reason"
    (should= {:kind :error :reason "fog rolled in"}
             (sut/outcome {:error :http-error :message "fog rolled in"})))

  (it "classifies a thrown exception result as :error"
    (should= {:kind :error :reason "stack overflow"}
             (sut/outcome {:error :exception :message "stack overflow"})))

  (it "isolates a throwing observer so finalization continues"
    (let [second (atom nil)]
      (log/capture-logs
        (sut/notify! [(reify sut/TurnObserver
                        (on-turn-started [_ _])
                        (on-turn-ended [_ _ _] (throw (Exception. "lookout exploded")))
                        (on-turn-died [_ _ _]))
                      (reify sut/TurnObserver
                        (on-turn-started [_ _])
                        (on-turn-ended [_ ctx outcome] (reset! second [ctx outcome]))
                        (on-turn-died [_ _ _]))]
                     :on-turn-ended
                     {:session-key "crows-nest"}
                     :ok)
        (let [entry (first (filter #(= :turn/observer-failed (:event %)) @log/captured-logs))]
          (should= :warn (:level entry))
          (should= "lookout exploded" (:error entry))))
      (should= [{:session-key "crows-nest"} :ok] @second)))

  (it "resolves a submitted lookout via the builtin registry"
    (let [result (sut/resolve-submitted [:lookout])]
      (should-be-nil (:error result))
      (should= 1 (count (:observers result)))))

  (it "refuses an unknown submitted observer name"
    (let [result (sut/resolve-submitted [[:foghorn "xyz"]])]
      (should= :unknown-observer (:error result))
      (should-contain "foghorn" (:message result))
      (should-contain "unknown observer" (:message result))))

  (it "attaches an ambient observer that is included for every turn"
    (let [obs (reify sut/TurnObserver
                (on-turn-started [_ _])
                (on-turn-ended [_ _ _])
                (on-turn-died [_ _ _]))]
      (sut/attach! obs)
      (should= [obs] (sut/ambient))))

  (it "merges ambient observers ahead of submitted ones"
    (let [ambient-obs (reify sut/TurnObserver
                        (on-turn-started [_ _])
                        (on-turn-ended [_ _ _])
                        (on-turn-died [_ _ _]))
          submitted   (reify sut/TurnObserver
                        (on-turn-started [_ _])
                        (on-turn-ended [_ _ _])
                        (on-turn-died [_ _ _]))]
      (sut/attach! ambient-obs)
      (should= [ambient-obs submitted] (sut/for-turn [submitted]))))

  (it "returns only ambient observers when none are submitted"
    (let [obs (reify sut/TurnObserver
                (on-turn-started [_ _])
                (on-turn-ended [_ _ _])
                (on-turn-died [_ _ _]))]
      (sut/attach! obs)
      (should= [obs] (sut/for-turn nil))))

  (it "is identity when no ambient observers are attached"
    (let [submitted (reify sut/TurnObserver
                      (on-turn-started [_ _])
                      (on-turn-ended [_ _ _])
                      (on-turn-died [_ _ _]))]
      (should= [submitted] (sut/for-turn [submitted]))
      (should= [] (sut/for-turn nil))))

  (it "clears ambient observers so later examples do not inherit them"
    (let [obs (reify sut/TurnObserver
                (on-turn-started [_ _])
                (on-turn-ended [_ _ _])
                (on-turn-died [_ _ _]))]
      (sut/attach! obs)
      (sut/clear-ambient!)
      (should= [] (sut/ambient))
      (should= [] (sut/for-turn nil))))
  )

(describe "lookout observer"

  (it "prints turn started then turn ended (ok) around a successful turn"
    (let [out (with-out-str
                (let [obs (sut/lookout)]
                  (sut/on-turn-started obs {:session-key "crows-nest"})
                  (print "Land ho ahead")
                  (sut/on-turn-ended obs {:session-key "crows-nest"} :ok)))]
      (should (re-find #"(?s)turn started.*Land ho ahead.*turn ended \(ok\)" out))))

  (it "prints turn ended (error ...) on a failed outcome"
    (let [out (with-out-str
                (let [obs (sut/lookout)]
                  (sut/on-turn-started obs {:session-key "crows-nest"})
                  (sut/on-turn-ended obs {:session-key "crows-nest"}
                                     {:kind :error :reason "fog rolled in"})))]
      (should (str/includes? out "turn started"))
      (should (re-find #"turn ended \(error[^)]*\)" out)))))
