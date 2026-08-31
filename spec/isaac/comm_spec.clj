(ns isaac.comm-spec
  (:require
    [clojure.string :as str]
    [isaac.bridge.prompt-cli :as prompt-cli]
    [isaac.comm.memory :as memory-comm]
    [isaac.comm.null :as null-comm]
    [isaac.comm.protocol :as sut]
    [speclj.core :refer :all]))

(def ^:private protocol-methods
  [[:on-turn-start    (fn [ch] (sut/on-turn-start ch "s" "hi"))]
   [:on-turn-end      (fn [ch] (sut/on-turn-end ch "s" {:content "done"}))]
   [:on-cycle-start   (fn [ch] (sut/on-cycle-start ch "s" {:n 1 :model "echo"}))]
   [:on-cycle-end     (fn [ch] (sut/on-cycle-end ch "s" {:n 1} {:outcome :reply :text "ok" :tool-calls []}))]
   [:on-chatter       (fn [ch] (sut/on-chatter ch "s" {:n 1} "chunk"))]
   [:on-reckoning     (fn [ch] (sut/on-reckoning ch "s" {:n 1} "think"))]
   [:on-aside         (fn [ch] (sut/on-aside ch "s" {:n 1} "working"))]
   [:on-reply         (fn [ch] (sut/on-reply ch "s" "done"))]
   [:on-tool-call     (fn [ch] (sut/on-tool-call ch "s" {:id "tc" :name "grep" :arguments {}}))]
   [:on-tool-cancel   (fn [ch] (sut/on-tool-cancel ch "s" {:id "tc" :name "grep" :arguments {}}))]
   [:on-tool-result   (fn [ch] (sut/on-tool-result ch "s" {:id "tc" :name "grep" :arguments {}} "ok"))]
   [:on-tool-progress (fn [ch] (sut/on-tool-progress ch "s" {:id "tc" :name "grep"} "partial"))]
   [:on-bulletin      (fn [ch] (sut/on-bulletin ch "s" {:kind :compaction/start :total-tokens 95}))]
   [:send!            (fn [ch] (sut/send! ch {:content "hi"}))]])

(defn- invoke-all! [ch]
  (doseq [[_ f] protocol-methods]
    (f ch)))

(defn- silent-aside-and-reply? [ch]
  (let [out (with-out-str
              (sut/on-aside ch "s" {:n 1} "aside-should-not-print")
              (sut/on-reply ch "s" "reply-should-not-print"))]
    (not (or (str/includes? out "aside-should-not-print")
             (str/includes? out "reply-should-not-print")))))

(describe "Channel protocol"

  (it "defaults cover every turn-event method and omit send!"
    (should= #{:on-turn-start :on-turn-end :on-cycle-start :on-cycle-end
               :on-chatter :on-reckoning :on-aside :on-reply
               :on-tool-call :on-tool-cancel :on-tool-result :on-tool-progress
               :on-bulletin}
             (set (keys sut/defaults)))
    (should-not (contains? sut/defaults :send!)))

  (it "can dispatch all channel callbacks"
    (let [events (atom [])
          ch     (reify sut/Comm
                   (on-turn-start [_ session-key input]
                     (swap! events conj [:turn-start session-key input]))
                   (on-turn-end [_ session-key result]
                     (swap! events conj [:turn-end session-key result]))
                   (on-cycle-start [_ session-key cycle]
                     (swap! events conj [:cycle-start session-key cycle]))
                   (on-cycle-end [_ session-key cycle outcome]
                     (swap! events conj [:cycle-end session-key cycle outcome]))
                   (on-chatter [_ session-key cycle chunk]
                     (swap! events conj [:chatter session-key cycle chunk]))
                   (on-reckoning [_ session-key cycle chunk]
                     (swap! events conj [:reckoning session-key cycle chunk]))
                   (on-aside [_ session-key cycle text]
                     (swap! events conj [:aside session-key cycle text]))
                   (on-reply [_ session-key text]
                     (swap! events conj [:reply session-key text]))
                   (on-tool-call [_ session-key tool-call]
                     (swap! events conj [:tool-call session-key tool-call]))
                   (on-tool-cancel [_ session-key tool-call]
                     (swap! events conj [:tool-cancel session-key tool-call]))
                   (on-tool-result [_ session-key tool-call result]
                     (swap! events conj [:tool-result session-key tool-call result]))
                   (on-tool-progress [_ session-key tool-call chunk]
                     (swap! events conj [:tool-progress session-key tool-call chunk]))
                   (on-bulletin [_ session-key bulletin]
                     (swap! events conj [:bulletin session-key bulletin]))
                   (send! [_ record]
                     (swap! events conj [:send record])))]
      (invoke-all! ch)
      (should= 14 (count @events))))

  (it "built-in comm implementations dispatch every protocol method without AbstractMethodError"
    (let [channels [(memory-comm/channel (atom []))
                    null-comm/channel
                    (prompt-cli/->PromptComm (atom "") false)]]])
      (doseq [ch channels]
        (let [stderr (java.io.StringWriter.)]
          (binding [*err* stderr]
            (with-out-str
              (doseq [[_ f] protocol-methods]
                (should-not-throw (f ch)))))))))

  (it "in-tree comms take at most one of {on-chatter} / {on-aside, on-reply} (memory exempt)"
    (let [null   null-comm/channel
          prompt (prompt-cli/->PromptComm (atom "") false)]
      (doseq [ch [null prompt]]
        (should (silent-aside-and-reply? ch)))))
  )
