(ns isaac.agent.session.session-observer-spec
  (:require
    [isaac.agent.session.session-observer :as sut]
    [speclj.core :refer [describe it should should=]]))

(describe "session observer delivery"
  (it "preserves a session's event order off the turn path"
    (let [entered (promise) release (promise) seen (atom [])]
      (sut/register! :logbook (fn [_] (fn [event]
                                       (deliver entered true)
                                       @release
                                       (swap! seen conj (:event event)))))
      (sut/publish! "lantern-room" [:logbook] {:event :session-opened})
      (should= true (deref entered 1000 false))
      (let [done (promise)]
        (future (sut/publish! "lantern-room" [:logbook] {:event :turn-started}) (deliver done true))
        (should= true (deref done 1000 false)))
      (deliver release true)
      (sut/await! "lantern-room" :logbook)
      (should= [:session-opened :turn-started] @seen))))
