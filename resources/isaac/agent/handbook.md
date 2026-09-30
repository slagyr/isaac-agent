# isaac.agent — Isaac's operating handbook, the crew

You are a crew running inside Isaac. This chapter is isaac-agent's: the
module that owns crews, souls, tools, sessions and transcripts, turns and
the tool loop, compaction, models and providers, comms and the bridge
(slash commands), effort, and delivery. If you haven't read
`isaac.foundation` yet (config mechanics, modules, `handbook__configure`
itself), read that chapter first — this one assumes it.

Everything below is either a **config path** you change with
`handbook__configure`, or a **CLI-only** action you ask an operator to run.
Where a concept belongs to a different module, this chapter gives one line
and that module's topic id rather than re-documenting it.

## Crews

A crew member is an entry in the `crew` config table (one file per id under
`config/crew/`, or inline in `isaac.edn`). It bundles a `model`, a `soul`,
tool permissions, and a few session defaults. Read one back with
`config get crew.<id>` (or a single field, `config get crew.<id>.model`);
change one with `config set crew.<id>.<field> <value>`.

Fields worth knowing:

- `model` — the model alias this crew uses (see Providers, models, and
  effort, below). `model-fallback` is an ordered list of alternate model
  ids tried when the head model's provider is walled, rejected for auth, or
  stalled.
- `soul` — the crew member's standing orders, in prose. Write it inline as
  a string, or leave it out of the `.edn` entirely and instead write
  `config/crew/<id>.md` — the crew's file becomes YAML frontmatter (every
  other field) plus a markdown body, and the body *is* the soul. The two
  are exclusive: setting `soul` inline while a companion `.md` also exists
  is refused. A blank or missing soul is not an error — the crew still
  runs, falling back to a generic default identity.
- `tags` — a flat set of keywords for discovery and routing (`config set
  crew.<id>.tags.<tag>`, `config unset crew.<id>.tags.<tag>` — a set field
  takes the member in the path, no value). `isaac crew list --tag <t>`
  filters on them; nothing in the turn path reads them automatically. Don't
  confuse these with session tags (a different, per-session set — see
  Sessions and transcripts).
- `cwd` — the default working directory for a **new** session created on
  this crew. It must be an absolute path. It only seeds the session at
  creation — it is not re-resolved on every turn, and setting it alone
  doesn't make it *readable*; see Tools and directories for the grant a
  crew also needs.
- `session-policy` — `:chronicle` (the default; one container per session)
  or `:episodes`, a container-per-episode policy owned by a separate
  module (`isaac.session.episodes`, if installed). An unknown name is
  refused at config load, naming what's actually registered. Changing a
  crew's policy doesn't retroactively touch its existing sessions: a
  session remembers the policy that opened it, and a turn refuses outright
  if that no longer matches the crew's current policy — the fix is a new
  session, not a forced switch.
- `tools`, `tools.directories` — covered fully in Tools and directories,
  below; this is where allow/deny lists and directory grants live per
  crew.
- `context-mode`, `cycle`, `compaction`, `effort`, `history-retention` —
  turn/session behavior knobs; covered in their own sections below.

`isaac crew list` and `isaac crew show <id>` are **read-only** — they
report the resolved crew table (`--json`/`--edn` for the full soul text;
the table view clips it to a preview). There is no `crew create`/`edit`
subcommand: a crew is a config entity, changed with `handbook__configure`
or `isaac config set`/`unset` like any other.

### Troubleshooting

- **A crew's soul doesn't seem to be in effect.** Confirm it isn't blank —
  a blank companion `.md` body reads as absent, and Isaac falls back to a
  generic identity silently rather than failing. `config get crew.<id>
  --raw` shows what's actually written.
- **Setting `soul` inline is refused.** A companion `config/crew/<id>.md`
  already exists for this crew — the two are mutually exclusive. Unset one
  before writing the other.
- **A turn on this crew is refused outright, citing session policy.** The
  session was opened under a different `session-policy` than the crew
  currently declares. Point the turn at a new session rather than trying
  to force the mismatch through.
- **A crew's `cwd` doesn't help a tool read anything.** `cwd` only seeds
  the session's working directory; it grants no filesystem access by
  itself. See Tools and directories.

## Tools and directories

Config uses namespaced keywords for tool identity (`:fs/read`); the model
sees the wire form (`fs__read`). A namespace glob (`:fs/*` / `fs__`) covers
every tool in that family. `:all` is a policy token, not a list member — a
list containing `:all` (`[:all]`) is refused; the bare value `:all` is
what "every tool" looks like.

