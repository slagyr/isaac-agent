(ns tool.memory-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.foundation.fs-steps :as fs-steps]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Crew memory tools"

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

  (it "memory_write appends content to today's note"
    (isaac.session.session-steps/default-grover-setup-in "isaac-state")
    (isaac.tool.tools-steps/current-time-is "2026-04-21T10:00:00Z")
    (isaac.tool.tools-steps/tool-called "memory_write" {:headers ["content" "Hieronymus hates artichokes."], :rows []})
    (isaac.tool.tools-steps/file-matches "crew/main/memory/2026-04-21.md" {:headers ["text"], :rows [["Hieronymus hates artichokes."]]}))

  (it "memory_write accepts an array of entries"
    (isaac.session.session-steps/default-grover-setup-in "isaac-state")
    (isaac.tool.tools-steps/current-time-is "2026-04-21T10:00:00Z")
    (isaac.tool.tools-steps/tool-called "memory_write" {:headers ["content" "[\"Orpheus the cat sneaks in during rainstorms.\" \"Grandma's sourdough lives on /Volumes/zane/recipes.\"]"], :rows []})
    (isaac.tool.tools-steps/file-matches "crew/main/memory/2026-04-21.md" {:headers ["text"], :rows [["Orpheus the cat sneaks in during rainstorms."] ["Grandma's sourdough lives on /Volumes/zane/recipes."]]}))

  (it "memory_write appends to existing content instead of overwriting"
    (isaac.session.session-steps/default-grover-setup-in "isaac-state")
    (isaac.tool.tools-steps/current-time-is "2026-04-21T10:00:00Z")
    (isaac.tool.tools-steps/tool-called "memory_write" {:headers ["content" "The vault door creaks when opened clockwise."], :rows []})
    (isaac.tool.tools-steps/tool-called "memory_write" {:headers ["content" "Hieronymus fell asleep under the stairs again."], :rows []})
    (isaac.tool.tools-steps/file-matches "crew/main/memory/2026-04-21.md" {:headers ["text"], :rows [["The vault door creaks when opened clockwise."] ["Hieronymus fell asleep under the stairs again."]]}))

  (it "memory_get reads a day's note within the range"
    (isaac.session.session-steps/default-grover-setup-in "isaac-state")
    (isaac.tool.tools-steps/current-time-is "2026-04-21T10:00:00Z")
    (isaac.foundation.fs-steps/file-at-with-content "crew/main/memory/2026-04-15.md" "Orpheus brought a dead mouse to the back door.")
    (isaac.tool.tools-steps/tool-called "memory_get" {:headers ["start_time" "2026-04-15"], :rows [["end_time" "2026-04-15"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["Orpheus brought a dead mouse to the back door."]]}))

  (it "memory_get returns notes from each day in an inclusive range"
    (isaac.session.session-steps/default-grover-setup-in "isaac-state")
    (isaac.tool.tools-steps/current-time-is "2026-04-21T10:00:00Z")
    (isaac.foundation.fs-steps/file-at-with-content "crew/main/memory/2026-04-14.md" "The moonflowers bloomed last night.")
    (isaac.foundation.fs-steps/file-at-with-content "crew/main/memory/2026-04-16.md" "Wind knocked over the hedgehog figurine.")
    (isaac.foundation.fs-steps/file-at-with-content "crew/main/memory/2026-04-19.md" "User found a geode in the attic.")
    (isaac.tool.tools-steps/tool-called "memory_get" {:headers ["start_time" "2026-04-14"], :rows [["end_time" "2026-04-16"]]})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["The moonflowers bloomed last night."] ["Wind knocked over the hedgehog figurine."]]})
    (isaac.tool.tools-steps/tool-result-not-contains "User found a geode in the attic."))

  (it "memory_search returns matching lines across all memory files"
    (isaac.session.session-steps/default-grover-setup-in "isaac-state")
    (isaac.tool.tools-steps/current-time-is "2026-04-21T10:00:00Z")
    (isaac.foundation.fs-steps/file-at-with-content "crew/main/memory/2026-04-15.md" "Orpheus brought a dead mouse to the back door.")
    (isaac.foundation.fs-steps/file-at-with-content "crew/main/memory/2026-04-19.md" "Orpheus sulked under the porch for most of the afternoon.")
    (isaac.foundation.fs-steps/file-at-with-content "crew/main/memory/2026-04-20.md" "The moonflowers bloomed last night.")
    (isaac.tool.tools-steps/tool-called "memory_search" {:headers ["query" "Orpheus"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-lines-match {:headers ["text"], :rows [["2026-04-15"] ["Orpheus brought a dead mouse"] ["2026-04-19"] ["Orpheus sulked"]]})
    (isaac.tool.tools-steps/tool-result-not-contains "moonflowers"))

  (it "memory_search is case-insensitive"
    (isaac.session.session-steps/default-grover-setup-in "isaac-state")
    (isaac.tool.tools-steps/current-time-is "2026-04-21T10:00:00Z")
    (isaac.foundation.fs-steps/file-at-with-content "crew/main/memory/2026-04-15.md" "Orpheus brought a dead mouse to the back door.")
    (isaac.tool.tools-steps/tool-called "memory_search" {:headers ["query" "orpheus"], :rows []})
    (isaac.tool.tools-steps/tool-result-not-error)
    (isaac.tool.tools-steps/tool-result-contains "Orpheus brought a dead mouse")))
