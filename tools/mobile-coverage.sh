#!/usr/bin/env bash
# Line coverage of the phone's core, from the lcov file `flutter test --coverage` writes.
#
#   tools/mobile-coverage.sh [minimum-percent]     default 80 (docs/guides/testing-style.md)
#
# lib/core is the part every screen leans on (session, transport, cache, money), so it is the part
# with a floor. A file under lib/core that no test loads is missing from lcov.info entirely; it is
# counted here as wholly uncovered rather than quietly left out of the denominator.
set -euo pipefail
# Never let the flutter or dart tool report usage from this repo's scripts (ADR-0016).
export FLUTTER_SUPPRESS_ANALYTICS=true DART_SUPPRESS_ANALYTICS=true
cd "$(dirname "$0")/../mobile"

MINIMUM="${1:-80}"
LCOV=coverage/lcov.info
[ -f "$LCOV" ] || { echo "no $LCOV: run flutter test --coverage first" >&2; exit 1; }

missing=()
while IFS= read -r file; do
  grep -qx "SF:$file" "$LCOV" || missing+=("$file")
done < <(find lib/core -name '*.dart' | sort)

unloaded=0
for file in ${missing[@]+"${missing[@]}"}; do
  lines=$(grep -cvE '^\s*(//|$|import |export |part |library )' "$file" || true)
  unloaded=$((unloaded + lines))
  echo "  never loaded by a test: $file (~$lines lines counted as uncovered)"
done

awk -F'[:,]' -v minimum="$MINIMUM" -v unloaded="$unloaded" '
  /^SF:/ { file = $2 }
  /^LF:/ { if (file ~ /^lib\/core\//) found += $2 }
  /^LH:/ { if (file ~ /^lib\/core\//) hit += $2 }
  END {
    total = found + unloaded
    if (total == 0) { print "  no lib/core lines found"; exit 1 }
    pct = 100 * hit / total
    printf "  lib/core line coverage: %.1f%% (%d of %d lines; minimum %d%%)\n", pct, hit, total, minimum
    exit (pct + 0.0001 < minimum) ? 1 : 0
  }' "$LCOV"
