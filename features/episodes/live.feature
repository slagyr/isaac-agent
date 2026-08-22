Feature: Episodes — live (router + lifecycle)
  Crews with :conversation :episodes get episode-managed conversations:
  the inbound --session name is the THREAD handle; the router maps it to
  the current open episode, whose backing session is named by the episode
  id (episodes as managed sessions — isaac-51xy decision 26). Warm
  prompts append (no recall, no reseal); cold prompts (past :episodes
  {:ttl-minutes 60}) close-and-chain with :parent-episode lineage;
  compaction closes the episode and seeds the successor's transcript
  with its summary. Sealing at close reuses the migration segmentation
  pipeline. Sessions are untouched for crews without the switch.

  Background:
    Given default Grover setup

  # ----- Open -----

    Scenario: first prompt on an episode crew opens an episode
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    Then the stdout contains "Charted, keep west"
    And the exit code is 0
    And an episode exists for crew "cordelia" matching:
      | key    | value                          |
      | id     | #"\d{4}-\d{2}-\d{2}-\d{4}-\w+" |
      | status | open                           |
      | thread | reef-chat                      |
    And the following sessions match:
      | id                             |
      | #"\d{4}-\d{2}-\d{2}-\d{4}-\w+" |
    And that episode's backing session has transcript matching:
      | type    | message.role | message.content        |
      | message | user         | Chart the reef passage |
      | message | assistant    | Charted, keep west     |

  # ----- Warm append -----

    Scenario: warm prompts append to the open episode
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
      | text | Buoys marked       | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    Given the current time is "2026-03-01T10:10:00"
    When isaac is run with "prompt -m 'Mark the buoys' --session reef-chat --crew cordelia"
    Then the exit code is 0
    And crew "cordelia" has 1 episode
    And an episode exists for crew "cordelia" matching:
      | key    | value     |
      | status | open      |
      | thread | reef-chat |
    And that episode's backing session has transcript matching:
      | type    | message.role | message.content        |
      | message | user         | Chart the reef passage |
      | message | assistant    | Charted, keep west     |
      | message | user         | Mark the buoys         |
      | message | assistant    | Buoys marked           |

  # ----- Cold continuation -----

    Scenario: cold prompts close the episode and chain a successor
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
      | text | 1-2: Reef charting | gist  |
      | text | Watches dogged     | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    Given the current time is "2026-03-01T11:45:00"
    When isaac is run with "prompt -m 'Set the watch rotation' --session reef-chat --crew cordelia"
    Then the exit code is 0
    And crew "cordelia" has 2 episodes
    And an episode exists for crew "cordelia" matching:
      | key            | value                          |
      | status         | open                           |
      | thread         | reef-chat                      |
      | parent-episode | #"\d{4}-\d{2}-\d{2}-\d{4}-\w+" |
    And the episodes for crew "cordelia" on thread "reef-chat" chain by lineage
    And that episode's backing session has transcript matching:
      | type    | message.role | message.content        |
      | message | user         | Set the watch rotation |
      | message | assistant    | Watches dogged         |

  # ----- Seal at close -----

    Scenario: closing seals the episode's transcript into scenes
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                                        | model |
      | text | Charted, keep west                             | echo  |
      | text | Buoys marked with the tool                     | echo  |
      | text | 1-2: Reef passage charted\n3-4: ~ Buoy tooling | gist  |
      | text | Watches dogged                                 | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    Given the current time is "2026-03-01T10:10:00"
    When isaac is run with "prompt -m 'Mark the buoys' --session reef-chat --crew cordelia"
    Given the current time is "2026-03-01T11:55:00"
    When isaac is run with "prompt -m 'Set the watch rotation' --session reef-chat --crew cordelia"
    Then the exit code is 0
    And an episode exists for crew "cordelia" matching:
      | key    | value  |
      | status | closed |
    And that episode has scenes matching:
      | gist                 | text                | routine |
      | Reef passage charted | #"(?s)keep west"    |         |
      | Buoy tooling         | #"(?s)Buoys marked" | true    |

  # ----- Compaction closes -----

    Scenario: compaction closes the episode and seeds the successor
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the isaac EDN file "config/models/echo.edn" exists with:
      | path           | value  |
      | model          | echo   |
      | provider       | grover |
      | context-window | 200    |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And crew "cordelia" has an open episode on thread "reef-chat" with:
      | compaction.head | 0.1 |
    And that episode's backing session has transcript:
      | type    | message.role | message.content                                                              |
      | message | user         | Please summarize the work we did on the logging subsystem and the tool loop  |
      | message | assistant    | We discussed logging output sinks, the compaction trigger, and tool dispatch |
      | message | user         | And what about the retry behavior we changed in the dispatcher last week     |
      | message | assistant    | We made the dispatcher retry idempotent and added backoff between attempts   |
    And the following model responses are queued:
      | type | content                                   | model |
      | text | Summary so far                            | echo  |
      | text | 1-4: Logging and dispatcher retrospective | gist  |
      | text | here is the answer                        | echo  |
    When isaac is run with "prompt -m 'next' --session reef-chat --crew cordelia"
    Then the stdout contains "here is the answer"
    And crew "cordelia" has 2 episodes
    And the episodes for crew "cordelia" on thread "reef-chat" chain by lineage
    And an episode exists for crew "cordelia" matching:
      | key    | value |
      | status | open  |
    And that episode's backing session has transcript matching:
      | type       | summary        | message.role | message.content    |
      | compaction | Summary so far |              |                    |
      | message    |                | user         | next               |
      | message    |                | assistant    | here is the answer |

  # ----- Explicit close -----

    Scenario: explicit close seals now; the next prompt chains
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
      | text | 1-2: Reef charting | gist  |
      | text | Fresh start        | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    When isaac is run with "episodes close --crew cordelia"
    Then the stdout contains "closed 1 episode"
    And the exit code is 0
    And an episode exists for crew "cordelia" matching:
      | key    | value  |
      | status | closed |
    And that episode has scenes matching:
      | gist          | text             |
      | Reef charting | #"(?s)keep west" |
    Given the current time is "2026-03-01T10:05:00"
    When isaac is run with "prompt -m 'Begin again' --session reef-chat --crew cordelia"
    Then crew "cordelia" has 2 episodes
    And the episodes for crew "cordelia" on thread "reef-chat" chain by lineage

  # ----- Non-episode crews -----

    Scenario: crews without the switch keep plain sessions
    Given the following model responses are queued:
      | type | content | model |
      | text | Aye     | echo  |
    When isaac is run with "prompt -m 'Status?' --session plain-chat"
    Then the stdout contains "Aye"
    And the exit code is 0
    And the following sessions match:
      | id         |
      | plain-chat |
    And crew "main" has 0 episodes

  # ----- Operator visibility -----

    Scenario: episodes list shows the crew's chain
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
      | text | 1-2: Reef charting | gist  |
      | text | Watches dogged     | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    Given the current time is "2026-03-01T11:45:00"
    When isaac is run with "prompt -m 'Set the watch rotation' --session reef-chat --crew cordelia"
    When isaac is run with "episodes list --crew cordelia"
    Then the stdout matches:
      | pattern                                                     |
      | \d{4}-\d{2}-\d{2}-\d{4}-\w+\s+closed\s+reef-chat\s+1 scene |
      | \d{4}-\d{2}-\d{2}-\d{4}-\w+\s+open\s+reef-chat\s+0 scenes  |
    And the exit code is 0

  # ----- Recall at open (isaac-h5dk) -----

  @wip
    Scenario: recall-at-open injects matched memory into the opening prompt
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                      | text                                    |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir suits roast pheasant |
    When isaac is run with "episodes index --crew cordelia"
    Given the following model responses are queued:
      | type | content              | model |
      | text | Pinot noir, as ever. | echo  |
    When isaac is run with "prompt -m 'What wine pairs with pheasant?' --session supper-chat --crew cordelia"
    Then the stdout contains "Pinot noir, as ever."
    And the exit code is 0
    And that episode has recalled scenes:
      | scene-id             | origin-episode       |
      | 2026-03-01-1000-s1x1 | 2026-03-01-1000-ab12 |
    And the last LLM request matches:
      | key      | value                                                                  |
      | messages | #"(?s)Recalled from earlier conversations.*recall__scene"              |
      | messages | #"(?s)\[2026-03-01-1000-s1x1 · 2026-03-01\] Wine pairing for pheasant" |
      | messages | #"(?s)pinot noir suits roast pheasant.*What wine pairs with pheasant"  |

  @wip
    Scenario: below-floor opens inject nothing
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}
       :recall {:floor-cos 0.999}}
      """
    And crew "cordelia" has a closed episode "2026-03-01-1000-ab12" with scenes:
      | id                   | started-at          | ended-at            | gist                      | text                                    |
      | 2026-03-01-1000-s1x1 | 2026-03-01T10:00:00 | 2026-03-01T10:05:00 | Wine pairing for pheasant | a light pinot noir suits roast pheasant |
    When isaac is run with "episodes index --crew cordelia"
    Given the following model responses are queued:
      | type | content        | model |
      | text | Nothing known. | echo  |
    When isaac is run with "prompt -m 'grumble mumble' --session other-chat --crew cordelia"
    Then the stdout contains "Nothing known."
    And the exit code is 0
    And that episode has no recalled scenes
    And the last LLM request does not mention recall

  # ----- Lineage seed (isaac-h5dk) -----

  @wip
    Scenario: cold continuation seeds parent gists by lineage, without duplication
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}
       :episodes {:gist-model :gist}}
      """
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content                   | model |
      | text | Marked; keep to leeward.  | echo  |
      | text | 1-2: Reef passage charted | gist  |
      | text | Still to leeward.         | echo  |
    When isaac is run with "prompt -m 'Chart the reef passage' --session reef-chat --crew cordelia"
    Given the current time is "2026-03-01T11:45:00"
    When isaac is run with "prompt -m 'Back to the reef passage' --session reef-chat --crew cordelia"
    Then the stdout contains "Still to leeward."
    And the exit code is 0
    And the episodes for crew "cordelia" on thread "reef-chat" chain by lineage
    And that episode has recalled scenes:
      | scene-id                       | origin-episode                 |
      | #"\d{4}-\d{2}-\d{2}-\d{4}-\w+" | #"\d{4}-\d{2}-\d{2}-\d{4}-\w+" |
    And the last LLM request matches:
      | key      | value                                                 |
      | messages | #"(?s)Previously in this conversation.*recall__scene" |
      | messages | #"(?s)\[\S+ · 2026-03-01\] Reef passage charted"      |
    And the last LLM request mentions "Reef passage charted" exactly 1 time

  # ----- Index at close (isaac-h5dk) -----

  @wip
    Scenario: closing indexes the sealed scenes immediately
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}
       :episodes {:gist-model :gist}}
      """
    And the following model responses are queued:
      | type | content                        | model |
      | text | A light pinot noir.            | echo  |
      | text | 1-2: Wine pairing for pheasant | gist  |
    When isaac is run with "prompt -m 'What wine pairs with pheasant?' --session supper-chat --crew cordelia"
    When isaac is run with "episodes close --crew cordelia"
    Then the stdout contains "indexed 2 rows"
    And the exit code is 0
    When isaac is run with "recall pheasant --crew cordelia -n 1"
    Then the stdout matches:
      | pattern                                     |
      | 1\. \S+\s+.*lex 1\.0\d*.*terms \[pheasant\] |
    And the exit code is 0

  # ----- Embedding optional / catch-up (isaac-h5dk) -----

  @wip
    Scenario: episodes work without embedding; indexing catches up when it arrives
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the isaac EDN file "config/models/gist.edn" exists with:
      | path     | value  |
      | model    | gist   |
      | provider | grover |
    And config file "isaac.edn" containing:
      """
      {:episodes {:gist-model :gist}}
      """
    And the following model responses are queued:
      | type | content                        | model |
      | text | A light pinot noir.            | echo  |
      | text | 1-2: Wine pairing for pheasant | gist  |
    When isaac is run with "prompt -m 'What wine pairs with pheasant?' --session supper-chat --crew cordelia"
    Then the exit code is 0
    When isaac is run with "episodes close --crew cordelia"
    Then the stdout contains "closed 1 episode"
    And the stdout does not contain "indexed"
    And no index exists for crew "cordelia"
    Given config file "isaac.edn" containing:
      """
      {:embedding {:source :provider :provider "grover" :model "mini-embed"}
       :episodes {:gist-model :gist}}
      """
    When isaac is run with "episodes index --crew cordelia"
    Then the stdout contains "2 new rows"
    And the exit code is 0
