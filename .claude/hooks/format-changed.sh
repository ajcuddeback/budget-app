#!/usr/bin/env bash
# PostToolUse (Write|Edit): format the changed file with the project's own tooling, if present.
set -uo pipefail
cd "${CLAUDE_PROJECT_DIR:-.}" 2>/dev/null || exit 0

file=$(jq -r '.tool_response.filePath // .tool_input.file_path // empty')
[ -z "$file" ] || [ ! -f "$file" ] && exit 0

case "$file" in
  *.java)
    [ -f backend/pom.xml ] && (cd backend && mvn -q -o spotless:apply "-DspotlessFiles=$(realpath --relative-to=backend "$file")" >/dev/null 2>&1)
    ;;
  *.ts|*.js|*.html|*.scss|*.css|*.json|*.md)
    if [ -x frontend/node_modules/.bin/prettier ]; then
      frontend/node_modules/.bin/prettier --write --ignore-unknown "$file" >/dev/null 2>&1
    fi
    ;;
esac
exit 0
