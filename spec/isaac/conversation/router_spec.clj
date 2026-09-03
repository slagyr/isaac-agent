(ns isaac.conversation.router-spec
  (:require
    [isaac.conversation.router :as sut]
    [speclj.core :refer :all]))

(describe "conversation router"
  (it "makes a thread the episode route input for an episodes crew"
    (should= "discord-C999"
             (:session-key
               (sut/route-conversation!
                 {:crew-cfg {:conversation :episodes}
                  :conversation {:kind :thread :id "discord-C999"}}))))

  (it "makes the same thread the chronicle route input for a chronicle crew"
    (should= "discord-C999"
             (:session-key
               (sut/route-conversation!
                 {:crew-cfg {:conversation :chronicles}
                  :conversation {:kind :thread :id "discord-C999"}})))))
