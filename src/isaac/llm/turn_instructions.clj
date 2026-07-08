(ns isaac.llm.turn-instructions
  "Standing per-turn instructions appended to the system prompt for all crews.")

(def parallel-tool-calls-hint
  "When tool calls are independent (reads, greps, separate files), batch them in a single response.")
