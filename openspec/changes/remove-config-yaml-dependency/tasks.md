## 1. Filesystem Context Resolver

- [x] 1.1 Implement `issue.yaml` model and parser with required `id` and `branch` validation and key-order-independent parsing.
- [x] 1.2 Implement filesystem-based issue root discovery that locates the issue directory via `issue.yaml`.
- [x] 1.3 Wire runtime environment resolution to set `ISSUE_DIR` from filesystem and `ISSUE_ID`/`ISSUE_BRANCH` from `issue.yaml`.
- [x] 1.4 Remove any remaining runtime code paths that read `config.yaml` keys for issue context values.

## 2. Integration and Cleanup

- [x] 2.1 Update `issue new <issue-id>` to generate `issue.yaml` with normalized ID and inferred branch, written in key order `id`, then `branch`.
- [x] 2.2 Remove `issueId`/`branch` initialization in `config.yaml` generation paths.
- [x] 2.3 Remove obsolete compatibility helpers and dead code related to config-content parsing.
- [x] 2.4 Add user-facing diagnostics for unresolved or ambiguous context without failing non-interactive workflows.
- [x] 2.5 Replace ad hoc output and warning calls in affected paths with `cli-core` logging/output formatting facilities.

## 3. Verification

- [x] 3.1 Add/adjust unit tests for `issue.yaml` parsing/validation and issue root discovery.
- [x] 3.2 Remove or rewrite tests that depend on config-content-driven context behavior.
- [x] 3.3 Add/adjust integration tests for expected `ISSUE_ID`, `ISSUE_DIR`, and `ISSUE_BRANCH` behavior from `issue.yaml`.
- [x] 3.4 Add/adjust tests that assert standardized `cli-core` formatted warnings/status output on key paths.
- [x] 3.5 Run project tests and ensure they pass.
- [x] 3.6 Run `installDist` and verify the distribution build succeeds.
