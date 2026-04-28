## 1. Command Wiring and Discovery

- [x] 1.1 Add `issue pick` subcommand registration and CLI help/usage text.
- [x] 1.2 Implement issue workspace discovery using `issue.yaml` as the required metadata source.
- [x] 1.3 Add validation/fallback handling for missing or malformed `issue.yaml` entries.

## 2. Picker Ranking and Selection Flow

- [x] 2.1 Implement recency persistence and ranking logic for issue candidates.
- [x] 2.2 Connect ranked candidates to the fuzzy finder so the highest-ranked entry is preselected by default.
- [x] 2.3 Ensure immediate `RET` acceptance opens the preselected top-ranked candidate.

## 3. Legacy Picker Parity

- [x] 3.1 Compare `issue pick` behavior with legacy `issue-term` flow for core interactions.
- [x] 3.2 Align filtering, labeling, and selection UX where parity is desirable.

## 4. Tests and Verification

- [x] 4.1 Add unit tests for `issue.yaml`-based discovery, including exclusion of `config.yaml`-only workspaces.
- [x] 4.2 Add unit tests for recency ordering and default preselection behavior.
- [x] 4.3 Add tests for `RET` immediate selection behavior.
- [x] 4.4 Run the relevant test suite and confirm `issue pick` behavior from a representative workspace.
