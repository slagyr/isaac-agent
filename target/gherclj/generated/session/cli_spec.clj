(ns session.cli-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.cli-steps :as cli-steps]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Sessions Command"

  (around [it]
    (binding [g/*state* (atom {})]
      (lifecycle/run-before-feature-hooks!)
      (try
        (it)
        (finally
          (lifecycle/run-after-feature-hooks!)))))

  (around [it]
    (binding [g/*state* (atom @g/*state*)]
      (lifecycle/run-before-scenario-hooks!)
      (try
        (it)
        (finally
          (lifecycle/run-after-scenario-hooks!)))))

  (it "sessions is registered and has help"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.foundation.cli-steps/isaac-run "help sessions")
    (isaac.foundation.cli-steps/stdout-contains "Usage: isaac sessions")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "sessions defaults to one flat table sorted alphabetically with a CREW column"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "total-tokens" "last-input-tokens" "updated-at"], :rows [["charlie-chat" "main" "778" "778" "2026-04-12T10:00:00"] ["bravo-chat" "ketch" "12000" "12000" "2026-04-11T10:00:00"] ["alpha-chat" "main" "5000" "5000" "2026-04-12T15:00:00"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["SESSION       AGE    USED  WINDOW  PCT  CREW"] ["alpha-chat    \\S+   5,000  32,768  \\d+%  main"] ["bravo-chat    \\S+  12,000  32,768  \\d+%  ketch"] ["charlie-chat  \\S+     778  32,768 \\s+\\d+%  main"]]})
    (isaac.foundation.cli-steps/stdout-does-not-contain "crew: main")
    (isaac.foundation.cli-steps/stdout-does-not-contain "crew: ketch")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "sessions --crew filters by current crew member"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "crew" "total-tokens" "updated-at"], :rows [["design-chat" "main" "5000" "2026-04-12T15:00:00"] ["pirate-chat" "ketch" "12000" "2026-04-11T10:00:00"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions --crew ketch")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["pirate-chat"]]})
    (isaac.foundation.cli-steps/stdout-does-not-contain "design-chat")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "sessions with no sessions prints a message"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.foundation.cli-steps/isaac-run "sessions")
    (isaac.foundation.cli-steps/stdout-contains "no sessions")
    (isaac.foundation.cli-steps/exit-code-is "0"))

  (it "sessions --crew with unknown crew member prints an error"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.foundation.cli-steps/isaac-run "sessions --crew nonexistent")
    (isaac.foundation.cli-steps/stderr-contains "unknown crew")
    (isaac.foundation.cli-steps/stderr-contains "nonexistent")
    (isaac.foundation.cli-steps/exit-code-is "1"))

  (it "sessions output has aligned columns with a header row"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "last-input-tokens" "updated-at"], :rows [["design-chat" "5000" "5000" "2026-04-12T15:00:00"] ["review-chat" "778" "778" "2026-04-12T10:00:00"] ["pirate-chat" "12000" "12000" "2026-04-11T10:00:00"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions --crew main")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["SESSION      AGE    USED  WINDOW  PCT"] ["design-chat  \\S+   5,000  32,768  \\d+%"] ["review-chat  \\S+     778  32,768   \\d+%"] ["pirate-chat  \\S+  12,000  32,768  \\d+%"]]}))

  (it "sessions show prints metadata for one session"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "last-input-tokens" "updated-at"], :rows [["design-chat" "5000" "5000" "2026-04-12T15:00:00"]]})
    (isaac.session.session-steps/session-has-transcript "design-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "Hello there"] ["message" "assistant" "Hi, how can I help?"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions show design-chat")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["Session Status"] ["Crew .* main"] ["Model .* echo \\(grover\\)"] ["Session .* design-chat"] ["Turns .* 2"] ["Context .* 5,000 / 32,768"]]})
    (isaac.foundation.cli-steps/stdout-does-not-contain "Hello there"))

  (it "sessions delete removes a session and its transcript"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["design-chat"]]})
    (isaac.session.session-steps/session-has-transcript "design-chat" {:headers ["type" "message.role" "message.content"], :rows [["message" "user" "hi"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions delete design-chat")
    (isaac.foundation.cli-steps/exit-code-is "0")
    (isaac.session.session-steps/session-does-not-exist "design-chat")
    (isaac.foundation.fs-steps/edn-isaac-file-does-not-exist "sessions/design-chat.jsonl"))

  (it "sessions output is colorized when --color always is set"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "updated-at"], :rows [["design-chat" "28000" "2026-04-12T15:00:00"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions --color always")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["\\x1b\\[1m.*SESSION"] ["design-chat.*\\x1b\\["]]}))

  (it "sessions --no-color suppresses ANSI escapes"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "updated-at"], :rows [["design-chat" "5000" "2026-04-12T15:00:00"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions --no-color")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["^[^\\x1b]*$"]]})
    (isaac.foundation.cli-steps/stdout-contains "design-chat"))

  (it "USED shows last-turn context size, not cumulative billing"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.foundation.fs-steps/isaac-edn-file-exists "config/crew/ketch.edn" {:headers ["path" "value"], :rows [["model" "grover"] ["soul" "You are a pirate."]]})
    (isaac.session.session-steps/sessions-exist {:headers ["name" "total-tokens" "last-input-tokens" "updated-at"], :rows [["chatty" "1000000" "5000" "2026-04-12T15:00:00"]]})
    (isaac.foundation.cli-steps/isaac-run "sessions")
    (isaac.foundation.cli-steps/stdout-matches {:headers ["pattern"], :rows [["chatty\\s+\\S+\\s+5,000\\s+32,768\\s+\\d+%"]]})
    (isaac.foundation.cli-steps/stdout-does-not-contain "1,000,000")))
