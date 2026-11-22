#!/usr/bin/env bash
# Drive the running UI, capture screenshots for agent review, and run the automated checks.
#
#   tools/ui-check.sh                     # against http://localhost:4200
#   tools/ui-check.sh --url http://host   # against any running instance
#   tools/ui-check.sh --selfcheck         # prove the harness works, no app needed
#   tools/ui-check.sh --serve             # start the Angular dev server first, stop it after
#   tools/ui-check.sh --grep login        # only specs matching a pattern
#
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
UI="$ROOT/tools/ui"

URL="${UI_BASE_URL:-http://localhost:4200}"
SELFCHECK=0; SERVE=0; GREP=""; HEADED=""
while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --selfcheck) SELFCHECK=1; shift ;;
    --serve) SERVE=1; shift ;;
    --grep) GREP="$2"; shift 2 ;;
    --headed) HEADED="--headed"; shift ;;
    -h|--help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [ ! -d "$UI/node_modules" ]; then
  echo "Installing UI harness dependencies (one time)..."
  (cd "$UI" && npm ci --no-audit --no-fund >/dev/null 2>&1 || npm install --no-audit --no-fund >/dev/null 2>&1) \
    || { echo "Dependency install failed. Run: cd tools/ui && npm install" >&2; exit 1; }
fi

export UI_OUT_DIR="${UI_OUT_DIR:-$ROOT/tools/ui/artifacts}"
export UI_BASE_URL="$URL"

# ---------------------------------------------------------------- self-check
if [ "$SELFCHECK" -eq 1 ]; then
  echo "Self-check: proving the harness works (no app required)."
  rm -rf "$UI_OUT_DIR"; mkdir -p "$UI_OUT_DIR"
  (cd "$UI" && npx playwright test --grep "harness" $HEADED)
  status=$?
  (cd "$UI" && npx tsx helpers/summarize.ts) || true
  exit $status
fi

# ------------------------------------------------------------------- serving
#
# The server runs in its OWN PROCESS GROUP and the whole group is killed on the way out.
#
# It used to be `( cd frontend && npm start ) &` with `kill "$SERVER_PID"`, and that leaks: $! is
# the subshell, npm spawns `sh -c ng serve`, which spawns `ng serve`. SIGTERM to the subshell
# leaves both grandchildren running, reparented to init, still holding port 4200 — and nothing
# says so. The next run then finds something answering on 4200 and uses it.
#
# That failed in both directions on 2026-11-22. A leaked server from an earlier run failed
# verify.sh's live UI checks, which cost an investigation. The other direction is worse and
# silent: a stale server serving an OLD BUILD answers every check, and the run goes green against
# code nobody is looking at. Non-negotiable #8 exists because a green suite does not prove the
# page renders; a green suite against the wrong build does not prove anything at all.
SERVER_PID=""
cleanup() {
  # Negative PID is the process GROUP. setsid below makes the child a group leader, so its pid
  # and the group id are the same number, and this reaches ng serve as well as npm.
  [ -n "$SERVER_PID" ] && kill -- "-$SERVER_PID" 2>/dev/null
}
trap cleanup EXIT

if [ "$SERVE" -eq 1 ]; then
  if [ ! -f "$ROOT/frontend/package.json" ]; then
    echo "--serve needs frontend/ to exist. It does not yet." >&2; exit 1
  fi
  # Asked to start a server, and something is already answering. Do NOT quietly use it: whatever
  # it is serving was built by somebody else, from some other commit, and a pass or a fail against
  # it means nothing. Say what it is and refuse.
  if curl -fsS --max-time 2 "$URL" >/dev/null 2>&1; then
    echo "--serve was asked for, but something is already serving $URL." >&2
    echo "That is usually a dev server leaked by an earlier run. This run would test ITS build," >&2
    echo "not yours, so it is refused. Stop it and try again:" >&2
    echo >&2
    echo "  pkill -f 'ng serve'" >&2
    echo >&2
    echo "Or point this run at it deliberately: tools/ui-check.sh --url $URL" >&2
    exit 1
  fi
  echo "Starting the Angular dev server..."
  # The log lives beside the artifacts directory, which does not exist until the run creates it.
  mkdir -p "$UI_OUT_DIR"
  setsid sh -c "cd '$ROOT/frontend' && npm start" >"$UI_OUT_DIR/../dev-server.log" 2>&1 &
  SERVER_PID=$!
fi

# ------------------------------------------------------------ wait for the app
printf 'Waiting for %s ' "$URL"
ready=0
for _ in $(seq 1 60); do
  if curl -fsS --max-time 2 "$URL" >/dev/null 2>&1; then ready=1; break; fi
  printf '.'; sleep 1
done
if [ "$ready" -eq 0 ]; then
  echo
  echo "Nothing is serving $URL." >&2
  echo "  Start the app first, or use --serve, or point at it with --url." >&2
  echo "  To check the harness itself without an app: tools/ui-check.sh --selfcheck" >&2
  exit 1
fi
echo " up."

rm -rf "$UI_OUT_DIR"; mkdir -p "$UI_OUT_DIR"

# ---------------------------------------------------------------------- run
# An array, not a string: unquoted word-splitting turned `--grep "an expired session"` into
# `--grep an` plus two stray arguments, which Playwright ignored — so a scoped run silently ran
# the whole suite and looked like it had simply passed a lot of tests.
GREP_ARG=(); [ -n "$GREP" ] && GREP_ARG=(--grep "$GREP")
(cd "$UI" && npx playwright test --grep-invert "@doc|harness (captures|detects)" "${GREP_ARG[@]}" $HEADED)
status=$?

(cd "$UI" && npx tsx helpers/summarize.ts) || true

if [ "$status" -ne 0 ]; then
  echo
  echo "UI checks FAILED. Read the findings above and the screenshots listed."
  echo "Full HTML report: $UI_OUT_DIR/_html-report/index.html"
fi
exit $status
