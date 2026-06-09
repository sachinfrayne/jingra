#!/usr/bin/env bash
set -euo pipefail

# Always operate from the project root, regardless of where this script is called from
cd "$(dirname "$0")/.."

if [[ ! -f AGENTS.md ]]; then
  echo "error: AGENTS.md not found in $(pwd)" >&2
  exit 1
fi

rm -f CLAUDE.md
ln -sf AGENTS.md CLAUDE.md
mkdir -p .cursor/rules

rm -f .cursor/rules/jingra-project.mdc
ln -sf ../../AGENTS.md .cursor/rules/jingra-project.mdc

echo "OK: CLAUDE.md -> AGENTS.md"
echo "OK: .cursor/rules/jingra-project.mdc -> AGENTS.md"

ls -la CLAUDE.md .cursor/rules/jingra-project.mdc
