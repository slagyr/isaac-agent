(ns session.turn-usage-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.session.session-steps :as session-steps]
            [isaac.tool.tools-steps :as tools-steps]))

(describe "Per-turn usage on assistant entries"

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

  (it "Normalizes input, output, and total tokens"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["usage-turn"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "usage.input_tokens" "usage.output_tokens"], :rows [["text" "Hello" "echo" "100" "25"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "usage-turn")
    (isaac.session.session-steps/session-transcript-matching "usage-turn" {:headers ["#index" "type" "message.role" "message.usage.input-tokens" "message.usage.output-tokens" "message.usage.total-tokens"], :rows [["-1" "message" "assistant" "100" "25" "125"]]}))

  (it "Normalizes cache-read from cached tokens"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["usage-turn"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "usage.input_tokens" "usage.output_tokens" "usage.input_tokens_details.cached_tokens"], :rows [["text" "Hello" "echo" "100" "25" "7"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "usage-turn")
    (isaac.session.session-steps/session-transcript-matching "usage-turn" {:headers ["#index" "type" "message.role" "message.usage.cache-read"], :rows [["-1" "message" "assistant" "7"]]}))

  (it "Normalizes cache-write from cache creation tokens"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["usage-turn"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "usage.input_tokens" "usage.output_tokens" "usage.cache_creation_input_tokens"], :rows [["text" "Hello" "echo" "100" "25" "3"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "usage-turn")
    (isaac.session.session-steps/session-transcript-matching "usage-turn" {:headers ["#index" "type" "message.role" "message.usage.cache-write"], :rows [["-1" "message" "assistant" "3"]]}))

  (it "Preserves reasoning tokens on the normalized usage block"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["usage-turn"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model" "usage.input_tokens" "usage.output_tokens" "usage.output_tokens_details.reasoning_tokens"], :rows [["text" "Hello" "echo" "100" "25" "11"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "usage-turn")
    (isaac.session.session-steps/session-transcript-matching "usage-turn" {:headers ["#index" "type" "message.role" "message.usage.reasoning-tokens"], :rows [["-1" "message" "assistant" "11"]]}))

  (it "Writes usage when the provider omits the usage block entirely"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["usage-turn"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "model"], :rows [["text" "Hello" "echo"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "usage-turn")
    (isaac.session.session-steps/session-transcript-matching "usage-turn" {:headers ["#index" "type" "message.role" "message.usage.input-tokens" "message.usage.output-tokens" "message.usage.total-tokens" "message.usage.cache-read" "message.usage.cache-write"], :rows [["-1" "message" "assistant" "25" "12" "37" "0" "0"]]}))

  (it "Uses accumulated tool-loop token counts for the final assistant entry"
    (isaac.session.session-steps/default-grover-setup)
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["usage-turn"]]})
    (isaac.tool.tools-steps/builtin-tools-registered)
    (isaac.session.session-steps/crew-tool-allow "main" "grep")
    (isaac.session.session-steps/responses-queued {:headers ["type" "content" "tool_call" "arguments" "model" "usage.input_tokens" "usage.output_tokens" "usage.input_tokens_details.cached_tokens" "usage.cache_creation_input_tokens"], :rows [["tool_call" "" "grep" "{}" "echo" "10" "5" "7" "11"] ["text" "Done" "" "" "echo" "4" "1" "2" "3"]]})
    (isaac.session.session-steps/user-sends-on-session "hi" "usage-turn")
    (isaac.session.session-steps/session-transcript-matching "usage-turn" {:headers ["#index" "type" "message.role" "message.content" "message.usage.input-tokens" "message.usage.output-tokens" "message.usage.total-tokens" "message.usage.cache-read" "message.usage.cache-write"], :rows [["-1" "message" "assistant" "Done" "14" "6" "20" "9" "14"]]})))
