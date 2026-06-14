(ns isaac.config.check-contributions-spec
  (:require
    [isaac.config.check-contributions :as sut]
    [speclj.core :refer :all]))

(describe "config check-contributions"

  (it "declares only the checks the config schema cannot express (tools, slash-commands, providers, comms all validate through the config berth)"
    (should-not (contains? sut/server :tools))
    (should-not (contains? sut/server :slash-commands))
    (should-not (contains? sut/server :provider-types))
    (should-not (contains? sut/server :comms))
    (should (contains? sut/server :resolved-providers))
    (should (contains? sut/server :manifest-refs))
    (should (contains? sut/server :comm-reserved-schema))))