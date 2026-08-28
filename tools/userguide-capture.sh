#!/usr/bin/env bash
# Capture the screenshots used by the customer-facing user guide.
#
#   tools/userguide-capture.sh              # against http://localhost:4200
#   tools/userguide-capture.sh --url ...    # any running instance
#   tools/userguide-capture.sh --selfcheck  # prove the pipeline works, no app needed
#   tools/userguide-capture.sh --grep accounts
#
# Images land in userguide/images/ and ARE committed — a user guide without pictures of the
# real thing is not a user guide. Every capture is stamped with the commit and date it was
# taken so tools/userguide-check.sh can flag ones that have gone stale.
#
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
UI="$ROOT/tools/ui"

URL="${UI_BASE_URL:-http://localhost:4200}"
SELFCHECK=0; SERVE=0; GREP=""
while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --selfcheck) SELFCHECK=1; shift ;;
    --serve) SERVE=1; shift ;;
    --grep) GREP="$2"; shift 2 ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

if [ ! -d "$UI/node_modules" ]; then
  echo "Installing UI harness dependencies (one time)..."
  (cd "$UI" && npm ci --no-audit --no-fund >/dev/null 2>&1 || npm install --no-audit --no-fund >/dev/null 2>&1) \
    || { echo "Dependency install failed. Run: cd tools/ui && npm install" >&2; exit 1; }
fi

export USERGUIDE_IMAGES="$ROOT/userguide/images"
export UI_BASE_URL="$URL"
mkdir -p "$USERGUIDE_IMAGES"

run_capture() {
  # Doc captures are desktop-only and single-project: a guide shows one consistent view,
  # and a second viewport would silently overwrite each image with the mobile version.
  (cd "$UI" && npx playwright test --project=desktop --grep "@doc${GREP:+.*$GREP}" "$@")
}

if [ "$SELFCHECK" -eq 1 ]; then
  # Write to a scratch directory: self-check output proves the pipeline works, it is not
  # guide content, and committing it would leave permanent orphans in userguide/images/.
  export USERGUIDE_IMAGES="$ROOT/tools/ui/artifacts/userguide-selfcheck"
  rm -rf "$USERGUIDE_IMAGES"; mkdir -p "$USERGUIDE_IMAGES"
  echo "Self-check: proving the user-guide capture pipeline works (no app required)."
  echo "  (writing to tools/ui/artifacts/userguide-selfcheck/, not the committed guide)"
  run_capture specs/docs/guide-selfcheck.spec.ts
  status=$?
else
  SERVER_PID=""
  cleanup() { [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null; }
  trap cleanup EXIT

  if [ "$SERVE" -eq 1 ]; then
    [ -f "$ROOT/frontend/package.json" ] || { echo "--serve needs frontend/ to exist." >&2; exit 1; }
    echo "Starting the Angular dev server..."
    (cd "$ROOT/frontend" && npm start >/dev/null 2>&1) &
    SERVER_PID=$!
  fi

  printf 'Waiting for %s ' "$URL"
  ready=0
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 2 "$URL" >/dev/null 2>&1; then ready=1; break; fi
    printf '.'; sleep 1
  done
  [ "$ready" -eq 1 ] || {
    echo; echo "Nothing is serving $URL." >&2
    echo "  Start the app, or use --serve, or --selfcheck to test the pipeline alone." >&2
    exit 1
  }
  echo " up."
  run_capture --grep-invert selfcheck
  status=$?
fi

count=$(find "$USERGUIDE_IMAGES" -name '*.png' 2>/dev/null | wc -l | tr -d ' ')
echo
echo "Captured $count image(s) into ${USERGUIDE_IMAGES#$ROOT/}/"
if [ "$count" -gt 0 ]; then
  echo
  echo "  Read these to write or check the guide text:"
  find "$USERGUIDE_IMAGES" -name '*.png' | sort | sed 's/^/    /'
  echo
  echo "  Write what you SEE in them, not what the feature doc says should be there."
fi
exit $status
