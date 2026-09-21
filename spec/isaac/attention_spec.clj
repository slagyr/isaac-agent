(ns isaac.attention-spec
  (:require
    [clojure.string :as str]
    [isaac.attention :as sut]
    [isaac.comm.delivery.queue :as queue]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.fs :as fs]
    [isaac.tool.memory :as memory]
    [speclj.core :refer :all]))

(def notify-cfg {:attention {:notify {:comm :discord :target "boiler-room"}}})

(def hour-ms (* 60 60 1000))

(defn- broken [overrides]
  (merge {:provider "chatgpt"
          :model    "snuffy-codex"
          :session  "trash-can"
          :message  "The 'snuffy-codex' model is not supported on this account"}
         overrides))

(describe "attention"
  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (before (sut/clear-throttle!))

  (it "enqueues conversation-blocked attention when notify is configured"
    (sut/maybe-notify-conversation-blocked!
     notify-cfg
     "longwave"
     {:reason :compaction-failed :total-tokens 99 :context-window 100})
    (let [pending (queue/list-pending)]
      (should= 1 (count pending))
      (should= :discord (:comm (first pending)))
      (should= "boiler-room" (:target (first pending)))
      (should (str/includes? (:content (first pending)) "Conversation blocked"))
      (should (str/includes? (:content (first pending)) "longwave"))
      (should-not (str/includes? (:content (first pending)) "Compaction disabled"))))

  (it "enqueues turn-failed attention when notify is configured"
    (sut/maybe-notify-turn-failed!
     notify-cfg
     "crashy"
     {:message "wire format mismatch"})
    (let [pending (queue/list-pending)]
      (should= 1 (count pending))
      (should= :discord (:comm (first pending)))
      (should= "boiler-room" (:target (first pending)))
      (should (str/includes? (:content (first pending)) "crashy"))
      (should (str/includes? (:content (first pending)) "wire format mismatch"))))

  (context "broken-provider attention"

    (it "posts provider, model, session and the provider message"
      (sut/maybe-notify-provider-broken! notify-cfg (broken {}) 0)
      (let [content (:content (first (queue/list-pending)))]
        (should= 1 (count (queue/list-pending)))
        (should (str/includes? content "chatgpt"))
        (should (str/includes? content "snuffy-codex"))
        (should (str/includes? content "trash-can"))
        (should (str/includes? content "not supported"))))

    (it "clips a giant provider dump so the notice stays short"
      (sut/maybe-notify-provider-broken!
        notify-cfg
        (broken {:message (apply str (repeat 8000 "x"))})
        0)
      (let [content (:content (first (queue/list-pending)))]
        (should (< (count content) 1100))
        (should (str/includes? content "chatgpt"))
        (should (str/includes? content "characters dropped"))))

    (it "names why it broke: the error keyword and status lead, the message tail-clips (isaac-9af8)"
      (sut/maybe-notify-provider-broken!
        notify-cfg
        (broken {:error  :llm-error
                 :status nil
                 :message (str "HEADMARK handshake tools inventory "
                               (apply str (repeat 120 "noise,"))
                               " error event: stream dropped mid-response TAILMARK")})
        0)
      (let [content (:content (first (queue/list-pending)))]
        (should (str/includes? content "error llm-error"))
        (should (str/includes? content "model snuffy-codex"))
        (should (str/includes? content "session trash-can"))
        (should (str/includes? content "stream dropped mid-response"))
        (should (str/includes? content "characters dropped"))
        (should (str/includes? content "TAILMARK"))
        (should-not (str/includes? content "HEADMARK"))))

    (it "carries a structured status when one is present (isaac-9af8)"
      (sut/maybe-notify-provider-broken!
        notify-cfg
        (broken {:error :api-error :status 400})
        0)
      (let [content (:content (first (queue/list-pending)))]
        (should (str/includes? content "error api-error"))
        (should (str/includes? content "status 400"))
        (should (str/includes? content "not supported"))))

    (it "clips any caller's oversized content at enqueue and logs the full text (isaac-9af8)"
      (let [giant (apply str (repeat 3000 "z"))]
        (log/capture-logs
          (sut/maybe-notify-turn-failed! notify-cfg "trash-can" {:message giant}))
        (let [content (:content (first (queue/list-pending)))]
          (should (< (count content) 1100))
          (should (str/includes? content "Turn failed for session trash-can"))
          (should (str/includes? content "characters dropped")))
        (let [entry (last (filter #(= :attention/content-clipped (:event %)) @log/captured-logs))]
          (should-not-be-nil entry)
          (should (> (:content-chars entry) (count giant)))
          (should (str/includes? (str (:full-content entry)) (apply str (repeat 300 "z")))))))

    (it "bounds the provider message itself so the leaders always lead (isaac-9af8)"
      (sut/maybe-notify-provider-broken!
        notify-cfg
        (broken {:message (str (apply str (repeat 5000 "x")) " stream-tail")})
        0)
      (let [content (:content (first (queue/list-pending)))]
        (should (str/includes? content "Provider chatgpt is broken"))
        (should (str/includes? content "model snuffy-codex"))
        (should (str/includes? content "session trash-can"))
        (should (str/includes? content "stream-tail"))
        (should (str/includes? content "characters dropped"))
        (should-not (str/includes? content "truncated "))
        (should (< (count content) 600))))

    (it "leaves a short provider message untouched (isaac-9af8)"
      (sut/maybe-notify-provider-broken! notify-cfg (broken {}) 0)
      (let [content (:content (first (queue/list-pending)))]
        (should (str/includes? content "The 'snuffy-codex' model is not supported on this account"))
        (should-not (str/includes? content "dropped"))
        (should-not (str/includes? content "…"))))

    (it "keeps the full untruncated text in the log when the message is clipped (isaac-9af8)"
      (let [giant (str "full-log-copy " (apply str (repeat 3000 "y")))]
        (log/capture-logs
          (sut/maybe-notify-provider-broken! notify-cfg (broken {:message giant}) 0)
          (let [entry (last (filter #(= :attention/provider-message-clipped (:event %)) @log/captured-logs))]
            (should-not-be-nil entry)
            (should= (count giant) (:message-chars entry))
            (should (str/includes? (str (:full-message entry)) "full-log-copy ")))
          (should= 1 (count (queue/list-pending))))))

    (it "posts once per provider within an hour and logs the suppressed count"
      (log/capture-logs
        (sut/maybe-notify-provider-broken! notify-cfg (broken {}) 0)
        (sut/maybe-notify-provider-broken! notify-cfg (broken {:session "dumpster"}) 1)
        (sut/maybe-notify-provider-broken! notify-cfg (broken {}) 2)
        (should= 1 (count (queue/list-pending)))
        (let [entry (last (filter #(= :attention/provider-throttled (:event %)) @log/captured-logs))]
          (should= :info (:level entry))
          (should= "chatgpt" (:provider entry))
          (should= 2 (:suppressed entry)))))

    (it "reposts after an hour with the suppressed failure count"
      (sut/maybe-notify-provider-broken! notify-cfg (broken {}) 0)
      (sut/maybe-notify-provider-broken! notify-cfg (broken {}) (* 30 60 1000))
      (sut/maybe-notify-provider-broken! notify-cfg (broken {}) (+ hour-ms 30000))
      (let [pending (queue/list-pending)
            repost  (first (filter #(str/includes? (str (:content %)) "1 more failure") pending))]
        (should= 2 (count pending))
        (should-not-be-nil repost)
        (should (str/includes? (:content repost) "chatgpt"))
        (should (str/includes? (:content repost) "not supported"))))

    (it "posts independently for each provider"
      (sut/maybe-notify-provider-broken! notify-cfg (broken {}) 0)
      (sut/maybe-notify-provider-broken! notify-cfg (broken {:provider "anthropic"
                                                            :model    "tinfoil-sonnet"
                                                            :session  "paperclip"
                                                            :message  "not a valid model id"}) 1)
      (should= 2 (count (queue/list-pending))))

    (it "uses memory/*now* when now-ms is omitted"
      (binding [memory/*now* (java.time.Instant/ofEpochMilli 0)]
        (sut/maybe-notify-provider-broken! notify-cfg (broken {})))
      (binding [memory/*now* (java.time.Instant/ofEpochMilli (* 30 60 1000))]
        (sut/maybe-notify-provider-broken! notify-cfg (broken {})))
      (binding [memory/*now* (java.time.Instant/ofEpochMilli (+ hour-ms 30000))]
        (sut/maybe-notify-provider-broken! notify-cfg (broken {})))
      (should= 2 (count (queue/list-pending))))

    )
  )
