(ns isaac.agent.drive.weather-spec
  (:require
    [isaac.agent.drive.weather :as sut]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.nexus :as nexus]
    [isaac.agent.session.store.memory :as mem-store]
    [speclj.core :refer :all])
  (:import
    (java.time Instant)))

(describe "weather backoff"

  (it "uses the provider retry-after when it is longer than exponential backoff"
    (should= 60000 (sut/backoff-ms {:retry-after-ms 60000} 1)))

  (it "grows past a short provider retry-after on later suspends"
    (should= 60000 (sut/backoff-ms {:retry-after-ms 1000} 2)))

  (it "caps backoff at 30 minutes"
    (should= 1800000 (sut/backoff-ms {:retry-after-ms 3600000} 1)))

  (it "grows from 30 seconds when the provider omits retry-after"
    (should= 30000 (sut/backoff-ms {} 1))
    (should= 60000 (sut/backoff-ms {} 2)))

  (it "caps exponential backoff at 30 minutes"
    (should= 1800000 (sut/backoff-ms {} 12)))
  )

(describe "weather reason"

  (it "treats walls, auth, and stalls as weather"
    (should= :wall (sut/weather-reason {:unavailable? true :reason :wall}))
    (should= :auth (sut/weather-reason {:unavailable? true :reason :auth}))
    (should= :stall (sut/weather-reason {:unavailable? true :reason :stream-stalled}))
    (should= :mcp-unavailable (sut/weather-reason {:unavailable? true :reason :mcp-unavailable}))
    (should= :overloaded (sut/weather-reason {:unavailable? true :reason :overloaded}))
    (should= :connection-lost (sut/weather-reason {:unavailable? true :reason :connection-lost})))

  (it "does not treat blocked or exhausted conversations as weather"
    (should-be-nil (sut/weather-reason {:unavailable? true :reason :blocked}))
    (should-be-nil (sut/weather-reason {:unavailable? true :reason :context-exhausted}))
    (should-be-nil (sut/weather-reason {:error :llm-error})))
  )

(describe "stamping a weather marker"

  (around [example]
    (nexus/-with-nexus {:root "/test/isaac" :fs (fs/mem-fs)}
      (example)))

  (it "suspends a walled turn with retry-at and does not fabricate transcript content"
    (let [store  (mem-store/create-store)
          now    (Instant/parse "2026-04-21T10:00:00Z")
          result (sut/stamp-weather! store "trash-can"
                                     {:unavailable? true :reason :wall :retry-after-ms 60000}
                                     {:provider "chatgpt" :model "snuffy-codex" :now now})
          marker (isaac.agent.session.store.spi/get-turn-marker store "trash-can")]
      (should= "suspended" (:stopReason result))
      (should= true (:unavailable? result))
      (should= 60000 (:retry-after-ms result))
      (should= true (:suspended marker))
      (should= :wall (:reason marker))
      (should= "chatgpt" (get-in marker [:suspended-on :provider]))
      (should= "snuffy-codex" (get-in marker [:suspended-on :model]))
      (should= "2026-04-21T10:00:00Z" (:suspended-at marker))
      (should= "2026-04-21T10:01:00Z" (:retry-at marker))
      (should= 1 (:suspend-count marker))))

  (it "posts attention after a second MCP suspension when the attention window has elapsed"
    (let [store (mem-store/create-store)
          now (Instant/parse "2026-04-21T10:00:00Z")
          cfg {:turn {:suspended-attention-ms 0}}]
      (sut/stamp-weather! store "trash-can" {:unavailable? true :reason :mcp-unavailable}
                          {:cfg cfg :provider "claude-code" :model "sonnet" :now now})
      (should-not (:attention-posted (isaac.agent.session.store.spi/get-turn-marker store "trash-can")))
      (sut/stamp-weather! store "trash-can" {:unavailable? true :reason :mcp-unavailable}
                          {:cfg cfg :provider "claude-code" :model "sonnet" :now (.plusSeconds now 60)})
      (should= true (:attention-posted (isaac.agent.session.store.spi/get-turn-marker store "trash-can")))))

  (it "keeps the provider message so prompt stderr still surfaces auth rejection"
    (let [store  (mem-store/create-store)
          now    (Instant/parse "2026-04-21T10:00:00Z")
          result (sut/stamp-weather! store "trash-can"
                                     {:unavailable? true
                                      :reason       :auth
                                      :message      "OAuth2 token missing required scope: api:access"
                                      :retry-after-ms 300000}
                                     {:provider "chatgpt" :model "snuffy-codex" :now now})]
      (should= "suspended" (:stopReason result))
      (should= true (:unavailable? result))
      (should= :auth (:reason result))
      (should= "OAuth2 token missing required scope: api:access" (:message result))))
  )
