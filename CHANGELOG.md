# Changelog

## Unreleased

- Per-charge `:tools {:deny [...]}` overlay: a caller (request → `charge/build`) can withhold tools for one turn. The deny list is subtracted from the global/crew cascade result (plus skill auto-tools), so withheld tools are never offered to the model; tokens accept `:ns/name`, `ns__name`, or `:ns/*`. Logs `:turn/tools-withheld` (debug) with the removed names when non-empty; a charge without `:tools` is unchanged (isaac-7mwt).
- `ChroniclePolicy/default-session` no longer falls back to another crew's most-recent session when the requested crew has none — nil means "no session on this crew," never a foreign one. `SessionPolicy/open-session!` implementations now refuse (throw) rather than silently reuse a name that already belongs to a different crew, and the sidecar/common session store never resolves a blank or nil identifier to the literal session id "session" (`get-session ""` is nil, `create-session!` mints a fresh name instead of colliding) (isaac-j95x).
- Per-turn `--with-context-mode` (CLI) / `:with-context-mode` override now wins over the crew's `:context-mode`, matching `--with-model` and `--with-crew`: `charge/build`'s `behavior-opts` was only forwarding model/crew overrides to `resolve-behavior`, so a crew-level `:context-mode` always beat the turn override (isaac-zdnx).
- Token gauge: a per-request prompt size above `:context-window` is recorded as the gauge value instead of being discarded, so `should-compact?` is true on the next check and compaction runs before the next request — discarding it left the gauge on its chars/4 tally and three workers ran at ~290k against a 200k budget without ever compacting. The adapter contract now distinguishes per-request prompt size from turn usage: usage carries `:prompt-scope` (`:request` / `:running-sum` / `:unknown`, absent means `:request`), the Responses API declares `:unknown` on a chained `previous_response_id` request because that chain is billed cumulatively, and an adapter that declares none leaves the stamp untouched — no zero is written. `:session/stamp-implausible` now fires only for a running sum and means "adapter bug" (isaac-dgod).
- Parallel-tool-calls hint rewritten from a survey of public coding-agent prompts: capability statement, default-to-parallel with the one sequential exception, the cost named in round-trips, plan-the-batch, and a 3–5 call batch bound. Same var, same insertion point, all providers (isaac-pn98).

- Context gauge counts the assistant output the tally cursor left behind: only the first reply after the cursor is covered by `:last-output-tokens`, so a tool loop's later replies are no longer counted nowhere. A response reporting zero prompt tokens (a refusal, or a provider that does not count) no longer overwrites `:last-input-tokens` with zero. `sessions list` falls back to the transcript's own stamped sum when the tally reads zero under a transcript of real size, so a full session is never listed as empty (isaac-166j).
- Provider response contract: `:reasoning :summary` is optional. A provider that streams thinking with no summarizable text (claude-cli) no longer fails the turn as `:provider-contract`, so a completed turn stops logging `:chat/provider-contract-violated` at `:error` and the episodes seal stops failing with `:provider-error` (isaac-ddls).
- Repin isaac-foundation (and spec / test-support / marigold.*) to foundation main `0b120cc` so the pin is reachable after bean-branch squash (isaac-lsz2).
- Compaction summaries run at `:compaction {:effort 2}` (configurable), chunk whenever history exceeds `:max-request-tokens` (default 32k) regardless of the model window, and retry a transport-class drop (`:stream-stalled` / `"closed"`) once at half size before counting a consecutive failure. Logs `:session/compaction-chunk-retry` (isaac-jgng).
- Loop-driver seam: `tool-loop/run` dispatches on provider `:drives-tool-loop?`; default loop unchanged; provider-driven loops compact between turns only and log `:turn/compaction-deferred` (isaac-1sdl).

## 0.1.67

- Closing an episode no longer writes a duplicate record into the legacy `episodes/<crew>/<eid>/` tree: the session-migrate materializer nests under the lifecycle's session id (`:nest-under`) and stamps `:session-id`; the CLI `episodes migrate-session` nests under the migrated session (isaac-x62d).

