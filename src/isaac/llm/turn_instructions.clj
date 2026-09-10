(ns isaac.llm.turn-instructions
  "Standing per-turn instructions appended to the system prompt for all crews.")

(def parallel-tool-calls-hint
  (str "Tool discipline:\n"
       "- Batch independent tool calls (reads, greps, globs, separate files) in a single response. "
       "One call per response is the slow path.\n"
       "- Locate first with grep or glob, then read only the region you need.\n"
       "- Read a file once, in a window large enough to cover what you need; "
       "do not page through it in small slices, and do not re-read a file you just edited.\n"
       "- Load a skill once per turn; the copy in your context stays valid until the turn ends."))
