## Why

The CLI currently relies on `config.yaml` content and schema to detect issue metadata, and `issue new` initializes that file from a PDE-oriented template. We need a minimal, dedicated issue metadata file so the issue workflow depends only on the properties it actually needs.

## What Changes

- Replace metadata dependency on `config.yaml` with a new `issue.yaml` contract.
- Update `issue new <issue-id>` to generate `issue.yaml` with only `id` and inferred `branch`.
- Resolve `ISSUE_DIR` from filesystem inspection and resolve `ISSUE_ID`/`ISSUE_BRANCH` from `issue.yaml`.
- Remove obsolete config-driven behavior, code, and tests.
- Remove the old YAML template and PDE-specific scaffolding files that are no longer part of the issue workflow.
- Standardize command output and diagnostics to use logging/output formatting facilities from `cli-core`.

## Capabilities

### New Capabilities
- `issue-metadata-file`: Define and use `issue.yaml` as the minimal source of issue metadata (`id`, `branch`) independent of PDE config requirements.

### Modified Capabilities
- None.

## Impact

- Affects `issue new` scaffolding and issue context/environment resolution.
- Changes behavior of commands and shell integration that consume `ISSUE_ID`, `ISSUE_DIR`, and `ISSUE_BRANCH`.
- Removes coupling to `config.yaml` schema for issue metadata while preserving a minimal metadata contract.
- Removes PDE-oriented templates/assets from repository scaffolding paths used by issue initialization.
