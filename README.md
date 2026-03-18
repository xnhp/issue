# issue

Tools for working within a single issue directory. Each issue directory contains an `issue.yaml`, a `config.yaml` for repo setup, working copies of repos, and optional IntelliJ project files.

## Zsh auto-env hook

This hook sets `ISSUE_ID`, `ISSUE_DIR`, and `ISSUE_BRANCH` when you `cd` into an issue directory (or any of its subdirectories). It reads `id` and `branch` from `issue.yaml` without invoking the JVM for speed.

Add this to your `~/.zshrc`:

```zsh
autoload -U add-zsh-hook
_issue_auto_env() {
    local dir="$PWD"
    local root=""
    while [[ "$dir" != "/" ]]; do
        if [[ -f "$dir/issue.yaml" ]]; then
            root="$dir"
            break
        fi
        dir="${dir%/*}"
        [[ -z "$dir" ]] && dir="/"
    done
    if [[ -n "$root" ]]; then
        local issue_id
        local branch_name
        issue_id="$(awk -F: '/^[[:space:]]*id[[:space:]]*:/ {sub(/^[^:]*:[[:space:]]*/, "", $0); gsub(/^[\"'\'']|[\"'\'']$/, "", $0); print $0; exit}' "$root/issue.yaml")"
        branch_name="$(awk -F: '/^[[:space:]]*branch[[:space:]]*:/ {sub(/^[^:]*:[[:space:]]*/, "", $0); gsub(/^[\"'\'']|[\"'\'']$/, "", $0); print $0; exit}' "$root/issue.yaml")"
        export ISSUE_ID="$issue_id"
        export ISSUE_BRANCH="$branch_name"
        export ISSUE_DIR="$root"
    else
        unset ISSUE_ID ISSUE_BRANCH ISSUE_DIR
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
