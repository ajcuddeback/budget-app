#!/usr/bin/env bash
# SessionStart: orient the agent without making it explore.
set -uo pipefail
cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0

branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")
changed=$(git status --porcelain 2>/dev/null | wc -l | tr -d ' ')

backend="not created"; [ -d backend ] && backend="present"
frontend="not created"; [ -d frontend ] && frontend="present"

features=$(ls docs/features/*.md 2>/dev/null | grep -vE "_TEMPLATE|README" | xargs -r -n1 basename | sed 's/\.md$//' | paste -sd', ' - )
[ -z "$features" ] && features="none yet"
adrs=$(ls docs/adr/[0-9]*.md 2>/dev/null | wc -l | tr -d ' ')

read -r -d '' CONTEXT <<CTX
Budget App — session orientation (from .claude/hooks/session-start.sh)

  branch: ${branch} | uncommitted files: ${changed}
  backend/: ${backend} | frontend/: ${frontend}
  feature docs: ${features}
  ADRs recorded: ${adrs}

Angular + Java 21/Spring Boot + PostgreSQL. Security is the top priority.
Clean slate: the original MERN app was deleted (ADR-0015). What it did is recorded in
docs/domain/legacy-app.md — read that, not git history.

Read CLAUDE.md before starting. It routes you to the right doc so you do not
re-explore the codebase. Do not survey files to answer a question docs/ already answers.

Before building a feature: open its doc in docs/features/, or create one with /feature-doc.
Before claiming done: run tools/verify.sh.
Record decisions with /adr, and anything surprising with /remember.
CTX

jq -nc --arg ctx "$CONTEXT" \
  '{hookSpecificOutput:{hookEventName:"SessionStart", additionalContext:$ctx}}'
