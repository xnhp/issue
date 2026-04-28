## ADDED Requirements

### Requirement: Issue pick subcommand is available
The CLI SHALL expose an `issue pick` subcommand that opens an interactive issue-selection flow.

#### Scenario: User invokes issue pick
- **WHEN** a user runs `issue pick`
- **THEN** the CLI opens a fuzzy finder populated with available issue workspaces

### Requirement: Picker discovers issue metadata from issue.yaml
The picker SHALL discover issue candidates from workspace directories that contain valid `issue.yaml` metadata and SHALL not require deprecated `config.yaml` files.

#### Scenario: Candidate workspace includes issue.yaml
- **WHEN** discovery scans an issue workspace containing `issue.yaml`
- **THEN** the workspace is included in picker candidates using metadata from `issue.yaml`

#### Scenario: Deprecated config.yaml exists without issue.yaml
- **WHEN** discovery finds a workspace that has only legacy `config.yaml`
- **THEN** that workspace is not treated as a valid picker candidate unless `issue.yaml` is also present

### Requirement: Picker entries show issue ID and title
The picker SHALL display each candidate with both issue ID and issue title.

#### Scenario: Candidate has id and title metadata
- **WHEN** the picker renders a candidate loaded from `issue.yaml`
- **THEN** the candidate label includes both the issue ID and title

### Requirement: Picker ranks recently selected items first
The picker SHALL order candidates so recently selected issue workspaces appear before less recently selected workspaces, using one global recency history.

#### Scenario: Prior selections are available
- **WHEN** the user opens the picker and recency history exists
- **THEN** candidates previously selected more recently appear earlier in the list

#### Scenario: Recent selections come from different repositories
- **WHEN** the global recency history contains selections across multiple repositories
- **THEN** ranking still orders candidates by that global recency regardless of repository

### Requirement: Top-ranked item is immediately selectable
The picker SHALL preselect the highest-ranked candidate so the user can accept it with a single `RET` keypress.

#### Scenario: User accepts default selection
- **WHEN** the picker opens and the user presses `RET` without changing the selection
- **THEN** the highest-ranked candidate is selected and opened
