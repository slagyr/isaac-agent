# Changelog

## Unreleased

### Added

- `isaac config keys` / `list` with no path list top-level resolved config keys (foundation pin) (isaac-rg61).
- `isaac episodes migrate-session` — materialize a session as a closed episode (scenes + gists) under `episodes/<crew>/<id>/` with configurable `:episodes {:gist-model ...}` (isaac-rxr4).
- migrate-session: line-format segmentation, provider-error abort, flagged-span `:raw`, 1-based span numbers (isaac-80pq).
- Optional embedding seam (`:embedding` config, Embedder protocol, ollama adapter, `isaac embed` CLI) for phase-1 recall (isaac-5lri).
- Episode scenes stored as `<scene-id>.md` (YAML frontmatter + distilled text body); `episode.edn` unchanged (isaac-lq7x).

### Breaking

- Default prompt discovery roots moved from `<isaac-root>/config/{commands,skills,rules}` to `<isaac-root>/prompts/{commands,skills,rules}`. Project-layer prompts live under `<project-root>/.isaac/prompts/`; boot files (`AGENTS.md`) load from the same discovered project root (walk up from session cwd). There is no legacy fallback under `config/` or `<project-root>/prompts/` — move existing prompt files on upgrade. Configurable extra roots (`:prompt-paths`, `:command-paths`, `:skill-paths`) are unchanged.