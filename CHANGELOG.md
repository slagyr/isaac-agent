# Changelog

## Unreleased

### Breaking

- Default prompt discovery roots moved from `<isaac-root>/config/{commands,skills,rules}` to `<isaac-root>/prompts/{commands,skills,rules}`. Project-layer prompts live under `<project-root>/.isaac/prompts/`; boot files (`AGENTS.md`) load from the same discovered project root (walk up from session cwd). There is no legacy fallback under `config/` or `<project-root>/prompts/` — move existing prompt files on upgrade. Configurable extra roots (`:prompt-paths`, `:command-paths`, `:skill-paths`) are unchanged.