**Allow/deny is a four-step, last-match-wins cascade**: global allow,
global deny, crew deny, crew allow, in that order. No `:tools.allow`
anywhere is deny-all. A crew's own `deny` overlays the global deny — it
never removes it. A crew's own `allow` is the last word and can re-open a
tool the global config denied. Concretely: with a global `defaults.crew
.tools.deny [:exec/run]` and a crew's own `tools.allow [:exec/run]`, that
one crew keeps `exec/run` while every other crew doesn't.

**Directory grants are a separate, longest-prefix-wins system** that
applies only to `fs/*` tools (`exec` is not covered by it at all — allowing
`exec/run` grants shell access with no directory check). A grant token is
`:cwd` (the session's own working directory), `:quarters` (this crew's own
area under the state root, `crew/<id>/`), or an absolute path. No grants at
all means no path is readable, including the session's own cwd. Among
grants that match a path, the **longest matching prefix wins**; a tie
between grants of the same length falls back to the same four-step
cascade order used for tool allow/deny (global allow, global deny, crew
deny, crew allow). A crew's directories overlay the global ones — they add,
they don't replace. Path traversal (`..`) is resolved before matching, so
it can't walk out of an allowed root, and a crew can never read Isaac's own
`config/` regardless of grants (see isaac.foundation's Files section).
`acknowledge-broad?` suppresses the warning a very broad grant (like the
filesystem root) otherwise logs — it doesn't change what's allowed.

Tool-level knobs, not directory-related:

- `config:tools.web_search.provider` / `config:tools.web_search.api-key` —
  the only registered web search backend is `:brave`; the key is required
  once a provider is set.
- `defaults.tools.max-lines` / `defaults.tools.max-bytes` — global caps on
  a tool result string before head-tail truncation. `defaults.crew.tools
  .max-parallel` caps concurrent tool calls from one model response batch,
  per crew.

`resource-pools` (entity table, `config/resource-pools/<id>.edn`) is a
different kind of gate: not a tool permission, but an admission check a
**turn** leases before it runs (submitted with `--pool <id>`, repeatable).
A turn whose pools are busy is parked on the turn queue rather than
dropped (see Turns and the tool loop). Each pool names a `type` — the
built-in `:tide` type gates on a clock `window` (an `"HH:mm-HH:mm"` range,
wraparound allowed, e.g. `"22:00-06:00"`); other types (contributed via
the `:isaac.agent/resource-pool-types` berth) may instead use `limit`
(a concurrency cap) or `members` (a list of resource paths the pool
coordinates). An unregistered `type` is refused at config load.

### Troubleshooting

- **A tool the crew should have access to still isn't callable.** Walk the
  cascade in order — a global deny survives a crew's `tools` block unless
  the crew explicitly re-allows that exact tool or family; an empty
  `tools` section on a crew is deny-all, not inherit-all.
- **`fs/read` is allowed but a file still can't be read.** Allow/deny and
  directory grants are separate systems — a tool being allowed says
  nothing about which paths it can touch. Check `tools.directories.allow`
  for the crew and the global defaults.
- **A directory grant doesn't seem to apply.** Check for a more specific
  deny under it — the longest matching prefix wins regardless of which
  layer (global/crew) declared it.
- **A resource pool's `type` is rejected as unknown.** It isn't a
  built-in (`:tide`) and no installed module has contributed it via the
  `:isaac.agent/resource-pool-types` berth — check `isaac modules list`.

## Sessions and transcripts

A session is a persisted conversation, stored independently of any one
crew member's config, one directory per session under the state root
(`sessions/<crew>/<id>/`): a `session.edn` sidecar plus an EDNL transcript
(`current.ednl`, one entry per line — messages, tool calls/results,
compaction records). `config:sessions.store` selects `:ednl-dir` (the
default) or `:memory` (test/ephemeral use).

Which session a turn actually lands on — an existing one, or a new one —
is decided by **frequencies**; see Frequencies, below, for the shape and
matching rules.

`history-retention` (`:prune` or `:retain`, resolved once at session
creation from crew/model/provider/`defaults` and then locked on that
session for its lifetime) decides whether compaction freezes the discarded
prefix into a numbered segment file (`:retain`, the default) or simply
drops it (`:prune`). Either way the **live** transcript view a turn sees is
a window over the full, append-only record — nothing is rewritten in
place.

`session-policy` (chronicle vs. episodes) is a crew field, covered above
under Crews.

`isaac sessions` subcommands: `list`, `show <id>`, `set <id>.<path>
<value>` / `unset <id>.<path>` (mutate a stored field — this is how you
clear a stuck compaction block, see Compaction and context modes), `rename
<old> <new>`, `delete <id>`, `migrate` (convert legacy sessions to the
current on-disk layout), and `cancel <id>` (stamp `:cancelled` on an
in-progress turn marker, fire-and-forget).

### Troubleshooting

- **A new turn keeps missing an existing session and creating another
  one.** Check `create` in the frequencies used — `:if-missing` only skips
  creation when a real match exists and isn't itself busy; if you need to
  always land on one session, name it explicitly with `session`. See
  Frequencies, below, for the full picture.
- **`history-retention` doesn't seem to match what I just set on the
  crew.** It's resolved once, at session creation, and locked — changing
  the crew's default afterward doesn't retroactively change existing
  sessions.
- **A turn refuses with a session-policy mismatch.** See Crews,
  Troubleshooting — start a new session rather than forcing it.

## Frequencies

Isaac is a ship, and a turn doesn't just start — it's **hailed**. Every
caller that wants a turn to happen (a CLI invocation, a hail, a cron tick,
a webhook, a comm) opens hailing frequencies and Isaac picks up on
whichever session is listening. The name is a small nod to *Star Trek*'s
"Open hailing frequencies" — the module quietly enjoys that it gets to say
this with a straight face in production logs.

**What it is.** A frequencies map is the one shape every caller builds to
say "this session," or "a session like this," plus a handful of per-turn
overrides. `isaac.session.frequencies` owns conforming, matching, and
resolving it; nothing else in Isaac re-implements session selection. The
CLI (`isaac prompt` and friends), hail, cron, `defaults.frequencies`, ACP,
and every comm all build the same map and hand it here — each of those
modules' own chapters cover *how* they build it, not what it means once
built.

Selection keys:

- `session` — explicit session id(s); the first is used. An explicit
  `session` always wins outright — no tag or crew selector narrows or
  overrides it, and a named session that doesn't exist is either created
  (`create :if-missing`/`:always`) or refused (`create :never`), never
  silently matched to something else.
- `session-tags` — tags a session must carry, ANDed (every tag named, not
  just one).
- `crew` — sessions whose `:crew` matches this id.
- `prefer` — `:recent` or `:oldest`; the tiebreak when more than one
  session matches (default `:recent`).
- `create` — `:never`, `:if-missing` (the default), or `:always`. A match
  that's merely **busy** (already mid-turn) is not "missing" — with
  `:if-missing`, a crew with one busy and one free session takes the free
  one even if the busy one is more recent; if every candidate is busy, the
  turn waits and attaches to whichever frees first rather than spawning a
  sibling session.

Per-turn overrides — these change how *this* turn runs without touching
the crew's own config or persisting onto the session:

- `with-crew` — run this turn under a different crew's model and soul.
- `with-model` — override the model for this turn.
- `with-effort` — override effort (see Providers, models, and effort).
- `with-context-mode` — override `:full`/`:reset` for this turn only.

A CLI-only `--resume` selects across every live session, ignoring every
other selector (it's a resolver-level flag, not one of the keys above);
combining it with `session`/`crew`/`session-tags` is a usage error.

**How matching resolves.** An explicit `session` is looked up directly.
Otherwise, naming `crew` and/or `session-tags` filters the live session
set to matches on both. `create :always` skips matching entirely and
always creates. Zero matches with `create :never` (or an explicit
`session` that doesn't exist) fails the turn outright — the error names
what was searched for (a session id, a crew, or the tags) rather than a
bare "not found." More than one match resolves by `prefer`. There's no
error for "too many": frequencies always picks exactly one session or
fails outright — it never fans a turn out to several. An older `:reach`
key that did exactly that is gone for good; `isaac config validate`
refuses it outright wherever it still appears (isaac-5gu1).

**`defaults.frequencies`** supplies fallback values — most commonly
`crew` — for any key a caller's own map doesn't set. The merge is
consumer-wins: a caller's own frequencies beat the default on any key
they both set. And if the caller *does* name `session`, `session-tags`,
or `crew` explicitly, the default's `crew` is dropped from the merge
entirely rather than tacked on as an extra filter alongside it — a caller
selecting by tag shouldn't also be silently constrained to whatever crew
the default names. A bare `isaac prompt -m "..."` with nothing configured
and no `defaults.frequencies.crew` set has nowhere to land and fails.

**How to change it.** Everything about a specific call's frequencies is
per-call, not config — CLI flags, a hail's `:frequencies`, a cron job's
flat fields, a comm's per-channel config (see that surface's own chapter
for its addressing syntax; the shape and resolution rules here are the
same everywhere). The one config-level knob is the fallback:

```
config set defaults.frequencies.crew cordelia
config set defaults.frequencies.create if-missing
```

**How to verify.** `isaac sessions show <id>` confirms which session a
turn actually landed on and its `crew`/tags. A rejected frequencies map
raises before any session work happens — a CLI's stderr, a hail's error
response, or a `config validate` failure on `defaults.frequencies` all
name the same underlying reason.

### Troubleshooting

- **A turn keeps missing an existing session and creating another one.**
  Check `create`: `:if-missing` (the default) only skips creation when a
  real, non-busy match exists. Name the session explicitly with `session`
  if you need to always land on one.
- **A bare invocation with no selectors fails with "no session
  selected."** Nothing named a session, tags, or crew, and
  `config:defaults.frequencies.crew` isn't set either — there's nothing to
  resolve. Set the default or pass a selector.
- **`config validate` rejects an unknown key under `defaults.frequencies`
  by name.** That table is checked against the same key set this section
  documents — a typo (a stray `:reach`, `:session-tag` instead of
  `:session-tags`) is refused rather than silently ignored.
- **A `with-*` override didn't seem to "stick."** It isn't supposed to —
  overrides apply only to the turn they're attached to and never persist
  onto the crew or the session's own config.

## Turns and the tool loop

A turn is one full pass through the tool loop, from the user's (or hail's)
input to a reply. Per-crew `cycle` knobs shape how long a turn can run
before it's forced to wrap up:

- `cycle.limit` — the tool-call cycle budget (default 100).
- `cycle.checkpoint-every` — nudge the model to checkpoint its progress
  every N cycles (off if omitted).
- `cycle.wrap-up-prompt` / `checkpoint-prompt` — override the built-in
  nudge text sent for each.
- `cycle.continuations` — how many times a wrapped-up turn may be
  re-driven as a fresh turn on the same session (default 2).

When the cycle budget runs out with tool calls still pending, what happens
next depends on how the turn was submitted. An attended origin (a live
terminal) gets `:stop`: one final, tool-less summary cycle. An unattended
origin (hail) gets `:wrap-up`: one more cycle **with** tools plus a
checkpoint nudge, then a tool-less closing note — and that note becomes a
**continuation**: a fresh turn on the same session telling the model to
carry on from its own note. Continuations are themselves budgeted
(`cycle.continuations`); running out logs the exhaustion and raises
attention (see Comms and delivery) rather than looping forever. Every
turn's result reports how it ended: `:reply`, `:cycle-limit`, `:cancelled`,
`:error`, `:context-exhausted`, or `:provider-unavailable`.

Turns don't run in the background unobserved — a submission that can't
start immediately (its resource pools are busy, or a session is already
running one) is **parked**, not dropped, in a durable waiting room. It
wakes on a scheduler tick or the moment a running turn finishes and
releases its pools, and admits held requests in submit order. `isaac
turns list` shows what's parked (`--all` includes finished ones); `isaac
turns show <id>` inspects one record; `isaac turns drop <id>` evicts a
held request so it never runs. Only one turn runs per session at a time —
there is no crew-wide concurrency cap anymore, just per-session
serialization.

An observer can be attached per turn (`--observer lookout`, or
`name:params`) to narrate lifecycle events (turn started/ended, with
outcome) as they happen; an unknown observer name is refused before the
turn even dispatches.

### Troubleshooting

- **A turn seems stuck and never starts.** Check `isaac turns list` for a
  held request — it's most likely waiting on a busy resource pool or a
  session already mid-turn, not lost.
- **A hail-driven turn stopped mid-task with tool calls left to make.**
  That's `:wrap-up`, working as intended — check whether it's still within
  its `cycle.continuations` budget; if exhausted, that's logged and
  attention is raised, and the next step is a fresh turn, not a wait.
- **An attended (terminal) turn stops abruptly instead of continuing.**
  `:stop` never continues by design — only unattended origins get
  continuations. That's the expected split between the two termination
  modes.

## Compaction and context modes

Every session tracks a running **gauge** — its estimate of tokens in play,
folding in the last response's usage plus everything appended since.
There's no tool to read the gauge directly; you experience it as
compaction happening quietly, or as a wrap-up/exhaustion event if it can't
keep up. Compaction fires once the gauge reaches `compaction.threshold`
(a fraction of the model's context window, default 0.8). `compaction
.strategy` decides how much gets folded:

- `:rubberband` — compacts everything compactable in one shot, replacing
  it with a single summary.
- `:slinky` — compacts only the older portion, leaving the most recent
  `compaction.head` fraction of the window (default 0.3) intact and
  unsummarized.

`compaction.async? true` runs the summarization call in the background
instead of blocking the current turn; the turn continues on the
not-yet-compacted transcript until the summary splices in. `compaction
.effort` (default 2) sets how hard the summarizer thinks. These live on
provider, crew, or model config — the usual layering applies (see
Providers, models, and effort).

If compaction itself keeps failing (the provider rejects even the
summarization request), Isaac doesn't retry forever: after three
consecutive failures the session is marked blocked and further turns are
refused immediately, with no further provider call, while an attention
bulletin goes out. Clear it with `isaac sessions unset <id>.block` once
the underlying cause (usually an oversized single message) is addressed;
the failure counter resets on the next successful turn.

`context-mode` (per crew, `:full` or `:reset`) decides what a turn
actually **replays** to the model — not what's stored. `:full` (the
default) sends the whole compaction-adjusted transcript. `:reset` sends
only the soul and the current message, treating every turn as
independent; the on-disk transcript still accumulates normally either
way, so switching back to `:full` later sees full history again.

### Troubleshooting

- **A session won't take any more turns and reports it's blocked.** Three
  consecutive compaction failures tripped the guard. Clear it with `isaac
  sessions unset <id>.block`; if it re-blocks immediately, the transcript
  likely has a single oversized entry compaction can't shrink.
- **Compaction seems to lose more (or less) than expected.** Check
  `compaction.strategy` — `:rubberband` folds everything; `:slinky` only
  folds the older tail, governed by `compaction.head`.
- **A crew on `:reset` seems to have no memory between turns.** That's the
  point of `:reset` — history is still recorded, just not replayed. Switch
  to `:full` if continuity is wanted.

## Providers, models, and effort

A **provider** (`providers` table) is an LLM service: base URL, auth mode,
and API adapter. `type` inherits a built-in template (`anthropic`, `xai`,
`openai`, `chatgpt`, `grok`, `ollama`, `grover` — the in-memory test
double) via the `:isaac.agent/provider-template` berth, so most provider
entries only need `id` and overrides. `api` selects the wire adapter
(`messages`, `chat-completions`, `responses`, `ollama`, `grover`) — this is
what actually speaks the provider's protocol. A **model** (`models` table)
names a provider plus the provider-specific model string, and can override
context window, compaction, and effort behavior per-model.

Authentication is CLI-only: `isaac auth login --provider <id>` (an
`api-key` provider prompts for and stores the key; an `oauth-device`
provider — `chatgpt`, `grok` — runs the device-code flow: a code and a
verification URL to approve in a browser). `isaac auth status` shows what's
authenticated; `isaac auth logout --provider <id>` removes it. None of
this has a `handbook__configure` path — a turn can't authenticate itself.

`model-fallback` on a crew is an ordered list of models tried, **within
the same turn**, when the head model's provider is **walled** (rate
limited or out of credit — not a failure, just retry-later) or its auth is
rejected, or it stalls. A walled provider is skipped for every later model
on that same provider, not just the current one. If the whole chain is
exhausted, the turn ends `:provider-unavailable` and one attention
bulletin fires (throttled — see Comms and delivery); a genuinely broken
request (a bad request, or context overflow) does **not** fall through the
chain — it fails in place instead, since another model wouldn't fix it.

**Effort** is the cross-provider "how hard should it think" knob,
0–10 (built-in default 7): `session > crew > model > provider > provider
template > 7`, first non-nil wins. `/effort N` sets a session-level
override; `/effort` alone reports the effective value; `/effort clear`
removes the override, falling back through the rest of the chain. A model
with `allows-effort false` (or effort explicitly 0) never gets `:effort`
attached to its requests — some models reject the field outright.

### Troubleshooting

- **`isaac auth login` succeeds but turns still report an auth error.**
  Confirm the crew's model actually points at the provider you
  authenticated — a crew on the wrong provider alias won't pick up a
  freshly stored credential.
- **A model-fallback chain never seems to kick in.** It only fires for
  walled/auth/stall conditions, mid-turn. A plain bad-request or
  context-overflow error is by design not retried on a different model.
- **`/effort` shows a value you didn't set.** Something above the session
  layer supplies it — check crew, then model, then provider `effort`, in
  that order, before assuming it's a bug.

## Bridge and slash commands

The bridge is the first thing that sees a user's raw input: anything
starting with `/` is routed to a registered slash command instead of the
model. `/status` prints session state (crew, model, session id, turn and
compaction counts, context usage, tool count, cwd). `/cwd [path]` shows or
sets the session's working directory. `/effort [N|clear]` — see Providers,
models, and effort. `/model [id]` shows or switches the session's model.
`/crew [id]` shows or switches the session's crew (soul, model, and
provider all change together).

`config:bridge.suspend-timeout-ms` (default 15000) bounds how long a clean
shutdown waits for an in-flight turn to reach a cooperative cancel point
before it's forced. A **suspended** turn is different from a **cancelled**
one: cancellation (any channel — Ctrl-C, an ACP cancel) deletes the turn
marker outright ("stop, don't come back"), while suspension — on shutdown,
or on "weather" (a provider wall, an auth failure, or a mid-stream stall) —
preserves the marker with a reason and, for weather, a `retry-at`, so a
scheduler sweep resumes it automatically once conditions clear. Repeated
weather suspensions on the same turn are normal, not failures.

### Troubleshooting

- **A turn seems to have vanished after a restart.** Check whether it was
  suspended (clean shutdown or weather) rather than cancelled — a
  suspended turn's marker survives and a resume sweep should pick it back
  up; `isaac sessions show <id>` shows the stamped reason.
- **A `/` message went straight to the model instead of running as a
  command.** The name after `/` isn't a registered slash command — check
  for a typo; unrecognized slash input falls through to the normal turn
  path rather than erroring.

## Comms and delivery

A **comm** (`comms` table) is a configured channel instance — Discord,
iMessage, ACP, memory (test), and so on — each entry naming a `type` (the
installed impl, contributed via the `:isaac.agent/comm` berth) and a
`crew` it routes into. Whatever extra fields a comm needs (a channel id, a
token) come from that impl's own schema, composed in dynamically — see
that comm module's own chapter for its specific fields.

The `comm__send` tool is **queue-first**: it never calls a comm directly.
It validates the target comm slot and the fields that comm's `send-schema`
requires, optionally resolves attachments (only for a comm that opted in
with `:send-attachments? true`, and only from paths already inside the
turn's allowed directories), and enqueues one delivery record. An unknown
comm slot or a missing required field is refused before anything is
queued. A background delivery worker (`isaac.agent`'s `comm-delivery`
component) drains that queue: on success the record is removed; a
transient failure (the comm is momentarily unreachable) is retried on a
fixed backoff (roughly 1s, 5s, 30s, 2m, 10m) up to five attempts before it
gives up; a permanent failure dead-letters immediately. None of this
backoff schedule is configurable today — a stuck delivery is either still
retrying or already dead-lettered, not silently lost.

`attention` is the system-scoped path for telling *an operator* something
needs looking at — not a crew's own comm traffic.
`config:attention.notify.comm` / `config:attention.notify.target` name
where bulletins go (provider walls exhausting their
whole fallback chain, weather suspensions, continuation exhaustion, and
similar) — throttled to roughly one per hour per session/provider so a
recurring problem doesn't flood the channel. `attention.break-glass` is
declared in the schema but not wired to anything yet — setting it today
has no effect. `[verify]`

### Troubleshooting

- **`comm__send` refuses with "unknown comm slot".** The slot name doesn't
  match any entry in the `comms` table — check `config get comms` for
  what's actually configured, not just what's installed.
- **A queued message never seems to arrive.** Check whether it's still
  retrying (transient failure, backing off) or has been dead-lettered
  (permanent failure, or five exhausted retries) — the delivery worker
  doesn't silently drop anything, but it also doesn't retry forever.
- **Attention bulletins aren't showing up anywhere.** Confirm
  `attention.notify` is actually set — an unconfigured attention target
  logs a warning internally instead of failing, so a missing bulletin
  often just means nobody ever pointed it anywhere.
