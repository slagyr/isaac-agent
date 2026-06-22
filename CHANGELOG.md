# Changelog

## Unreleased

### Breaking

- Default prompt discovery roots moved from `<isaac-root>/config/{commands,skills,rules}` to `<isaac-root>/prompts/{commands,skills,rules}`. Project-layer prompts likewise live under `<project-root>/prompts/` instead of `<project-root>/.isaac/`. There is no legacy fallback under `config/` — move existing prompt files on upgrade. Configurable extra roots (`:prompt-paths`, `:command-paths`, `:skill-paths`) are unchanged.