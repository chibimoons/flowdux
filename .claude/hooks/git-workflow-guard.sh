#!/bin/bash
# Git Workflow Guard Hook
# PreToolUse hook for Bash tool — injects git workflow rules
# when git push, gh pr create, or git tag commands are detected.
#
# NOTE: matcher can only filter by tool_name ("Bash"), not by command content.
# So this script runs for every Bash call but exits early for non-git commands.
# The grep check below is the fastest possible filter (~1ms overhead).

INPUT=$(cat)

# Fast exit: check raw JSON for git keywords before parsing
if ! echo "$INPUT" | grep -qE '"git push|"gh pr create|"git tag'; then
  exit 0
fi

# Only parse with jq if we matched
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

if echo "$COMMAND" | grep -qE "git push|gh pr create|git tag"; then
  CWD=$(echo "$INPUT" | jq -r '.cwd // empty')
  RULES_FILE="${CWD:-.}/docs/dev/git-workflow.md"

  if [ -f "$RULES_FILE" ]; then
    echo "[Git Workflow Rules — 아래 규칙을 반드시 확인하세요]" >&2
    cat "$RULES_FILE" >&2
    echo "" >&2
  fi
fi

exit 0
