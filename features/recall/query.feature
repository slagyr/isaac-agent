Feature: Recall — query
  `isaac recall <query>` ranks a crew's indexed scenes against a query by
  blending weighted channels: cosine over text vectors, cosine over gist
  vectors, lexical term overlap, and recency (half-life decay, default 30
  days). Weights resolve defaults -> :recall config -> CLI flags, are
  "parts" multipliers, and the blended score is normalized by their sum.
  Ties break by scene-id ascending. Output shows the per-channel
  breakdown — the retrieval-quality checkpoint needs to see WHY a scene
  ranked. A stale index is a forcing step: recall hard-errors when zero
  rows match the configured embedding model.

  Background:
    Given an Isaac root at "isaac-state"
    And the isaac EDN file "config/providers/grover.edn" exists with:
      | path | value  |
      | api  | grover |
      | auth | none   |

  # ----- Help -----

    Scenario: recall is registered and has help
    When isaac is run with "help recall"
    Then the stdout matches:
      | pattern                                      |
      | Usage: isaac recall \[options\] <query>      |
      | Rank a crew's indexed scenes against a query |
      | --crew\s+                                    |
      | -n, --top\s+                                 |
      | --w-text\s+                                  |
      | --w-gist\s+                                  |
      | --w-lex\s+                                   |
      | --w-recency\s+                               |
      | --half-life\s+                               |
    And the exit code is 0

  # ----- Hybrid ranking -----

    Scenario: ranked hits with per-channel score breakdown
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist | text |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | wine | wine |
      | 2026-03-01-1006-s2x2 | 2026-03-01T10:06:00 | 2026-03-01T10:09:00 | race | dawn |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall wine --crew cordelia"
    Then the stdout matches:
      | pattern                                                                                          |
      | recall "wine" \(crew cordelia, model mini-embed, 2 scenes\)                                      |
      | 1\. 2026-03-01-1000-s1x1\s+score \d\.\d+\s+text 1\.0\d*\s+gist 1\.0\d*\s+lex 1\.0\d*\s+rec \d\.\d+ |
      | \s+wine                                                                                          |
      | 2\. 2026-03-01-1006-s2x2\s+score \d\.\d+\s+text \d\.\d+\s+gist \d\.\d+\s+lex 0\.0\d*\s+rec \d\.\d+ |
      | \s+race                                                                                          |
    And the exit code is 0

  # ----- Lexical channel -----

    Scenario: exact identifiers surface via the lexical channel with embeddings zeroed
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                       | text                                         |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Fixing the reef chart bean | resolved chart-7x2b by redrawing the passage |
      | 2026-03-01-1006-s2x2 | 2026-03-01T10:06:00 | 2026-03-01T10:09:00 | Wine pairing for pheasant  | a light pinot noir                           |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall chart-7x2b --crew cordelia --w-text 0 --w-gist 0"
    Then the stdout matches:
      | pattern                               |
      | 1\. 2026-03-01-1000-s1x1\s+.*lex 1\.0 |
      | 2\. 2026-03-01-1006-s2x2\s+.*lex 0\.0 |
    And the exit code is 0

  # ----- Weight precedence -----

    Scenario: weights resolve defaults, then :recall config, then CLI flags
    Given the current time is "2026-03-10T12:00:00"
    And config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-01-10-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist   | text   |
      | 2026-01-10-1000-s1x1 | 2026-01-10T11:00:00 | 2026-01-10T12:00:00 | harbor | harbor |
      | 2026-03-10-1100-s2x2 | 2026-03-10T10:00:00 | 2026-03-10T11:00:00 | supper | grouse |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall harbor --crew cordelia"
    Then the stdout matches:
      | pattern                  |
      | 1\. 2026-01-10-1000-s1x1 |
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}
       :recall {:weights {:recency 8}}}
      """
    When isaac is run with "recall harbor --crew cordelia"
    Then the stdout matches:
      | pattern                  |
      | 1\. 2026-03-10-1100-s2x2 |
    When isaac is run with "recall harbor --crew cordelia --w-recency 1"
    Then the stdout matches:
      | pattern                  |
      | 1\. 2026-01-10-1000-s1x1 |
    And the exit code is 0

  # ----- Recency -----

    Scenario: recency favors fresh scenes; --w-recency 0 neutralizes; --half-life reshapes
    Given the current time is "2026-03-10T12:00:00"
    And config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-01-10-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist | text |
      | 2026-01-10-1000-oldx | 2026-01-10T11:00:00 | 2026-01-10T12:00:00 | grog | grog |
      | 2026-03-10-1100-newx | 2026-03-10T10:00:00 | 2026-03-10T11:00:00 | grog | grog |
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall grog --crew cordelia"
    Then the stdout matches:
      | pattern                                |
      | 1\. 2026-03-10-1100-newx               |
      | 2\. 2026-01-10-1000-oldx\s+.*rec 0\.25 |
    When isaac is run with "recall grog --crew cordelia --w-recency 0"
    Then the stdout matches:
      | pattern                  |
      | 1\. 2026-01-10-1000-oldx |
      | 2\. 2026-03-10-1100-newx |
    When isaac is run with "recall grog --crew cordelia --half-life 60"
    Then the stdout matches:
      | pattern                               |
      | 1\. 2026-03-10-1100-newx              |
      | 2\. 2026-01-10-1000-oldx\s+.*rec 0\.5 |
    And the exit code is 0

  # ----- Missing index and model drift -----

    Scenario: missing index and model drift fail loudly; mixed rows warn
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist | text  |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | wine | pinot |
    When isaac is run with "recall wine --crew cordelia"
    Then the stderr contains "no index for crew cordelia"
    And the stderr contains "isaac episodes index"
    And the stderr does not contain "Exception"
    And the exit code is 1
    When isaac is run with "episodes index --crew cordelia"
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "maxi-embed"}}
      """
    When isaac is run with "recall wine --crew cordelia"
    Then the stderr contains "no rows for model maxi-embed"
    And the stderr contains "isaac episodes index"
    And the exit code is 1
    When isaac is run with "episodes index --crew cordelia"
    When isaac is run with "recall wine --crew cordelia"
    Then the stderr contains "2 stale rows (mini-embed)"
    And the stderr contains "--rebuild"
    And the stdout matches:
      | pattern                  |
      | 1\. 2026-03-01-1000-s1x1 |
    And the exit code is 0
