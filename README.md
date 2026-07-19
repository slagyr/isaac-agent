# 🍏 Isaac Agent 🧠

<img align="left" width="200" src="https://raw.githubusercontent.com/slagyr/isaac-agent/main/isaac-agent.png" alt="isaac-agent" style="margin-right: 20px; margin-bottom: 10px;">

The runtime for Isaac agents and crews. Supplies LLM API adapters, tool execution, session management with transcripts, and bridges for communication surfaces.

<br>

[![Agent](https://github.com/slagyr/isaac-agent/actions/workflows/ci-tests.yml/badge.svg)](https://github.com/slagyr/isaac-agent/actions/workflows/ci-tests.yml) 
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Clojure](https://img.shields.io/badge/Clojure-1.11%2B-blue?logo=clojure)](https://clojure.org)
[![Babashka](https://img.shields.io/badge/Babashka-1.3%2B-red?logo=clojure)](https://babashka.org)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?logo=openjdk)](https://openjdk.org/)

<br clear="left">

## What's here

- `isaac.agent` core — module factory, berths for tools, llm-api, slash-commands, providers.
- LLM API adapters: messages, chat-completions, ollama, claude-cli, responses, grover.
- Tool registry: builtin tools (web_fetch, read, write, exec, grep, edit, memory, search, glob, skills) and registration.
- Crew & sessions: persistent transcripts, compaction, state.
- Bridge: prompt execution, tool loop, comm dispatch.
- CLI: auth, crew, prompt, sessions.

Depends on [isaac-foundation](https://github.com/slagyr/isaac-foundation) for core machinery.

## Development

Sibling checkouts for dev:

```
plan/
  isaac-foundation/
  isaac-agent/  # this
```

Babashka-first:

```sh
bb spec
bb features
bb ci
```

See AGENTS.md and toolbox for full setup.

## Consumer coordinate

```clojure
io.github.slagyr/isaac-agent {:local/root "../isaac-agent"}
;; or
{:git/url "https://github.com/slagyr/isaac-agent.git" :git/sha "..."}
```
