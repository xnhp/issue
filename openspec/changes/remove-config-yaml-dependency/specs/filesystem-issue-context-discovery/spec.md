## ADDED Requirements

### Requirement: Dedicated Issue Metadata File
The CLI MUST use `issue.yaml` as the dedicated issue metadata file, independent of PDE `config.yaml` requirements.

#### Scenario: `issue new` writes minimal metadata
- **WHEN** a user runs `issue new <issue-id>`
- **THEN** the created issue directory SHALL contain `issue.yaml`
- **AND** `issue.yaml` SHALL contain exactly the required metadata keys `id` and `branch`
- **AND** the file writer SHALL output keys in preferred order: `id`, then `branch`

#### Scenario: Metadata parsing is key-order independent
- **WHEN** `issue.yaml` contains valid `id` and `branch` keys in any YAML key order
- **THEN** runtime context resolution SHALL parse values correctly without relying on key order

#### Scenario: Config file is not used for metadata
- **WHEN** runtime issue context is resolved
- **THEN** the CLI SHALL NOT read `issueId` or `branch` from `config.yaml`

### Requirement: Deterministic Metadata Derivation
The CLI SHALL derive issue context with a deterministic source: `ISSUE_DIR` from filesystem discovery, and `ISSUE_ID`/`ISSUE_BRANCH` from `issue.yaml`.

#### Scenario: Metadata resolves from issue file
- **WHEN** an issue workspace contains a valid `issue.yaml`
- **THEN** the CLI SHALL set `ISSUE_ID` to `issue.yaml.id`
- **AND** it SHALL set `ISSUE_BRANCH` to `issue.yaml.branch`
- **AND** it SHALL set `ISSUE_DIR` to the detected issue root directory

#### Scenario: Missing or invalid issue file fails clearly
- **WHEN** `issue.yaml` is missing or does not contain valid `id` and `branch` values
- **THEN** the CLI SHALL fail with an actionable error message that identifies the missing/invalid metadata

### Requirement: Obsolete Config-Driven Behavior Removal
The CLI MUST remove obsolete functionality, code paths, and tests that depend on reading `config.yaml` content for runtime issue context.

#### Scenario: Deprecated config-content code path is removed
- **WHEN** the codebase is built and tested after this change
- **THEN** there SHALL be no active runtime path that reads `config.yaml` keys to compute `ISSUE_ID`, `ISSUE_DIR`, or `ISSUE_BRANCH`

#### Scenario: Obsolete tests are removed or replaced
- **WHEN** test suites are updated for this change
- **THEN** tests that assert config-content-driven context behavior SHALL be removed or replaced
- **AND** tests SHALL validate filesystem-and-`issue.yaml`-based resolution behavior instead

### Requirement: Remove Legacy Template And PDE-Specific Artifacts
The CLI MUST remove legacy YAML templates and PDE-specific scaffold files that are only required by the old config-driven flow.

#### Scenario: Legacy template is not used or shipped
- **WHEN** `issue new` executes after this change
- **THEN** it SHALL NOT require `config.template.yaml`
- **AND** repository scaffolding SHALL NOT include obsolete template dependencies for issue metadata generation

#### Scenario: Deprecated PDE-specific scaffolding is removed
- **WHEN** issue initialization is executed after this change
- **THEN** PDE-specific files/directories that are not required for the new issue workflow SHALL NOT be created
- **AND** tests SHALL assert that removed artifacts are absent

### Requirement: CLI-Core Logging And Output Formatting
The CLI MUST use logging and output formatting facilities from `cli-core` for user-facing status, warning, and error output in the changed command paths.

#### Scenario: Warnings use standardized formatting
- **WHEN** the CLI emits warnings related to missing/invalid `issue.yaml` or removed legacy artifacts
- **THEN** warnings SHALL be produced via `cli-core` facilities
- **AND** warning style SHALL match other commands that use `cli-core`

#### Scenario: Success and status output use standardized formatting
- **WHEN** commands such as `issue new` report created files or status
- **THEN** output SHALL use `cli-core` formatting helpers instead of ad hoc `println`/manual prefixes
