---
name: issue-workflow
description: Organize and operate within per-issue working directories that bundle sparse repo checkouts, feature branches, and local config files (e.g., config.yaml) tying repos, bundles, or launch configs together. Use when you see or are asked about an issue-focused folder structure (often the cwd) containing per-issue repo checkouts or worktrees such as knime-*, or when setting up/maintaining that structure.
---

# Issue Workflow

## Overview

Recognize and work within a single-issue directory that captures all context for a to-do/issue: sparse repo checkouts, feature branches, and local configuration that ties related pieces together.

## Identify the Issue Directory

- Treat the current working directory as the issue root when it contains multiple repo checkouts plus issue-specific config files.
- Expect repo folders to be sparse checkouts named like `knime-<repo>` and scoped to this issue only.
- Assume each repo is on a feature branch that corresponds to the issue unless evidence shows otherwise.

## Working Conventions

- Keep edits and commands scoped to the issue directory and its repos; avoid mixing with non-issue checkouts.
- When referencing paths, use the issue root as the anchor and keep repo-specific paths explicit.
- If a repo is missing or not on the expected branch, call it out and ask before changing branches or fetching.

## Configuration Files

- Look for `issue.yaml` (or similarly named files) in the issue root.
