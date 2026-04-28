## Why

Selecting an issue context currently requires manual steps, which slows down workflows and makes it easy to pick the wrong issue directory. We need a fast, keyboard-first `issue pick` flow that reflects the new `issue.yaml`-based setup and improves the default selection behavior.

## What Changes

- Add a new `issue pick` subcommand that opens an interactive fuzzy finder for available issue workspaces.
- Rank and display recently selected items first so frequent contexts are easier to access.
- Make the most likely (most recently used) item preselected so pressing `RET` immediately opens it.
- Update picker data loading to use `issue.yaml` metadata rather than the old `config.yaml` assumptions from `~/.local/bin/issue-term`.

## Capabilities

### New Capabilities
- `issue-pick-command`: Interactive fuzzy issue selection with recency-aware ranking and immediate enter-to-select defaults.

### Modified Capabilities
- None.

## Impact

- Affected code: CLI command registration/dispatch, workspace discovery, interactive picker UI, and any persistence used for recent selections.
- Affected behavior: users can launch issue contexts through `issue pick` with faster defaults.
- External references: aligns behavior with notes in GitHub issue #1 (`improve picker`).
