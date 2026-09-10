(ns isaac.session.policy.episodes-spec
  (:require
    [isaac.episodes.store :as episode-store]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.provider :as llm-provider]
    [isaac.nexus :as nexus]
    [isaac.recall.inject :as recall-inject]
    [isaac.session.policy :as policy]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as session-store]
    [speclj.core :refer :all]))

(describe "isaac.session.policy.episodes"

  (with mem (fs/mem-fs))
  (with root "/tmp-episodes-policy")
  (with ss (memory-store/create-store @root))
  (with pol (policy/create :episodes @ss))

  (around [example]
    (nexus/-with-nested-nexus {:fs            @mem
                               :sessions      {:store @ss}
                               :root          @root
                               :config        (atom {:embedding {:source :provider :provider "grover" :model "mini-embed"}})}
      (example)))

  (before
    (grover/install-test-fixture!)
    (grover/reset-queue!)
    (fs/mkdirs @mem @root)
    (session-store/register-store! @ss))

  (it "injects recall on the first user append of a newly opened session"
    (let [called (atom nil)]
      (with-redefs [recall-inject/inject-on-open! (fn [opts] (reset! called opts))]
        (policy/open-session! @pol "harbor-log" {:crew "cordelia" :cwd @root})
        (policy/append-message! @pol "harbor-log" {:role "user" :content "Which way through the reef passage?"})
        (should= :opened (:action @called))
        (should= "Which way through the reef passage?" (:query @called))
        (should= "cordelia" (:crew @called)))))

  (it "does not inject recall on a warm second user append"
    (let [calls (atom [])]
      (with-redefs [recall-inject/inject-on-open! (fn [opts] (swap! calls conj opts))]
        (policy/open-session! @pol "harbor-log" {:crew "cordelia" :cwd @root})
        (policy/append-message! @pol "harbor-log" {:role "user" :content "Chart the reef passage"})
        (policy/append-message! @pol "harbor-log" {:role "assistant" :content "Charted, keep west"})
        (reset! calls [])
        (policy/append-message! @pol "harbor-log" {:role "user" :content "Mark the buoys"})
        (should= [] @calls))))

  (it "opens a successor container on the same session-id after compaction"
    (let [cfg      {:episodes {:gist-model :gist}}
          provider (llm-provider/make-provider "grover" {:api "grover" :auth "none"})]
      (grover/enqueue! [{:type "text" :content "1-2: Reef charting"}])
      (policy/open-session! @pol "reef-chat" {:crew "cordelia" :cwd @root})
      (policy/append-message! @pol "reef-chat" {:role "user" :content "Chart the reef passage"})
      (policy/append-message! @pol "reef-chat" {:role "assistant" :content "Charted, keep west"})
      (with-redefs [isaac.config.loader/snapshot (fn [_reason] cfg)
                    isaac.config.resolve/resolve-crew-context
                    (fn [_cfg _crew _opts] {:provider provider :model "gist"})]
        (let [spliced (policy/splice-compaction! @pol "reef-chat" {:summary "Summary so far"})
              eps     (->> (episode-store/list-episodes @mem @root "cordelia")
                           vec)
              closed  (first (filter #(= :closed (:status %)) eps))
              open    (first (filter #(= :open (:status %)) eps))]
          (should= 2 (count eps))
          (should-not-be-nil closed)
          (should-not-be-nil open)
          (should= "reef-chat" (:session-id closed))
          (should= "reef-chat" (:session-id open))
          (should= (:id closed) (:parent-episode open))
          (should= (:id open) (:successor-container spliced))))))
  )
