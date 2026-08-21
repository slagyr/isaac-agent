(ns isaac.episodes.lifecycle-spec
  (:require
    [isaac.episodes.lifecycle :as sut]
    [isaac.episodes.store :as store]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.provider :as llm-provider]
    [isaac.nexus :as nexus]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as session-store]
    [isaac.tool.memory :as memory]
    [speclj.core :refer :all]))

(describe "isaac.episodes.lifecycle"

  (with mem (fs/mem-fs))
  (with root "/isaac-root")
  (with ss (memory-store/create-store @root))

  (before
    (grover/install-test-fixture!)
    (grover/reset-queue!)
    (fs/mkdirs @mem @root)
    (session-store/register-store! @ss))

  (around [example]
    (nexus/-with-nested-nexus {:fs @mem :sessions {:store @ss}}
      (example)))

  (it "opens an episode with :thread, :status :open, and a backing session named by the episode id"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "ab12")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (let [ep (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                     :thread "reef-chat" :session-store @ss
                                     :cwd "/tmp" :origin {:kind :cli}})]
          (should= :open (:status ep))
          (should= "reef-chat" (:thread ep))
          (should= "cordelia" (:crew ep))
          (should= "2026-03-01-1000-ab12" (:id ep))
          (should-be-nil (:parent-episode ep))
          (should= "2026-03-01-1000-ab12" (:id (session-store/get-session @ss (:id ep))))))))

  (it "opens a successor with :parent-episode"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "cd34")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T11:45:00Z")]
        (let [ep (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                     :thread "reef-chat" :session-store @ss
                                     :parent-episode "2026-03-01-1000-ab12"})]
          (should= "2026-03-01-1000-ab12" (:parent-episode ep))
          (should= "reef-chat" (:thread ep))))))

  (it "seeds a successor transcript with a compaction summary as the first entry"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "ef56")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:30:00Z")]
        (let [ep (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                     :thread "reef-chat" :session-store @ss
                                     :seed-compaction {:summary "Summary so far"}})
              transcript (session-store/get-transcript @ss (:id ep))
              entries (vec (remove #(= "session" (:type %)) transcript))]
          (should= "compaction" (:type (first entries)))
          (should= "Summary so far" (:summary (first entries)))))))

  (it "closes an episode via migrate/seal and writes :closed with scenes"
    (let [session (session-store/open-session! @ss "live-ep" {:crew "cordelia" :cwd @root})
          _ (session-store/append-message! @ss (:id session) {:role "user" :content "Chart the reef passage."})
          _ (session-store/append-message! @ss (:id session) {:role "assistant" :content "Charted, keep west."})
          _ (store/write-episode! @mem @root {:id (:id session) :crew "cordelia" :status :open
                                              :thread "reef-chat" :scene-ids []} [])
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
          _ (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])
          result (sut/close-episode! {:fs @mem :root @root :crew "cordelia"
                                      :episode-id (:id session)
                                      :session-store @ss
                                      :provider provider :model "gist"})
          ep (store/read-episode @mem @root "cordelia" (:id session))
          scenes (store/list-scenes @mem @root "cordelia" (:id session))]
      (should-not= :error (:status result))
      (should= :closed (:status ep))
      (should= "reef-chat" (:thread ep))
      (should= 1 (count scenes))
      (should= "Reef charting" (:gist (first scenes)))))

  (it "warm? is true when last message is inside the TTL"
    (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:10:00Z")]
      (should (sut/warm? [{:type "message" :timestamp "2026-03-01T10:00:00"}] 60))
      (should-not (sut/warm? [{:type "message" :timestamp "2026-03-01T09:00:00"}] 60))
      (should-not (sut/warm? [] 60))))

  (it "resolves an absent thread to a newly opened episode"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "gh78")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (let [resolved (sut/resolve-thread! {:fs @mem :root @root :crew "cordelia"
                                             :thread "reef-chat" :session-store @ss
                                             :cfg {:episodes {:ttl-minutes 60}}
                                             :cwd "/tmp" :origin {:kind :cli}})]
          (should= "2026-03-01-1000-gh78" (:session-key resolved))
          (should= :opened (:action resolved))
          (should= "reef-chat" (get-in resolved [:episode :thread]))))))

  (it "resolves a warm open episode to its backing session"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "ij90")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (let [opened (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                         :thread "reef-chat" :session-store @ss})
              _ (session-store/append-message! @ss (:id opened) {:role "user" :content "Chart"})
              _ (session-store/append-message! @ss (:id opened) {:role "assistant" :content "Aye"})]
          (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:10:00Z")]
            (let [resolved (sut/resolve-thread! {:fs @mem :root @root :crew "cordelia"
                                                 :thread "reef-chat" :session-store @ss
                                                 :cfg {:episodes {:ttl-minutes 60}}})]
              (should= (:id opened) (:session-key resolved))
              (should= :warm (:action resolved))))))))

  (it "cold-resolves by closing the open episode and opening a successor with :parent-episode"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "kl12")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (let [opened (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                         :thread "reef-chat" :session-store @ss})
              _ (session-store/append-message! @ss (:id opened) {:role "user" :content "Chart the reef passage."})
              _ (session-store/append-message! @ss (:id opened) {:role "assistant" :content "Charted, keep west."})
              provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
              _ (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])]
          (binding [memory/*now* (java.time.Instant/parse "2026-03-01T11:45:00Z")]
            (let [resolved (sut/resolve-thread! {:fs @mem :root @root :crew "cordelia"
                                                 :thread "reef-chat" :session-store @ss
                                                 :cfg {:episodes {:ttl-minutes 60 :gist-model :gist}}
                                                 :provider provider :model "gist"})]
              (should= :chained (:action resolved))
              (should= (:id opened) (get-in resolved [:episode :parent-episode]))
              (should= :open (get-in resolved [:episode :status]))
              (should= :closed (:status (store/read-episode @mem @root "cordelia" (:id opened))))))))))

  (it "compact-close seeds the successor transcript with the summary"
    (with-redefs [isaac.episodes.ids/chaos-suffix (constantly "mn34")]
      (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:00:00Z")]
        (let [opened (sut/open-episode! {:fs @mem :root @root :crew "cordelia"
                                         :thread "reef-chat" :session-store @ss})
              _ (session-store/append-message! @ss (:id opened) {:role "user" :content "Please summarize the logging work."})
              _ (session-store/append-message! @ss (:id opened) {:role "assistant" :content "We discussed sinks and the tool loop."})
              provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})
              _ (grover/enqueue! [{:type "text" :content "1-2: Logging retrospective"}])]
          (binding [memory/*now* (java.time.Instant/parse "2026-03-01T10:30:00Z")]
            (let [result (sut/compact-close! {:fs @mem :root @root :crew "cordelia"
                                              :thread "reef-chat" :session-store @ss
                                              :summary "Summary so far"
                                              :provider provider :model "gist"})
                  successor (:episode result)
                  entries (->> (session-store/get-transcript @ss (:id successor))
                               (remove #(= "session" (:type %)))
                               vec)]
              (should= :chained (:action result))
              (should= (:id opened) (:parent-episode successor))
              (should= "compaction" (:type (first entries)))
              (should= "Summary so far" (:summary (first entries)))
              (should= :closed (:status (store/read-episode @mem @root "cordelia" (:id opened))))))))))
  )
