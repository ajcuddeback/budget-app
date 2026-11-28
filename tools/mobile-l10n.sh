#!/usr/bin/env bash
# Builds the Flutter app's strings from the web's and the phone's own.
#
#   tools/mobile-l10n.sh           regenerate mobile/lib/l10n/{app_en.arb,message_lookup.g.dart,gen/}
#   tools/mobile-l10n.sh --check   fail if any committed file is stale (verify.sh runs this)
#
# English is the source (ADR-0023). Every key of frontend/src/i18n/en.json is carried over, so the
# two clients stay in step; mobile/l10n/mobile.en.json holds the phone's own text under keys the web
# does not have. See mobile/tool/l10n/generate_l10n.dart for the two rules it enforces.
set -euo pipefail
# Never let the flutter or dart tool report usage from this repo's scripts (ADR-0016).
export FLUTTER_SUPPRESS_ANALYTICS=true DART_SUPPRESS_ANALYTICS=true
cd "$(dirname "$0")/../mobile"

if [ "${1:-}" = "--check" ]; then
  dart run tool/l10n/generate_l10n.dart --check
  before=$(cat lib/l10n/gen/*.dart | sha256sum)
  flutter gen-l10n >/dev/null
  after=$(cat lib/l10n/gen/*.dart | sha256sum)
  if [ "$before" != "$after" ]; then
    echo "lib/l10n/gen is stale: run tools/mobile-l10n.sh and commit it." >&2
    exit 1
  fi
  exit 0
fi

dart run tool/l10n/generate_l10n.dart
flutter gen-l10n
echo "wrote mobile/lib/l10n/app_en.arb, message_lookup.g.dart and gen/"
