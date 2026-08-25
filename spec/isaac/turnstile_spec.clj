(ns isaac.turnstile-spec
  (:require
    [isaac.logger :as log]
    [isaac.turnstile :as sut]
    [speclj.core :refer [after before describe it should should-be-nil should-contain should=]]))

(defn- recording-turnstile
  "Test double: always :pass, records admit ctx and release invocations."
  ([events]
   (recording-turnstile events :pass))
  ([events decision]
   (reify sut/Turnstile
     (admit? [_ ctx]
       (swap! events conj [:admit ctx])
       decision)
     (release! [_ token]
       (swap! events conj [:release token])))))

(describe "turnstile registry"

  (before (sut/clear!)
          (sut/set-wake-hook! nil))
  (after (sut/clear!)
         (sut/set-wake-hook! nil))

  (it "resolves a registered turnstile by name"
    (let [gate (recording-turnstile (atom []))]
      (sut/register! :worksite (constantly gate))
      (should= gate (sut/resolve :worksite))))

  (it "returns nil for an unknown turnstile name"
    (should-be-nil (sut/resolve :foghorn)))

  (it "looks up a submitted name-only ref"
    (sut/register! :worksite (fn [_] :worksite-impl))
    (should= :worksite-impl (sut/resolve-ref :worksite)))

  (it "looks up a submitted [name params] ref and passes params to the factory"
    (let [seen (atom nil)]
      (sut/register! :worksite (fn [params] (reset! seen params) :chart-room))
      (should= :chart-room (sut/resolve-ref [:worksite "chart-room"]))
      (should= ["chart-room"] @seen)))

  (it "parses a CLI turnstile flag as a name-only keyword"
    (should= :worksite (sut/parse-ref "worksite")))

  (it "parses a CLI turnstile flag as name:params"
    (should= [:worksite "chart-room"] (sut/parse-ref "worksite:chart-room")))

  (it "parses a CLI turnstile flag with slash-separated params"
    (should= [:foreman "bean-work" "bn-7"] (sut/parse-ref "foreman:bean-work/bn-7")))

  (it "resolves a submitted worksite via the registry"
    (sut/register! :worksite (fn [_] (recording-turnstile (atom []))))
    (let [result (sut/resolve-submitted [:worksite])]
      (should-be-nil (:error result))
      (should= 1 (count (:turnstiles result)))))

  (it "refuses an unknown submitted turnstile name"
    (let [result (sut/resolve-submitted [[:foghorn "xyz"]])]
      (should= :unknown-turnstile (:error result))
      (should-contain "foghorn" (:message result))
      (should-contain "unknown turnstile" (:message result))))

  (it "is identity when no turnstiles are submitted"
    (let [result (sut/resolve-submitted nil)]
      (should-be-nil (:error result))
      (should= [] (:turnstiles result))))

  (it "registers a factory from a manifest contribution entry"
    (sut/register-entry! [:worksite {:factory 'isaac.turnstile/null-turnstile}])
    (should (some? (sut/resolve :worksite))))

  (it "parses a CLI turnstile flag as name:HH:mm-HH:mm window"
    (should= [:tide "22:00-06:00"] (sut/parse-ref "tide:22:00-06:00")))

  (it "resolves the builtin :tide factory via the registry"
    (let [result (sut/resolve-submitted [[:tide "22:00-06:00"]])]
      (should-be-nil (:error result))
      (should= 1 (count (:turnstiles result)))))
  )

(describe "null turnstile"

  (it "always admits with :pass"
    (should= :pass (sut/admit? (sut/null-turnstile) {:cwd "/tmp/chart-room"})))

  (it "release is a no-op"
    (should-be-nil (sut/release! (sut/null-turnstile) nil)))
  )

(describe "tide turnstile"

  (it "holds at 14:00 when the window is 22:00-06:00 (midnight-crossing)"
    (let [gate     (sut/tide ["22:00-06:00"])
          decision (sut/admit? gate {:now (java.time.Instant/parse "2026-03-01T14:00:00Z")})]
      (should= :hold (:status decision))
      (should-contain "tide" (:message decision))
      (should-contain "22:00-06:00" (:message decision))
      (should-contain "held" (:message decision))))

  (it "admits at 23:30 when the window is 22:00-06:00"
    (let [gate (sut/tide ["22:00-06:00"])]
      (should= :pass (sut/admit? gate {:now (java.time.Instant/parse "2026-03-01T23:30:00Z")}))))

  (it "admits at 03:00 when the window is 22:00-06:00 (after midnight)"
    (let [gate (sut/tide ["22:00-06:00"])]
      (should= :pass (sut/admit? gate {:now (java.time.Instant/parse "2026-03-01T03:00:00Z")}))))

  (it "holds at 07:00 when the window is 22:00-06:00"
    (let [gate (sut/tide ["22:00-06:00"])]
      (should= :hold (:status (sut/admit? gate {:now (java.time.Instant/parse "2026-03-01T07:00:00Z")})))))

  (it "admits at 14:00 when the window is 09:00-17:00 (same-day)"
    (let [gate (sut/tide ["09:00-17:00"])]
      (should= :pass (sut/admit? gate {:now (java.time.Instant/parse "2026-03-01T14:00:00Z")}))))

  (it "holds at 08:00 when the window is 09:00-17:00"
    (let [gate (sut/tide ["09:00-17:00"])]
      (should= :hold (:status (sut/admit? gate {:now (java.time.Instant/parse "2026-03-01T08:00:00Z")})))))

  (it "release is a no-op"
    (should-be-nil (sut/release! (sut/tide ["22:00-06:00"]) nil)))
  )

(describe "admission"

  (before (sut/clear!))
  (after (sut/clear!))

  (it "is identity when no turnstiles are submitted — today's run-now semantics"
    (let [result (sut/admit-all! [] {:cwd "/tmp/chart-room"})]
      (should-be-nil (:error result))
      (should= [] (:tokens result))))

  (it "collects a release token from a passing turnstile"
    (let [events (atom [])
          gate   (recording-turnstile events)
          result (sut/admit-all! [gate] {:cwd "/tmp/chart-room"})]
      (should-be-nil (:error result))
      (should= 1 (count (:tokens result)))
      (should= [[:admit {:cwd "/tmp/chart-room"}]] @events)
      (let [{:keys [turnstile token]} (first (:tokens result))]
        (should= gate turnstile)
        (should (string? (:id token))))))

  (it "refuses when a turnstile returns {:refuse reason}"
    (let [gate   (recording-turnstile (atom []) {:refuse :member-locked})
          result (sut/admit-all! [gate] {:cwd "/tmp/chart-room"})]
      (should= :refused (:error result))
      (should= :member-locked (:reason result))
      (should= [] (:tokens result))))

  (it "surfaces :hold as refusal until the queue bean lands"
    (let [gate   (recording-turnstile (atom []) :hold)
          result (sut/admit-all! [gate] {:cwd "/tmp/chart-room"})]
      (should= :refused (:error result))
      (should= :hold (:reason result))
      (should= [] (:tokens result))))

  (it "includes the turnstile name and window on a tide hold"
    (let [gate   (sut/tide ["22:00-06:00"])
          result (sut/admit-all! [gate] {:now (java.time.Instant/parse "2026-03-01T14:00:00Z")})]
      (should= :refused (:error result))
      (should= :hold (:reason result))
      (should-contain "tide" (:message result))
      (should-contain "22:00-06:00" (:message result))
      (should-contain "held" (:message result))))

  (it "releases already-acquired tokens when a later turnstile refuses"
    (let [events  (atom [])
          first*  (recording-turnstile events)
          second  (recording-turnstile events {:refuse :member-locked})
          result  (sut/admit-all! [first* second] {:cwd "/tmp/chart-room"})]
      (should= :refused (:error result))
      (should= :member-locked (:reason result))
      (should= [] (:tokens result))
      (should= [:admit :admit :release] (mapv first @events))))

  (it "invokes release tokens in reverse acquisition order"
    (let [order (atom [])
          a     (reify sut/Turnstile
                  (admit? [_ _] :pass)
                  (release! [_ _] (swap! order conj :a)))
          b     (reify sut/Turnstile
                  (admit? [_ _] :pass)
                  (release! [_ _] (swap! order conj :b)))
          {:keys [tokens]} (sut/admit-all! [a b] {})]
      (sut/release-all! tokens)
      (should= [:b :a] @order)))

  (it "nudges the wake hook after releasing tokens"
    (let [wakes (atom 0)
          gate  (recording-turnstile (atom []))
          {:keys [tokens]} (sut/admit-all! [gate] {})]
      (sut/set-wake-hook! (fn [] (swap! wakes inc)))
      (try
        (sut/release-all! tokens)
        (should= 1 @wakes)
        (finally
          (sut/set-wake-hook! nil)))))

  (it "isolates a throwing release so remaining tokens still fire"
    (let [second (atom false)
          boom   (reify sut/Turnstile
                   (admit? [_ _] :pass)
                   (release! [_ _] (throw (Exception. "berth exploded"))))
          ok     (reify sut/Turnstile
                   (admit? [_ _] :pass)
                   (release! [_ _] (reset! second true)))]
      (log/capture-logs
        (let [{:keys [tokens]} (sut/admit-all! [ok boom] {})]
          (sut/release-all! tokens)
          (let [entry (first (filter #(= :turnstile/release-failed (:event %)) @log/captured-logs))]
            (should= :warn (:level entry))
            (should= "berth exploded" (:error entry)))))
      (should @second)))
  )
