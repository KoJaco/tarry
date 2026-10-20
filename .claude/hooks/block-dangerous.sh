#!/usr/bin/env bash
# PreToolUse(Bash) guard. Exit code 2 = block; stderr is shown to Claude.
# Customise patterns in blocked-patterns.txt (extended regex, one per line, case-insensitive).
set -euo pipefail
if ! command -v jq >/dev/null 2>&1; then
  echo "BLOCKED: jq is required by .claude/hooks/block-dangerous.sh but is not installed (guard fails closed). Install jq." >&2
  exit 2
fi
cmd=$(jq -r '.tool_input.command // empty')
[ -z "$cmd" ] && exit 0
file="$(cd "$(dirname "$0")" && pwd)/blocked-patterns.txt"
while IFS= read -r pat; do
  [[ -z "$pat" || "$pat" == \#* ]] && continue
  if printf '%s' "$cmd" | grep -Eiq -- "$pat"; then
    echo "BLOCKED by .claude/hooks/blocked-patterns.txt (pattern: $pat). Agents may only touch dev/staging. Production changes go through CI with human approval. Propose a safer command or ask the user." >&2
    exit 2
  fi
done < "$file"
exit 0
