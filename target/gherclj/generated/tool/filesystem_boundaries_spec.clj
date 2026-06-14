(ns tool.filesystem-boundaries-spec
  (:require [speclj.core :refer :all]
            [gherclj.core :as g]
            [gherclj.lifecycle :as lifecycle]
            [isaac.config.config-steps :as config-steps]
            [isaac.foundation.root-steps :as root-steps]
            [isaac.session.session-steps :as session-steps]))

(describe "Per-crew filesystem boundaries"

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

  (it "crew can read files in their quarters"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :echo}\n :providers {:grover {:base-url \"http://test\" :api \"grover\"}}\n :models    {:echo {:model \"echo\" :provider :grover :context-window 32768}}}")
    (isaac.config.config-steps/config-file-containing "crew/main.edn" "{:tools {:allow [:read]}}")
    (isaac.session.session-steps/crew-has-file "main" "notes.txt" "hello")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["fence-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool" "arguments"], :rows [["tool_call" "read" "{\"file_path\": \"/isaac-state/crew/main/notes.txt\"}"] ["text" "" "Got it"]]})
    (isaac.session.session-steps/user-sends-on-session "read notes" "fence-test")
    (isaac.session.session-steps/session-transcript-matching "fence-test" {:headers ["type" "message.role" "message.isError"], :rows [["message" "toolResult" ""]]}))

  (it "crew can read files in whitelisted directories"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :echo}\n :providers {:grover {:base-url \"http://test\" :api \"grover\"}}\n :models    {:echo {:model \"echo\" :provider :grover :context-window 32768}}}")
    (isaac.config.config-steps/config-file-containing "crew/main.edn" "{:tools {:allow       [:read]\n         :directories [\"/tmp/isaac-playground\"]}}")
    (isaac.session.session-steps/given-file-contains "/tmp/isaac-playground/data.txt" "hello")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["fence-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool" "arguments"], :rows [["tool_call" "read" "{\"file_path\": \"/tmp/isaac-playground/data.txt\"}"] ["text" "" "Got it"]]})
    (isaac.session.session-steps/user-sends-on-session "read data" "fence-test")
    (isaac.session.session-steps/session-transcript-matching "fence-test" {:headers ["type" "message.role" "message.isError"], :rows [["message" "toolResult" ""]]}))

  (it "crew cannot read files outside their boundaries"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :echo}\n :providers {:grover {:base-url \"http://test\" :api \"grover\"}}\n :models    {:echo {:model \"echo\" :provider :grover :context-window 32768}}}")
    (isaac.config.config-steps/config-file-containing "crew/main.edn" "{:tools {:allow [:read]}}")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["fence-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool" "arguments"], :rows [["tool_call" "read" "{\"file_path\": \"/etc/passwd\"}"] ["text" "" "Sorry"]]})
    (isaac.session.session-steps/user-sends-on-session "read passwords" "fence-test")
    (isaac.session.session-steps/session-transcript-matching "fence-test" {:headers ["type" "message.role" "message.isError" "message.content"], :rows [["message" "toolResult" "true" "path outside allowed directories"]]}))

  (it "crew cannot write files outside their boundaries"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :echo}\n :providers {:grover {:base-url \"http://test\" :api \"grover\"}}\n :models    {:echo {:model \"echo\" :provider :grover :context-window 32768}}}")
    (isaac.config.config-steps/config-file-containing "crew/main.edn" "{:tools {:allow [:write]}}")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["fence-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool" "arguments"], :rows [["tool_call" "write" "{\"file_path\": \"/tmp/evil.txt\", \"content\": \"gotcha\"}"] ["text" "" "Sorry"]]})
    (isaac.session.session-steps/user-sends-on-session "write evil" "fence-test")
    (isaac.session.session-steps/session-transcript-matching "fence-test" {:headers ["type" "message.role" "message.isError" "message.content"], :rows [["message" "toolResult" "true" "path outside allowed directories"]]}))

  (it "crew can access session cwd when it opts in via :cwd"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :echo}\n :providers {:grover {:base-url \"http://test\" :api \"grover\"}}\n :models    {:echo {:model \"echo\" :provider :grover :context-window 32768}}}")
    (isaac.config.config-steps/config-file-containing "crew/main.edn" "{:tools {:allow       [:read]\n         :directories [:cwd]}}")
    (isaac.session.session-steps/given-file-contains "/work/project/hello.txt" "hi there")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "cwd"], :rows [["fence-test" "/work/project"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool" "arguments"], :rows [["tool_call" "read" "{\"file_path\": \"/work/project/hello.txt\"}"] ["text" "" "Got it"]]})
    (isaac.session.session-steps/user-sends-on-session "read hello" "fence-test")
    (isaac.session.session-steps/session-transcript-matching "fence-test" {:headers ["type" "message.role" "message.isError"], :rows [["message" "toolResult" ""]]}))

  (it "crew without :cwd opt-in cannot access session cwd"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :echo}\n :providers {:grover {:base-url \"http://test\" :api \"grover\"}}\n :models    {:echo {:model \"echo\" :provider :grover :context-window 32768}}}")
    (isaac.config.config-steps/config-file-containing "crew/main.edn" "{:tools {:allow [:read]}}")
    (isaac.session.session-steps/given-file-contains "/work/project/hello.txt" "hi there")
    (isaac.session.session-steps/sessions-exist {:headers ["name" "cwd"], :rows [["fence-test" "/work/project"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool" "arguments"], :rows [["tool_call" "read" "{\"file_path\": \"/work/project/hello.txt\"}"] ["text" "" "Sorry"]]})
    (isaac.session.session-steps/user-sends-on-session "read hello" "fence-test")
    (isaac.session.session-steps/session-transcript-matching "fence-test" {:headers ["type" "message.role" "message.isError" "message.content"], :rows [["message" "toolResult" "true" "path outside allowed directories"]]}))

  (it "path traversal that escapes boundaries is rejected"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :echo}\n :providers {:grover {:base-url \"http://test\" :api \"grover\"}}\n :models    {:echo {:model \"echo\" :provider :grover :context-window 32768}}}")
    (isaac.config.config-steps/config-file-containing "crew/main.edn" "{:tools {:allow [:read]}}")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["fence-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool" "arguments"], :rows [["tool_call" "read" "{\"file_path\": \"/isaac-state/crew/main/../../etc/passwd\"}"] ["text" "" "Sorry"]]})
    (isaac.session.session-steps/user-sends-on-session "sneaky read" "fence-test")
    (isaac.session.session-steps/session-transcript-matching "fence-test" {:headers ["type" "message.role" "message.isError" "message.content"], :rows [["message" "toolResult" "true" "path outside allowed directories"]]}))

  (it "crew cannot read its own config file"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :echo}\n :providers {:grover {:base-url \"http://test\" :api \"grover\"}}\n :models    {:echo {:model \"echo\" :provider :grover :context-window 32768}}}")
    (isaac.config.config-steps/config-file-containing "crew/main.edn" "{:tools {:allow [:read]}}")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["fence-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool" "arguments"], :rows [["tool_call" "read" "{\"file_path\": \"/isaac-state/config/crew/main.edn\"}"] ["text" "" "Sorry"]]})
    (isaac.session.session-steps/user-sends-on-session "read my config" "fence-test")
    (isaac.session.session-steps/session-transcript-matching "fence-test" {:headers ["type" "message.role" "message.isError" "message.content"], :rows [["message" "toolResult" "true" "path outside allowed directories"]]}))

  (it "crew cannot grep files outside allowed directories"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :echo}\n :providers {:grover {:base-url \"http://test\" :api \"grover\"}}\n :models    {:echo {:model \"echo\" :provider :grover :context-window 32768}}}")
    (isaac.config.config-steps/config-file-containing "crew/main.edn" "{:tools {:allow [:grep]}}")
    (isaac.session.session-steps/given-file-contains "/tmp/secret-stash/passwords.txt" "hunter2")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["fence-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool" "arguments"], :rows [["tool_call" "grep" "{\"pattern\": \"hunter\", \"path\": \"/tmp/secret-stash\"}"] ["text" "" "Sorry, I cannot."]]})
    (isaac.session.session-steps/user-sends-on-session "find passwords" "fence-test")
    (isaac.session.session-steps/session-transcript-matching "fence-test" {:headers ["type" "message.role" "message.isError" "message.content"], :rows [["message" "toolResult" "true" "path outside allowed directories"]]}))

  (it "crew cannot glob files outside allowed directories"
    (isaac.foundation.root-steps/in-memory-state "\"isaac-state\"")
    (isaac.config.config-steps/config-file-containing "isaac.edn" "{:defaults  {:crew :main :model :echo}\n :providers {:grover {:base-url \"http://test\" :api \"grover\"}}\n :models    {:echo {:model \"echo\" :provider :grover :context-window 32768}}}")
    (isaac.config.config-steps/config-file-containing "crew/main.edn" "{:tools {:allow [:glob]}}")
    (isaac.session.session-steps/given-file-contains "/tmp/secret-stash/treasure.clj" "")
    (isaac.session.session-steps/sessions-exist {:headers ["name"], :rows [["fence-test"]]})
    (isaac.session.session-steps/responses-queued {:headers ["type" "tool" "arguments"], :rows [["tool_call" "glob" "{\"pattern\": \"*.clj\", \"path\": \"/tmp/secret-stash\"}"] ["text" "" "Sorry, I cannot."]]})
    (isaac.session.session-steps/user-sends-on-session "hunt for code" "fence-test")
    (isaac.session.session-steps/session-transcript-matching "fence-test" {:headers ["type" "message.role" "message.isError" "message.content"], :rows [["message" "toolResult" "true" "path outside allowed directories"]]})))
