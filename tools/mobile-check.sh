#!/usr/bin/env bash
# Renders every phone screen and lists the pictures to read — the mobile twin of tools/ui-check.sh.
#
#   tools/mobile-check.sh            re-render the goldens, then list every PNG (changed ones first)
#   tools/mobile-check.sh --compare  render and compare against the committed PNGs; change nothing
#
# Screens are drawn at 390x844 with the bundled fonts, against the fixtures DemoFixtureIT generated
# from the real API: light, dark, VIEWER, offline-and-stale, errors, right-to-left and text at 2x
# (mobile/test/goldens/screens_golden_test.dart). A green run says the pixels are what they were;
# it does not say they are right. READ the PNGs it lists (docs/features/mobile-app.md, Testing).
set -euo pipefail
# Never let the flutter or dart tool report usage from this repo's scripts (ADR-0016).
export FLUTTER_SUPPRESS_ANALYTICS=true DART_SUPPRESS_ANALYTICS=true
cd "$(dirname "$0")/../mobile"

if [ "${1:-}" = "--compare" ]; then
  exec flutter test test/goldens
fi

flutter test test/goldens --update-goldens

dir=test/goldens/png
changed=$(git status --porcelain -- "$dir" | awk '{print $2}' | sed 's|^mobile/||' || true)
total=$(find "$dir" -name '*.png' | wc -l | tr -d ' ')

echo
if [ -n "$changed" ]; then
  echo "Changed or new since the last commit — read these first:"
  for f in $changed; do echo "  mobile/$f"; done
  echo
fi
echo "All $total screenshots:"
find "$dir" -name '*.png' | sort | sed 's|^|  mobile/|'
