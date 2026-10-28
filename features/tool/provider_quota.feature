Feature: provider__quota — how much of each subscription is left
  provider__quota asks each configured provider how much of its plan is
  used. A meter, registered per provider type on the
  :isaac.agent/quota-meter berth, fetches the provider's own report and
  conforms it to one shape: a list of windows (a limit that resets) and a
  list of balances (credits or money that don't). Percent used is the one
  number every window carries; remaining is 100 minus it.

  Meters read vendor endpoints that are not public APIs, so a failing
  meter is a status, not an error: the provider reports :unknown with the
  reason, and the tool still answers. Providers with no meter report
  :unsupported. Reports are cached for two minutes per provider.

  JSON keys follow the tool convention (snake_case).

  Background:
    Given default Grover setup
    And the isaac EDN file "config/providers/chatgpt.edn" exists with:
      | path | value    |
      | type | :chatgpt |
    And the isaac EDN file "config/providers/grok.edn" exists with:
      | path | value |
      | type | :grok |
    And the isaac EDN file "config/providers/ollama.edn" exists with:
      | path | value   |
      | type | :ollama |
    And oauth logins exist:
      | provider | access-token | account-id    |
      | chatgpt  | at-ketch     | acct-marigold |
      | grok     | at-lookout   |               |
    And the current time is "2026-10-28T12:00:00Z"

  @wip
  Scenario: ChatGPT reports both windows and its credits
    Given the URL "https://chatgpt.com/backend-api/wham/usage" has body:
      """
      {"plan_type": "plus",
       "rate_limit": {"allowed": true, "limit_reached": false,
                      "primary_window":   {"used_percent": 42, "limit_window_seconds": 18000,
                                           "reset_after_seconds": 3600, "reset_at": 1793192400},
                      "secondary_window": {"used_percent": 17, "limit_window_seconds": 604800,
                                           "reset_after_seconds": 421200, "reset_at": 1793610000}},
       "credits": {"has_credits": true, "unlimited": false, "balance": "12"}}
      """
    When the tool "provider__quota" is called with:
      | provider | chatgpt |
    Then the tool result is not an error
    And the tool result JSON has:
      | path                                    | value                |
      | providers[0].provider                   | chatgpt              |
      | providers[0].status                     | ok                   |
      | providers[0].plan                       | plus                 |
      | providers[0].checked_at                 | 2026-10-28T12:00:00Z |
      | providers[0].windows[0].id              | primary              |
      | providers[0].windows[0].label           | 5-hour               |
      | providers[0].windows[0].used_percent    | 42.0                 |
      | providers[0].windows[0].resets_at       | 2026-10-28T13:00:00Z |
      | providers[0].windows[0].length_seconds  | 18000                |
      | providers[0].windows[0].limited         | false                |
      | providers[0].windows[1].id              | secondary            |
      | providers[0].windows[1].label           | 7-day                |
      | providers[0].windows[1].used_percent    | 17.0                 |
      | providers[0].windows[1].resets_at       | 2026-11-02T09:00:00Z |
      | providers[0].balances[0].id             | credits              |
      | providers[0].balances[0].amount         | 12.0                 |
      | providers[0].balances[0].unit           | credits              |
    And an outbound HTTP request to "https://chatgpt.com/backend-api/wham/usage" matches:
      | key                        | value         |
      | headers.Authorization      | Bearer at-ketch |
      | headers.chatgpt-account-id | acct-marigold |

  @wip
  Scenario: Grok reports its weekly period, each product, and its prepaid balance
    Given the URL "https://cli-chat-proxy.grok.com/v1/billing?format=credits" has body:
      """
      {"config": {"currentPeriod": {"type": "USAGE_PERIOD_TYPE_WEEKLY",
                                    "start": "2026-10-23T14:00:00+00:00",
                                    "end":   "2026-10-30T14:00:00+00:00"},
                  "creditUsagePercent": 12.5,
                  "productUsage": [{"product": "GrokBuild", "usagePercent": 8.0},
                                   {"product": "GrokChat"}],
                  "prepaidBalance": {"val": 250},
                  "onDemandCap": {"val": 0},
                  "onDemandUsed": {"val": 0}}}
      """
    When the tool "provider__quota" is called with:
      | provider | grok |
    Then the tool result is not an error
    And the tool result JSON has:
      | path                                   | value                |
      | providers[0].provider                  | grok                 |
      | providers[0].status                    | ok                   |
      | providers[0].windows[0].id             | weekly               |
      | providers[0].windows[0].label          | 7-day                |
      | providers[0].windows[0].used_percent   | 12.5                 |
      | providers[0].windows[0].starts_at      | 2026-10-23T14:00:00Z |
      | providers[0].windows[0].resets_at      | 2026-10-30T14:00:00Z |
      | providers[0].windows[0].scope          |                      |
      | providers[0].windows[1].used_percent   | 8.0                  |
      | providers[0].windows[1].scope.product  | GrokBuild            |
      | providers[0].windows[2].used_percent   | 0.0                  |
      | providers[0].windows[2].scope.product  | GrokChat             |
      | providers[0].balances[0].id            | prepaid              |
      | providers[0].balances[0].amount        | 250.0                |
      | providers[0].balances[0].unit          | credits              |
    And an outbound HTTP request to "https://cli-chat-proxy.grok.com/v1/billing?format=credits" matches:
      | key                   | value             |
      | headers.Authorization | Bearer at-lookout |

  @wip
  Scenario: A provider with no meter is unsupported
    When the tool "provider__quota" is called with:
      | provider | ollama |
    Then the tool result is not an error
    And the tool result JSON has:
      | path                  | value       |
      | providers[0].provider | ollama      |
      | providers[0].status   | unsupported |
      | providers[0].windows  | []          |

  @wip
  Scenario: A meter that fails reports unknown with the reason
    Given the URL "https://chatgpt.com/backend-api/wham/usage" responds with:
      | status | 500 |
    When the tool "provider__quota" is called with:
      | provider | chatgpt |
    Then the tool result is not an error
    And the tool result JSON has:
      | path                  | value     |
      | providers[0].provider | chatgpt   |
      | providers[0].status   | unknown   |
      | providers[0].error    | #"(?i).*500.*" |
      | providers[0].windows  | []        |

  @wip
  Scenario: Reports are cached for two minutes
    Given the URL "https://chatgpt.com/backend-api/wham/usage" has body:
      """
      {"rate_limit": {"primary_window": {"used_percent": 42, "limit_window_seconds": 18000, "reset_at": 1793192400}}}
      """
    And the tool "provider__quota" is called with:
      | provider | chatgpt |
    And the URL "https://chatgpt.com/backend-api/wham/usage" has body:
      """
      {"rate_limit": {"primary_window": {"used_percent": 55, "limit_window_seconds": 18000, "reset_at": 1793192400}}}
      """
    And the current time is "2026-10-28T12:01:00Z"
    When the tool "provider__quota" is called with:
      | provider | chatgpt |
    Then the tool result JSON has:
      | path                                 | value                |
      | providers[0].windows[0].used_percent | 42.0                 |
      | providers[0].checked_at              | 2026-10-28T12:00:00Z |
    Given the current time is "2026-10-28T12:03:00Z"
    When the tool "provider__quota" is called with:
      | provider | chatgpt |
    Then the tool result JSON has:
      | path                                 | value                |
      | providers[0].windows[0].used_percent | 55.0                 |
      | providers[0].checked_at              | 2026-10-28T12:03:00Z |

  @wip
  Scenario: isaac quota prints the same report for people
    Given the URL "https://chatgpt.com/backend-api/wham/usage" has body:
      """
      {"plan_type": "plus",
       "rate_limit": {"primary_window":   {"used_percent": 42, "limit_window_seconds": 18000, "reset_at": 1793192400},
                      "secondary_window": {"used_percent": 17, "limit_window_seconds": 604800, "reset_at": 1793610000}}}
      """
    When isaac is run with "quota --provider chatgpt"
    Then the stdout matches:
      | pattern                                                |
      | PROVIDER\s+WINDOW\s+USED\s+RESETS                      |
      | chatgpt\s+5-hour\s+42%\s+2026-10-28 13:00 UTC          |
      | chatgpt\s+7-day\s+17%\s+2026-11-02 09:00 UTC           |
    And the exit code is 0
