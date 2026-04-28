## Context

The CLI currently lacks a dedicated `issue pick` command for interactive issue workspace selection. An older helper script (`~/.local/bin/issue-term`) provides a fuzzy finder flow, but it is outdated because repository metadata has moved from `config.yaml` to `issue.yaml`. The new subcommand must preserve a fast keyboard workflow, prioritize recently used entries, and let users open the most likely item by pressing `RET` immediately.

## Goals / Non-Goals

**Goals:**
- Provide an `issue pick` subcommand that opens an interactive fuzzy picker for issue workspaces.
- Discover and display issue entries from current `issue.yaml`-based metadata.
- Sort entries with recency-first behavior and preselect the top-ranked entry.
- Keep behavior deterministic and testable with unit tests around ranking and default selection.

**Non-Goals:**
- Rewriting unrelated CLI subcommands.
- Changing workspace layout conventions beyond reading `issue.yaml`.
- Implementing a new full-screen terminal UI framework if existing picker plumbing can be reused.

## Decisions

1. Use a dedicated `issue pick` command entry in the existing CLI command tree.
   - Rationale: discoverable command structure and consistent help output.
   - Alternative considered: overloading existing default `issue` behavior; rejected because explicit subcommands are clearer and less risky.

2. Treat `issue.yaml` as the source of truth for issue metadata during discovery.
   - Rationale: aligns implementation with current project conventions and avoids drift from deprecated `config.yaml` usage.
   - Alternative considered: supporting both file names forever; rejected to avoid ambiguity and maintenance overhead.

3. Introduce a recency store abstraction used by the picker for ordering and default selection.
   - Rationale: isolates ranking logic from UI details and enables focused unit tests.
   - Alternative considered: embed ranking directly in picker callback; rejected because it is harder to test and evolve.

4. Preselect highest-ranked entry and allow immediate `RET` acceptance.
   - Rationale: directly satisfies the expected fast path in issue #1.
   - Alternative considered: no default selection; rejected because it adds unnecessary keystrokes.

5. Show issue ID and title in picker entries.
   - Rationale: users need immediate recognition of both issue key and human-readable context.
   - Alternative considered: show directory names only; rejected because naming conventions vary and are less reliable.

6. Keep recency ranking global across all issue workspaces.
   - Rationale: users often switch between repositories and still benefit from one shared recent list.
   - Alternative considered: per-repository recency; rejected because it hides frequently used issues when changing repos.

## Risks / Trade-offs

- [Risk] Incorrect parsing of `issue.yaml` fields across existing issue directories -> Mitigation: centralize parsing with validation and fallbacks; add fixture-based tests for representative files.
- [Risk] Recency ranking may mask less-used but relevant items -> Mitigation: keep fuzzy matching active so query relevance can still surface lower-ranked entries.
- [Risk] Persisted recency state can become stale or corrupt -> Mitigation: tolerate missing/invalid state and rebuild ranking from available directories.

## Migration Plan

1. Add `issue pick` command wiring and help text.
2. Implement issue discovery from `issue.yaml` and ranking with recency persistence.
3. Connect ranking output to fuzzy finder default selection.
4. Add and run unit tests for discovery, ranking, and default selection behavior.

Rollback strategy: remove command registration and keep existing command behavior unchanged if regressions are detected.

## Open Questions

- None.
