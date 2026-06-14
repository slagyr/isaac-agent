# AGENTS.md

Micah's AI assistant management tools.

## Toolbox

This project uses [toolbox](https://raw.githubusercontent.com/slagyr/toolbox/main/SKILL.md)
to manage agent components. If `.toolbox/` doesn't exist, fetch the toolbox
SKILL.md from the URL above and follow its instructions. Once bootstrapped:

- **Project context:** See [`ISAAC.md`](ISAAC.md) for vocabulary, working-with-Micah, project knowledge, and Isaac-specific traps.
- **Gherkin tables:** See `features/TABLES.md` for the canonical table dialect used by gherclj step definitions.
- **Skills:** Load from `.toolbox/skills/{name}/SKILL.md` when their descriptions match the task at hand.
- **Commands:** When the user invokes a command by name (e.g., "/work"), read and follow `.toolbox/commands/{name}.md`.
- **Fresh checkout setup:** Run `bb hooks:install` once so git uses the repo-tracked pre-push hook.

### Skills

- [tdd](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/tdd/SKILL.md)
- [refactor](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/refactor/SKILL.md)
- [smells](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/smells/SKILL.md)
- [architecture](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/architecture/SKILL.md)
- [logging](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/logging/SKILL.md)
- [beans-multi-worker](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/beans-multi-worker/SKILL.md)
- [crap4clj](https://raw.githubusercontent.com/unclebob/crap4clj/master/SKILL.md)
- [dry4clj](https://raw.githubusercontent.com/slagyr/dry4clj/refs/heads/master/SKILL.md)
- [clj-mutate](https://raw.githubusercontent.com/slagyr/clj-mutate/master/SKILL.md)
- [scrap](https://raw.githubusercontent.com/slagyr/scrap/main/SKILL.md)
- [gherclj](https://raw.githubusercontent.com/slagyr/gherclj/refs/heads/master/SKILL.md)
- [gherkin](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/gherkin/SKILL.md)
- [clojure](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/clojure/SKILL.md)
- [c3kit](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/c3kit/SKILL.md)
- [c3kit-schema](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/c3kit-schema/SKILL.md)

### Commands

- [plan](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/plan.md)
- [todo](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/todo.md)
- [work](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/work.md)
- [plan-with-features](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/plan-with-features.md)
- [verify](https://raw.githubusercontent.com/slagyr/agent-lib/main/commands/verify.md)

## Bean Workflow

This project uses verification. Workers leave beans `in-progress` and
add `tag=unverified` when implementation is finished. Workers do **not**
mark beans `completed`. A separate reviewer runs `/verify` to check
acceptance criteria, then either marks the bean `completed` or returns
it to normal work, removing the tag in either case.

**Status flow:** `todo` → `in-progress` → `in-progress + tag=unverified` → `completed`

If verification fails, the bean returns to `in-progress` with notes appended to the body.

**Worker rule:** implementation handoff is `beans update <id> --tag=unverified`
while the bean stays `status=in-progress`. `completed` is verifier-only.

## Parallel-Worker Sync

Multiple worker checkouts (`isaac-main`, `isaac-worker-1`, ...) run in
parallel. Each one's view of source and beans is stale by default. The
cost of skipping a sync at a handoff point is silent divergence: a
verifier reviewing stale source, a worker missing reviewer notes, an
agent claiming "I don't see that bean" or "I don't have that code."

Beans live as plain markdown under `.beans/`, so `git pull` brings both
source and bean state. There is no separate sync command.

**Rule:** *Before acting on another worker's output, pull. After
producing your own, push.*

### Session start — always pull

First action in any new session, before any other work:

```bash
git pull --rebase
```

Without this, you'll reason about stale code and stale beans. Common
symptoms: "I don't see that bean," "I don't have that code," or
recommending a fix that already shipped.

### Push after every bean write

Pushes are cheap. They prevent stranding state where another worker
can't see it.

- After `beans create`, `beans update`, `beans archive`, `beans delete`
  → `git add .beans/ && git commit -m "..." && git push`.
- The bean change usually rides with the related code commit. Claiming a
  bean (`status=in-progress`) is a fine standalone commit.

### Pull at handoff points

Beyond session start, pulls are situational — only when you're about
to act on what someone else produced. Do **not** pull on every
`beans show` / `beans list`.

- **Verification** — before `/verify` or otherwise reviewing a bean
  tagged `unverified`: `git pull --rebase` before reading source or
  bean state. One pull covers both.

- **Resuming after external change** — told "the bean was reopened",
  "verifier left notes", "user closed a dependency", or any signal
  another actor touched your bean since you last looked: `git pull
  --rebase` before `beans show <id>`.

See the [beans-multi-worker skill](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/beans-multi-worker/SKILL.md)
for the full canonical reference.

## Testing Discipline

Every namespace in `src/` must have a corresponding spec in `spec/`.
Features test user-visible behavior; specs test implementation.
**Both are required.**

- No production code without a failing unit test first (TDD)
- Feature scenarios are NOT a substitute for unit specs
- A bean is NOT complete if new `src/` namespaces lack corresponding `spec/` files
- Run `bb spec` and `bb features` before closing any bean — both must pass

### Push Enforcement

Tests run automatically on push via the repo-tracked pre-push hook.
The hook short-circuits on doc-only changes. On `.clj`, `.cljs`,
`.cljc`, `.feature`, or `.edn` changes it runs `bb verify` and rejects
the push if anything is red.

If you bypass the hook (`--no-verify` or hook not installed), CI on
`main` runs the same suite and fails the run. Check `gh run list` (or
the project's notification channel) after a push to see CI status.

On a fresh checkout: `bb hooks:install`.

Implication: never push code/test changes without running `bb verify`
yourself or letting the hook run it. The work-session handoff assumes
the hook will run; bypassing it creates breakage your teammates have
to chase down.

### Fast Lint Before Spec

**After editing a Clojure file, run `bb lint <file>` before `bb spec`.**
It runs clj-kondo in under 300ms and catches paren/bracket mismatches
and syntax errors before paying the cost of loading the full project
for specs.

```bash
bb lint src/isaac/foo.clj   # lint one file (~50ms)
bb lint src/isaac/foo/      # lint a directory
bb lint                     # lint all of src/ and spec/ (~1-2s)
```

`bb lint` exits 0 on success, 1 on errors. Use it as the fast pre-spec gate:
1. Edit → `bb lint <file>` → fix syntax if needed → `bb spec` → fix logic → `bb features`

### No Fixed Sleeps in Specs

Use `(isaac.spec-helper/await-condition pred)` instead of `Thread/sleep` —
polls every 1ms for up to 1 second. See the
[tdd skill](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/tdd/SKILL.md#polling-instead-of-sleeping)
for the general pattern. `app_spec.clj` "preserves the previous config
when reload fails validation" has a worked example of the wrap-and-count
pattern for negative assertions.

For ACP proxy specs, always set `:acp-proxy-eof-grace-ms 0` in test opts.

## Logging Discipline

See the [logging skill](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/logging/SKILL.md)
for principles. The registered info+ events table is in
[ISAAC.md](ISAAC.md#logging--registered-info-events).

## c3kit Schema Discipline

When working in this project with `c3kit.apron.schema`, load and follow the
[c3kit-schema skill](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/c3kit-schema/SKILL.md).

- For boundary and config validation, prefer `schema/conform!`
- Never ignore the return value of non-bang schema functions such as
  `schema/validate`, `schema/coerce`, `schema/conform`, or `schema/present`
- If using a non-bang schema function, explicitly check `schema/error?`
  and handle failures
- Use manual validation only for semantic or cross-field rules after
  schema conformance

## Beans Issue Tracker

This project uses **beans** for issue tracking. Beans live as plain markdown under `.beans/`, version-controlled with the code. Run `beans prime` for the canonical agent-priming output.

### Quick Reference

```bash
beans list --ready                     # Find available work
beans show <id>                        # View bean details
beans update <id> --status=in-progress # Claim work
beans update <id> --tag=unverified     # Hand off to /verify; keep status=in-progress
```

### Multi-machine sync

`git pull` brings both source and bean state. `git push` sends both. No separate sync command. See the [beans-multi-worker skill](https://raw.githubusercontent.com/slagyr/agent-lib/main/skills/beans-multi-worker/SKILL.md) for session-close discipline and git-conflict resolution on bean files.

### Rules

- All coding performed in this project must be either: (A) specified by an existing bean, or (B) explicitly requested or authorized by the user.
- Use `beans` for existing bean work and for new work only when the user explicitly asks for bean tracking or approves creating one. If no bean exists for requested work, ask before creating one. Do NOT use TodoWrite, TaskCreate, or markdown TODO lists.
- Persistent knowledge lives in `.memories/<slug>.md` — plain markdown files outside the bean tracker. Do NOT use MEMORY.md files.

### Task Continuity

- Keep exactly one active task at a time.
- Do not switch to a different task unless the user explicitly says to switch.
- If the user asks a side question while work is in progress, answer it and then resume the current task unless the user explicitly redirects the work.
- If a new user message might replace the current task, ask for clarification instead of assuming.
- Before starting substantial new work after a context shift, restate the current active task and whether it has changed.
- When asked about prior requests or task history, read from the transcript or repo instructions directly instead of reconstructing from memory.
