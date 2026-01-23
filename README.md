# issue

Tools for working within a single issue directory. Each issue directory contains a `config.yaml`, working copies of repos, and optional IntelliJ project files.

## Zsh auto-env hook

This hook sets `ISSUE_ID`, `ISSUE_DIR`, `ISSUE_CONFIG`, and `ISSUE_BRANCH` when you `cd` into an issue directory (or any of its subdirectories). It parses `issueId` and `branchName` from `config.yaml` without invoking the JVM for speed.

Add this to your `~/.zshrc`:

```zsh
autoload -U add-zsh-hook
_issue_auto_env() {
    local dir="$PWD"
    local root=""
    while [[ "$dir" != "/" ]]; do
        if [[ -f "$dir/config.yaml" ]]; then
            root="$dir"
            break
        fi
        dir="${dir%/*}"
        [[ -z "$dir" ]] && dir="/"
    done
    if [[ -n "$root" ]]; then
        local issue_id
        local branch_name
        issue_id="$(awk -F: '/^[[:space:]]*issueId[[:space:]]*:/ {sub(/^[^:]*:[[:space:]]*/, "", $0); gsub(/^[\"'\'']|[\"'\'']$/, "", $0); print $0; exit}' "$root/config.yaml")"
        branch_name="$(awk -F: '/^[[:space:]]*branchName[[:space:]]*:/ {sub(/^[^:]*:[[:space:]]*/, "", $0); gsub(/^[\"'\'']|[\"'\'']$/, "", $0); print $0; exit}' "$root/config.yaml")"
        export ISSUE_ID="$issue_id"
        export ISSUE_BRANCH="$branch_name"
        export ISSUE_DIR="$root"
        export ISSUE_CONFIG="$root/config.yaml"
    else
        unset ISSUE_ID ISSUE_BRANCH ISSUE_DIR ISSUE_CONFIG
    fi
}
add-zsh-hook chpwd _issue_auto_env
_issue_auto_env
```

## Zsh completions

Copy the completion script into your default Zsh completion directory and ensure `fpath` includes it:

```zsh
mkdir -p ~/.zsh/completions
cp completions/_issue ~/.zsh/completions/_issue

fpath=(~/.zsh/completions $fpath)
autoload -Uz compinit
compinit
```
