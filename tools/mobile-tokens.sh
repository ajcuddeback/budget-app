#!/usr/bin/env bash
# Exports the design tokens and the bundled fonts into the Flutter app.
#
#   tools/mobile-tokens.sh            regenerate mobile/lib/core/theme/tokens.dart and the fonts
#   tools/mobile-tokens.sh --check    fail if the committed tokens.dart is stale (verify.sh runs this)
#
# A token change is a re-run of this script, never a hand edit (docs/features/mobile-app.md,
# Design). Colours, spacing and radii come from frontend/src/styles/tokens.css — the only sheet
# with the dark palette (design/README.md, Known gaps). The fonts come from design/fonts, which
# is served from the instance on the web; on the phone they are bundled, never fetched
# (non-negotiable 9). Flutter cannot read WOFF2, so they are decompressed to TrueType with
# fontTools. That step needs python3 with fontTools and brotli (pip install fonttools brotli);
# --check does not, so the gate runs on a machine without them.
set -euo pipefail
cd "$(dirname "$0")/../mobile"

if [ "${1:-}" = "--check" ]; then
  exec dart run tool/tokens/generate_tokens.dart --check
fi

dart run tool/tokens/generate_tokens.dart
echo "wrote mobile/lib/core/theme/tokens.dart"

PYTHON="${MOBILE_TOKENS_PYTHON:-python3}"
if ! "$PYTHON" -c 'import fontTools, brotli' 2>/dev/null; then
  echo "fonts NOT regenerated: $PYTHON has no fontTools/brotli (pip install fonttools brotli," >&2
  echo "or point MOBILE_TOKENS_PYTHON at a python that has them)." >&2
  exit 1
fi

mkdir -p assets/fonts
# latin and latin-ext are separate subsets on the web, chosen by unicode-range. Flutter picks a
# face by family, not by range, so each subset is its own family and the theme lists the -ext one
# as a fallback (lib/core/theme/app_theme.dart).
for pair in \
  "caprasimo-latin:Caprasimo-Latin" \
  "caprasimo-latin-ext:Caprasimo-LatinExt" \
  "figtree-variable-latin:Figtree-Latin" \
  "figtree-variable-latin-ext:Figtree-LatinExt"; do
  src="${pair%%:*}"
  dst="${pair##*:}"
  "$PYTHON" - "../design/fonts/$src.woff2" "assets/fonts/$dst.ttf" <<'PY'
import sys
from fontTools.ttLib import TTFont
font = TTFont(sys.argv[1])
font.flavor = None
font.save(sys.argv[2])
PY
  echo "wrote mobile/assets/fonts/$dst.ttf"
done
cp ../design/fonts/OFL-Caprasimo.txt ../design/fonts/OFL-Figtree.txt assets/fonts/
echo "copied the SIL Open Font Licence texts"
