(ns isaac.agent.llm.turn-instructions
  "Standing per-turn instructions appended to the system prompt for all crews.")

(def parallel-tool-calls-hint
  ;; Wording follows the public coding-agent prompts that get batching
  ;; (Cursor, Cline, Codex CLI, OpenCode, Claude Code 2.0): a capability
  ;; statement, default-to-parallel with the one named exception, the cost in
  ;; round-trips, plan-the-batch, and a batch bound. (isaac-pn98)
  (str "Tool discipline:\n"
       "- You can call several tools in one response. Each response that carries a "
       "single call costs a full round-trip over the whole context, so before you call "
       "tools, identify every independent read, search, command, or edit the next step "
       "needs and emit all of them together now.\n"
       "- Default to parallel. Independent reads, greps, globs, and inspection commands "
       "go in one message, never one per turn. Speculatively batch files that are likely "
       "useful.\n"
       "- Run calls sequentially only when you need the output or side effect of one to "
       "decide the arguments of the next. Then wait, and batch the next round.\n"
       "- Keep a batch to about 3 to 5 calls. Do not split independent work across "
       "separate turns.\n"
       "- Locate first with grep or glob, then read only the region you need. Read a "
       "file once, in a window large enough to cover what you need; do not page through "
       "it in small slices, and do not re-read a file you just edited.\n"
       "- Load a skill once per turn; the copy in your context stays valid until the turn ends."))


(def subagent-delegation-hint
  ;; Included only when agent__spawn is in the turn's effective tool list, so
  ;; subagent children (which lose the spawn tools) never get it. (isaac-fnyp)
  (str "Delegating to subagents:\n"
       "- agent__spawn runs a task in a separate context and sends you its conclusion "
       "when it finishes. Use it for work that would flood your context: sweeps across "
       "many files, broad searches, research that reads a lot to answer a little.\n"
       "- Don't spawn for what a few tool calls would answer; do that yourself.\n"
       "- The child sees only your prompt. Brief it fully: the goal, what you already "
       "know or ruled out, where to look, and what to report back.\n"
       "- Fit the model to the work: a cheaper model for simple, mechanical tasks, a "
       "stronger one for hard reasoning.\n"
       "- Spawning doesn't block. Keep working, or end your turn and stay available to "
       "the person; the result arrives as its own message. Don't redo delegated work."))

(defn spawn-tool?
  "True when the tool list (definitions or names) includes agent__spawn."
  [tools]
  (boolean (some #(= "agent__spawn" (if (map? %) (:name %) (str %))) tools)))
