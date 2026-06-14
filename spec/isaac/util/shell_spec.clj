(ns isaac.util.shell-spec
  (:require
    [isaac.util.shell :as sut]
    [speclj.core :refer :all]))

(describe "cmd-available?"

  (it "returns true when the command exists on PATH"
    (should (sut/cmd-available? "ls")))

  (it "returns false when the command does not exist on PATH"
    (should-not (sut/cmd-available? "nonexistent-command-xyzzy-12345")))

  )
