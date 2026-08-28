#!/usr/bin/env bash
# PreToolUse (Bash): enforce the two project decisions a vendored third-party skill would
# otherwise talk an agent into breaking.
#
# The Angular skill legitimately recommends `ng new <app-name>` and `--ai-config`. Both are
# wrong HERE: ADR-0005 fixes the frontend path, and --ai-config writes a competing agent config
# that fights this harness's routing (see the override table in docs/guides/angular-style.md).
#
# Scope, deliberately narrow: this catches an agent running the skill's default command. It is
# NOT an adversarial control — the failure it prevents is a visible, reversible mistake, so
# precision beats coverage. Two earlier versions fired on prose *about* the rule: one on a
# heredoc writing this very documentation, one on a test payload containing a chained command.
# A guard that flags discussion of itself is worse than none — it trains people to route around
# it, and it can lock you out of editing the guard.
#
# So: only a line that BEGINS with the invocation counts. A mention mid-sentence, inside quotes,
# or in a heredoc body never does.
set -uo pipefail

cmd=$(jq -r '.tool_input.command // empty')
[ -z "$cmd" ] && exit 0

deny() {
  jq -nc --arg r "$1" \
    '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"deny",permissionDecisionReason:$r}}'
  exit 0
}

invocation=""
# Trailing newline matters: without it `read` returns non-zero on the final line and the loop
# body never runs for a single-line command, silently disabling the guard.
while IFS= read -r line; do
  line=$(printf '%s' "$line" | sed -E 's/^[[:space:]]+//; s/[[:space:]]+/ /g')
  line=$(printf '%s' "$line" | sed -E 's/^(sudo|command|time) //')
  # The common `cd somewhere && ng new ...` shape, stripped so the invocation is at the front.
  line=$(printf '%s' "$line" | sed -E 's/^cd [^&;]+ (&&|;) //')
  case "$line" in
    "ng new "* | "npx ng new "* | "yarn ng new "* | "pnpm ng new "* | \
    "npx -y @angular/cli"*" new "* | "npx @angular/cli"*" new "* | \
    "ng generate application "* | "npx ng generate application "*)
      invocation="$line"
      break
      ;;
  esac
done < <(printf '%s\n' "$cmd")

[ -z "$invocation" ] && exit 0

if printf '%s' "$invocation" | grep -qE -- '--ai-config[= ]' &&
  ! printf '%s' "$invocation" | grep -qE -- '--ai-config[= ]none'; then
  deny "Do not pass --ai-config to the Angular CLI. It writes a competing CLAUDE.md/AGENTS.md into frontend/ that fights this repo's harness routing — our agent config is CLAUDE.md at the repo root. Use --ai-config=none. See the override table in docs/guides/angular-style.md (ADR-0014)."
fi

if ! printf '%s' "$invocation" | grep -qE -- '(new|application) frontend( |$)|--directory[= ]frontend'; then
  deny "Angular apps in this repo are scaffolded as 'frontend/' and nowhere else (ADR-0005). Use 'frontend' as the app name, or pass --directory=frontend. See the override table in docs/guides/angular-style.md."
fi

exit 0
