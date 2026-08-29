#!/usr/bin/env bash
# PreToolUse (Write|Edit): block writes to the legacy app and writes containing likely secrets.
set -uo pipefail

payload=$(cat)
file=$(printf '%s' "$payload" | jq -r '.tool_input.file_path // empty')
[ -z "$file" ] && exit 0

deny() {
  jq -nc --arg r "$1" \
    '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$r}}'
  exit 0
}

rel="${file#"${CLAUDE_PROJECT_DIR:-}"/}"

# Recreating the deleted MERN app would silently reintroduce what ADR-0015 removed.
case "$rel" in
  client/*|server/*)
    deny "'$rel' belongs to the original MERN app, which was deleted in favour of a clean-slate rewrite (ADR-0015). Angular goes in frontend/, Spring in backend/. If you genuinely need to see what the old code did, read docs/domain/legacy-app.md." ;;
esac

# Likely hardcoded secrets. Placeholders (\${VAR}, <...>, changeme, example) are allowed.
content=$(printf '%s' "$payload" | jq -r '[.tool_input.content, .tool_input.new_string] | map(select(. != null)) | join("\n")')
[ -z "$content" ] && exit 0

scan=$(printf '%s' "$content" | grep -vE '\$\{|\$[A-Z_]+|<[A-Za-z_-]+>|changeme|CHANGEME|your[-_]|example|EXAMPLE|placeholder|xxxx|\.\.\.')

hit=""
printf '%s' "$scan" | grep -qE -- '-----BEGIN [A-Z ]*PRIVATE KEY-----' && hit="a private key block"
printf '%s' "$scan" | grep -qE 'AKIA[0-9A-Z]{16}' && hit="an AWS access key id"
printf '%s' "$scan" | grep -qE '(gh[pousr]_[A-Za-z0-9]{20,}|xox[abprs]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9]{20,})' && hit="a provider API token"
printf '%s' "$scan" | grep -qiE '(password|passwd|secret|api[_-]?key|access[_-]?token|client[_-]?secret)[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"']{8,}["'"'"']' && hit="a hardcoded credential assignment"
printf '%s' "$scan" | grep -qE '(jdbc|postgres(ql)?|mysql|mongodb)(\+srv)?://[^[:space:]:/]+:[^[:space:]@]{4,}@' && hit="a connection string with an embedded password"

[ -n "$hit" ] && deny "This write appears to contain $hit. Secrets never go in the repository — configuration comes from environment variables with no fallback default (see docs/architecture/security-model.md). If this is a false positive, use a \${ENV_VAR} placeholder or a clearly fake value."

exit 0
