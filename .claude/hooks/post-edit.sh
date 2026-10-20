#!/usr/bin/env bash
# PostToolUse(Edit|Write|MultiEdit): format, then fast checks. Exit 2 feeds stderr back to Claude.
# NOTE: whole-project tsc/go vet per edit can be slow on big repos; switch to incremental builds if needed.
set -uo pipefail
f=$(jq -r '.tool_input.file_path // empty')
{ [ -z "$f" ] || [ ! -f "$f" ]; } && exit 0
case "$f" in
  *.ts|*.tsx|*.js|*.jsx|*.json|*.css|*.md|*.mdx)
    if [ -f package.json ] && command -v pnpm >/dev/null 2>&1; then
      pnpm -s prettier --write "$f" >/dev/null 2>&1 || true
      case "$f" in
        *.ts|*.tsx)
          out=$(pnpm -s tsc --noEmit 2>&1) || { printf '%s\n' "$out" | head -40 >&2; exit 2; } ;;
      esac
    fi ;;
  *.go)
    command -v gofmt >/dev/null 2>&1 && gofmt -w "$f"
    if [ -f go.mod ] || [ -f api/go.mod ]; then
      dir=.; [ -f api/go.mod ] && dir=api
      out=$(cd "$dir" && go vet ./... 2>&1) || { printf '%s\n' "$out" | head -40 >&2; exit 2; }
    fi ;;
esac
exit 0
