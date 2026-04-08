# issue

Tooling and configuration to help with keeping one directory per to-do / issue.

This contains the minimal functionality for exactly this responsibility. More sophisticated behaviour
can then be built my separate utilities on top of it.

An _issue directory_ is marked by an `issue.yaml` containing some very basic metadata:
```
➜ issue new NXT-4622
Created issue metadata at /home/ben/Desktop/issues/todo_NXT-4622-executor-to-serve-web-resources-dire/issue.yaml

➜ cd todo_NXT-4622-executor-to-serve-web-resources-dire && ls
.rw-rw-r-- 126 ben 31 Mar 12:12 issue.yaml

➜ cat issue.yaml
id: 'NXT-4622'
branch: 'todo/NXT-4622-executor-to-serve-web-resources-dire'
title: 'Executor to serve web resources directly'
```

## Agent Skill

You can set up an extremely simple [skill instruction](skills/issue-workflow/SKILL.md) to inform an agent of your choice of the idea:

```
---
name: issue-workflow
description: Organize and operate within per-issue working directories that bundle sparse repo checkouts, feature branches, and local config files (e.g., config.yaml) tying repos, bundles, or launch configs together. Use when you see or are asked about an issue-focused folder structure (often the cwd) containing per-issue repo checkouts or worktrees such as knime-*, or when setting up/maintaining that structure.
---

# Issue Workflow

## Overview

Recognize and work [...]

```

This is usually enough to steer the agent to interpret the directory as intended.


## Picker

Arguably the most useful thing is an ergonomic picker:

```
➜ i          # my alias for `issue pick ~/Desktop/issues/`
Issue >
  8/8 ───────────────────────────────────────────────────────────────────────────────────────────────
> NXT-4479 Detail page for components first iteration
  NXT-4460 Indicate if user is not viewing the most recent version (including drafts)
  NXT-4624 Use KdsLink on project detail page
  PA-56 Container Navigation
  NXT-4606 Clarify background colours & spaces for Workflow/Component Detail Pages
  NXT-4611 Wrap nodes in metanode / component
  NXT-4581 Remove \
  NXT-4622 Executor to serve web resources directly
```

ordered by most-recently-used, displays metadata from `issue.yaml`. 

Press `RET` to select an issue and optionally a child directory:

```
➜ 12:32 ben enh_NXT-4479-component-detail-page i
Path >
  12/12 ──────────────────────────────────────────────────────────────────────────────────────────────
> .
  .work/
  knime-designsystem_NXT-4479-component-detail-page/
  knime-hub-webapp_NXT-4479-component-detail-page/
  issue.yaml
  kdslink-resolver-follow-up.md
  notes.md
  pr-feedback.workflow.yaml
```

This will print the full path of the selected folder/file to stdout.


<details>
<summary>
If you want to directly `cd` to a dir or open your `$EDITOR` on a file, 
some indirection is required, e.g. for `zsh`:
</summary>

```
issue-cd() {
  local target_path
  target_path="$(issue pick "$HOME/Desktop/issues")" || return
  [[ -n "$target_path" ]] || return 0

  if [[ -d "$target_path" ]]; then
    builtin cd -- "$target_path"
    return 0
  fi

  if [[ -f "$target_path" ]]; then
    if [[ -z "${EDITOR:-}" ]]; then
      print -u2 -- "Not a directory and EDITOR is unset: $target_path"
      return 1
    fi
    local -a editor_cmd
    editor_cmd=(${(z)EDITOR})
    "${editor_cmd[@]}" -- "$target_path"
    return $?
  fi

  print -u2 -- "Selected path does not exist: $target_path"
  return 1
}

```

Invoke the picker even faster by configuring a keybinding to launch your
terminal directly into this command, e.g. an `i3` for super+i:
```
bindsym $mod+i exec --no-startup-id "alacritty -e zsh -ic 'issue-cd; exec zsh -i'"
```

</details>


## Other convenience commands


We have some convenience commands to work within an issue:
```
➜ issue worktrees "git checkout -b $(issue read branch)"
knime-designsystem
Switched to a new branch 'enh/NXT-4479-detail-page-for-components-first-iter'
knime-hub-webapp
Switched to a new branch 'enh/NXT-4479-detail-page-for-components-first-iter'
```
