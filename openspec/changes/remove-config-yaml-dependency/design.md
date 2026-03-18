## Context

Issue workspaces currently use `config.yaml` as the metadata source, and `issue new` writes that file from `config.template.yaml`. This mixes PDE provisioning fields with issue metadata and couples runtime behavior to an oversized schema.

The change introduces a dedicated `issue.yaml` metadata file with only `id` and `branch`. Runtime context uses filesystem discovery for `ISSUE_DIR` and reads `ISSUE_ID`/`ISSUE_BRANCH` from `issue.yaml`.

## Goals / Non-Goals

**Goals:**
- Resolve issue context without parsing `config.yaml` content.
- Define a minimal `issue.yaml` schema containing only `id` and `branch`.
- Resolve `ISSUE_DIR` from filesystem and resolve `ISSUE_ID`/`ISSUE_BRANCH` from `issue.yaml`.
- Keep behavior predictable with explicit fallback order and clear failure modes.
- Remove obsolete config-based resolution code paths, tests, and compatibility shims.
- Remove legacy YAML template inputs and PDE-specific scaffold files from `issue new` outputs.

**Non-Goals:**
- Redesign the whole issue directory structure.
- Replace PDE provisioning configuration behavior beyond issue metadata.
- Infer arbitrary metadata beyond issue identity and branch context.

## Decisions

1. Introduce `issue.yaml` as dedicated metadata contract
- Define `issue.yaml` with exactly two required keys: `id` and `branch`.
- Update `issue new <issue-id>` to create `issue.yaml` and stop writing issue metadata to `config.yaml`.
- Write `issue.yaml` keys in preferred order (`id`, then `branch`) for readability, but parse keys order-independently.
- Rationale: explicit minimal schema matches the runtime contract and decouples from PDE template concerns.
- Alternative considered: keep using `config.yaml` with reduced fields. Rejected because the filename and surrounding expectations remain tied to PDE configuration semantics.

2. Metadata resolution precedence
- `ISSUE_DIR` is derived from filesystem issue-root discovery.
- `ISSUE_ID` and `ISSUE_BRANCH` are read from `issue.yaml`.
- If `issue.yaml` is missing or invalid, fail with an actionable error instead of silently inferring from unrelated config.
- Rationale: strict contract reduces ambiguity and prevents accidental drift.
- Alternative considered: infer from git branch as fallback. Rejected to keep a single source of truth.

3. Remove config-content compatibility behavior
- Remove code paths that parse `config.yaml` values (`issueId`, `branch`) for runtime context.
- Remove related compatibility shims and tests that validate config-content-driven behavior.
- Rationale: the new contract is filesystem + `issue.yaml`; keeping compatibility logic adds complexity with no intended value.
- Alternative considered: keep partial compatibility during transition. Rejected per scope decision to remove obsolete behavior.

4. Remove PDE-specific scaffolding artifacts
- Remove `config.template.yaml` as an input to `issue new`.
- Stop generating PDE-oriented scaffold files/directories as part of issue initialization where they are only needed by the old flow.
- Remove obsolete code paths and tests that assert generation or use of those PDE-specific files.
- Rationale: the issue workflow should only generate and depend on files required for issue execution (`issue.yaml` + repo state).

5. Use `cli-core` logging and output formatting
- Route user-facing diagnostics, warnings, and status messages through facilities provided by `cli-core` instead of ad hoc `println`/`stderr` formatting.
- Keep message styles and prefixes consistent with existing CLI conventions.
- Rationale: consistent UX and centralized formatting behavior across commands.

6. Observability and error messaging
- Emit actionable diagnostics when root discovery fails or branch metadata is ambiguous.
- Rationale: replacing config parsing with inference requires better operator feedback.

## Risks / Trade-offs

- [Missing metadata file] Existing issue directories without `issue.yaml` will fail context resolution -> Mitigation: fail with actionable errors that state required file and keys; do not add a migration command.
- [Invalid metadata file] Manual edits can produce malformed `issue.yaml` -> Mitigation: strict validation with actionable error messages naming missing/invalid keys.
- [Tooling expecting PDE files] External scripts may still reference removed template/scaffold files -> Mitigation: fail with clear messages when deprecated files are referenced.

## Migration Plan

1. Implement `issue.yaml` read/write model (`id`, `branch`) and validation.
2. Update `issue new` to generate `issue.yaml` with normalized ID and inferred branch.
3. Integrate filesystem + `issue.yaml` resolution into runtime initialization.
4. Remove `config.template.yaml` and PDE-specific scaffold generation paths no longer needed.
5. Remove config-content parsing logic and obsolete compatibility paths/tests.
6. Replace ad hoc output calls in affected command paths with `cli-core` logging/output facilities.
7. Ensure `issue.yaml` is written with preferred key order (`id`, `branch`) while parsing remains key-order independent.
8. Add tests for valid/missing/invalid `issue.yaml`, key-order-independent parsing, and absence of deprecated template/scaffold artifacts.
9. Rollback path: revert resolver integration to previous config-based logic if critical workflows break.

## Open Questions

- None.