## 0.1.66

- `episodes migrate-layout`: a crew's legacy recall index is carried over as-is; no placeholder rows are added for scenes it skipped (routine scenes), so recall queries no longer warn about stale rows after migration (isaac-xqy1).

## 0.1.65

- `episodes migrate-layout` carries each crew's recall index (rows, model, vectors) from `episodes/<crew>/` into `sessions/<crew>/recall/`, re-keyed by session id, and removes the legacy index files and emptied directories; previously it rebuilt placeholder rows that recall treated as stale (isaac-xqy1).

## 0.1.64

- `episodes migrate-layout`: a flat session that is the thread of a leftover episode (post-mmod Discord/ACP threads) is stamped `:session-policy :episodes`, so session.edn and the fleet index agree; the dry-run line prints the policy (isaac-lhnq).

## 0.1.63

- Tool results stop provoking re-reads (isaac-yk0u): `fs__edit` / `fs__multi_edit` / `fs__write` answer with the edited region, line-numbered like a read; a repeated read, grep or skill load whose result hash matches one seen earlier in the same context window returns a short "already in your context since cycle N" stub (hash-only cache in the turn's cycle map, invalidated by edits, cleared on compaction). Logs `:tool/cache-hit`.

## 0.1.62

- Episodes storage layout: one directory per session under `sessions/<crew>/<session-id>/` with `session.edn` holding identity and overrides once; episodes nest inside (`episodes/<episode-id>/{episode.edn,current.ednl,scenes/}`); the recall index moves to `sessions/<crew>/recall/`; a fleet-wide `sessions/index.edn`; store-minted 17-digit episode ids; `isaac episodes migrate-layout [--dry-run]` moves an existing root (old ids kept) (isaac-b6w0).
- Operator cancel: `sessions cancel` stamps `:cancelled` on the turn marker; resume honours it (isaac-jejt).

## 0.1.61

- Exhausted turns: the wrap-up note is persisted to the transcript as the final assistant message, so the next turn resumes from it (isaac-x0cw).

## 0.1.60

- Session policy is the only transcript seam: bridge resume (dangling-tool-call and torn-line repair), `/status` turn count, `charge/transcript` and `isaac.api/create-session!` resolve the crew's SessionPolicy instead of holding the primitive store. `repair-transcript!` is implemented by the sidecar and memory stores (torn trailing EDNL line truncated to the last complete line) and delegated by both policies, so isaac-b6w0 can relocate the episode transcript in one place (isaac-jqma).
- Parallel tool batches: the cancel-mid-batch scenario expects the in-flight call to report `tool-cancel`, matching the 0.1.59 preserved `{:error :cancelled}` (isaac-qpdb fallout).

## 0.1.59

- Tool registry: `run-handler` preserves a handler's `{:error :cancelled}` instead of stringifying it through output capping, so a cancel mid-tool reaches the drive and comms get `on-tool-cancel` (ACP `tool_call_update` status `cancelled`). Belt: `announce-tool-call!` also emits the cancel while the tool is `:running` (isaac-qpdb).

## 0.1.58

- Session policy berth (`:isaac.agent/session-policy`): chronicle and episodes are per-crew policies over a primitive session store. Crews select one with `:session-policy :chronicle | :episodes` (default chronicle; an unknown policy is a config error). The bridge, drive, comms, hail and prompt CLI stop resolving episodes themselves; the session id never changes; recall is injected inside the policy's first append; a conversation start without a session id asks the policy for `default-session`. Crew config key `:conversation` is gone (isaac-mmod).

## 0.1.57

- Episodes: the TTL sweep logs `:episodes/closing` before sealing, `:episodes/closed` only on success, and `:episodes/close-failed` with the error otherwise. An empty episode (no scenes, summary-only transcript) is deleted instead of sealed and logs `:episodes/deleted :reason :empty`, so a compacted-away successor can no longer be retried every 30 s forever (isaac-9tjo).

## 0.1.56

- Turn instructions: the one-line batching hint becomes a four-line tool-discipline block — batch independent calls, locate with grep/glob then read only the region, read a file once in a large window and never re-read after your own edit, load a skill once per turn. Specs reference the var instead of pinning the text.

## 0.1.46

- Transcript appends are atomic per line: `append-entry!` builds the full EDNL line and appends under a per-path lock, so tool results completing concurrently (isaac-j2v0 parallel batches) can no longer tear a line and poison the session (isaac-jz6h, append-lock leg).

- Transcript appends are single-writer: concurrent toolResult lines stay one complete EDN object (isaac-jz6h).

## 0.1.44

- Cycle map carries the turn's `:origin` (charge inbound origin) on on-cycle-start/on-cycle-end so comms route cycle-scoped output — asides, the reply — to the originating channel even when the session key is an episode id; memory comm records `origin-kind` (isaac-ay0s).

## 0.1.43

- Token accounting locked in: per-cycle provider stamp, last-cycle stamp never a sum, drift-calibrated gauge, implausible-stamp cap with `:session/stamp-implausible`, claude-cli counts cached input (isaac-vuto).
- Idle sealing: quiet episodes seal and index within minutes on the episodes tick; TTL close rides the same tick (isaac-q34y).
- Scuttlebutt train repairs on main after the 5nxf merge (isaac-jarr).

## 0.1.42

- Scuttlebutt Comm protocol: cycle/chatter/reckoning/aside/reply/bulletin/tool-progress replace the old text-chunk + four compaction callbacks; CliComm deleted (null fallback); memory comm is the reference implementor (isaac-5nxf, isaac-jarr).
- Train-gate repairs: streaming mock tools now land on the crew allow-list so `on-tool-progress` fires (scuttlebutt.feature:91); cancel-between-tools is a known flake in the full suite and green in isolation (isaac-jarr).

## 0.1.41

- Conversation routing seam: surfaces pass an explicit `:conversation {:kind :thread :id ...}` plus `:origin`; the bridge alone resolves chronicle vs episode; turn results carry the preserved origin for delivery; `:episodes/opened` logs origin (isaac-7dkp, supersedes isaac-mrfu's charge-skip fix).

## 0.1.40

- Token accounting (isaac-pqjn), compact-from-provider-tokens + overflow compact-and-retry (isaac-p9zy, isaac-x2up), fixture/expectation repairs (isaac-0oqd).

### Added

- `isaac config keys` / `list` with no path list top-level resolved config keys (foundation pin) (isaac-rg61).
- `isaac episodes migrate-session` — materialize a session as a closed episode (scenes + gists) under `episodes/<crew>/<id>/` with configurable `:episodes {:gist-model ...}` (isaac-rxr4).
- migrate-session: line-format segmentation, provider-error abort, flagged-span `:raw`, 1-based span numbers (isaac-80pq).
- Optional embedding seam (`:embedding` config, Embedder protocol, ollama adapter, `isaac embed` CLI) for phase-1 recall (isaac-5lri).
- Episode scenes stored as `<scene-id>.md` (YAML frontmatter + distilled text body); `episode.edn` unchanged (isaac-lq7x).
- Mid-loop transcript flush: each toolCall is persisted before exec and each toolResult immediately after; cancel leaves a dangling toolCall (isaac-l7lv).

### Breaking

- Default prompt discovery roots moved from `<isaac-root>/config/{commands,skills,rules}` to `<isaac-root>/prompts/{commands,skills,rules}`. Project-layer prompts live under `<project-root>/.isaac/prompts/`; boot files (`AGENTS.md`) load from the same discovered project root (walk up from session cwd). There is no legacy fallback under `config/` or `<project-root>/prompts/` — move existing prompt files on upgrade. Configurable extra roots (`:prompt-paths`, `:command-paths`, `:skill-paths`) are unchanged.