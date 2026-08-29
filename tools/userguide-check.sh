#!/usr/bin/env bash
# Check the customer-facing user guide for the ways it rots.
#
# A user guide fails differently from code: it does not crash, it just quietly starts lying.
# A screenshot showing a button that moved sends a reader hunting for something that is not
# there, and they conclude they broke it. These checks catch that mechanically.
#
set -uo pipefail
cd "$(dirname "$0")/.."

GUIDE=userguide
# Files starting with _ are templates, not guides: their placeholder paths are meant to be
# unresolvable, so they are excluded from the link and image checks.
IMAGES="$GUIDE/images"
MANIFEST="$IMAGES/manifest.json"
FAILED=0
WARNED=0

pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; FAILED=1; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; WARNED=1; }

[ -d "$GUIDE" ] || { echo "No userguide/ directory."; exit 0; }

# 1. Every image a guide references must exist.
missing=0
while IFS= read -r md; do
  while IFS= read -r ref; do
    case "$ref" in http*) continue ;; esac
    target="$(cd "$(dirname "$md")" && realpath -m "$ref" 2>/dev/null)"
    [ -e "$target" ] || { fail "$md references a missing image: $ref"; missing=1; }
  done < <(grep -oE '!\[[^]]*\]\([^)]+\)' "$md" 2>/dev/null | sed 's/.*(\(.*\))/\1/')
done < <(find "$GUIDE" -name '*.md' -not -name '_*')
[ "$missing" -eq 0 ] && pass "every referenced image exists"

# 2. Orphaned images — captured but referenced by no guide.
if [ -d "$IMAGES" ]; then
  orphans=0
  for img in "$IMAGES"/*.png; do
    [ -e "$img" ] || continue
    base=$(basename "$img")
    if ! grep -rqF "$base" --include='*.md' "$GUIDE" 2>/dev/null; then
      warn "orphaned image (captured but no guide uses it): $base"
      orphans=$((orphans + 1))
    fi
  done
  [ "$orphans" -eq 0 ] && pass "no orphaned images"
fi

# 3. Stale screenshots: captured before the UI they depict last changed.
if [ -f "$MANIFEST" ] && [ -d frontend ]; then
  ui_changed=$(git log -1 --format=%cs -- frontend/ 2>/dev/null || echo "")
  if [ -n "$ui_changed" ]; then
    stale=0
    while IFS=$'\t' read -r name captured; do
      # String compare works: both are YYYY-MM-DD.
      if [[ "$captured" < "$ui_changed" ]]; then
        warn "stale screenshot: $name captured $captured, frontend changed $ui_changed"
        stale=$((stale + 1))
      fi
    done < <(python3 -c "
import json,sys
for s in json.load(open('$MANIFEST')):
    print(s['name'], s['capturedAt'], sep='\t')
" 2>/dev/null)
    if [ "$stale" -eq 0 ]; then
      pass "no screenshots older than the last frontend change"
    else
      printf '      Re-capture with: tools/userguide-capture.sh --serve\n'
    fi
  fi
else
  pass "staleness check not applicable yet (no frontend/)"
fi

# 4. Manifest and disk agree.
if [ -f "$MANIFEST" ]; then
  drift=0
  while IFS= read -r f; do
    [ -e "$f" ] || { fail "manifest lists a file that is not on disk: $f"; drift=1; }
  done < <(python3 -c "
import json
for s in json.load(open('$MANIFEST')): print(s['file'])
" 2>/dev/null)
  [ "$drift" -eq 0 ] && pass "manifest matches what is on disk"
fi

# 5. Internal links between guides resolve.
broken=0
while IFS= read -r md; do
  while IFS= read -r link; do
    case "$link" in http*|\#*|mailto:*) continue ;; esac
    target="$(cd "$(dirname "$md")" && realpath -m "${link%%#*}" 2>/dev/null)"
    [ -e "$target" ] || { fail "broken link in $md -> $link"; broken=1; }
  done < <(grep -oE '(^|[^!])\[[^]]+\]\([^)]+\)' "$md" 2>/dev/null | sed 's/.*(\(.*\))/\1/')
done < <(find "$GUIDE" -name '*.md' -not -name '_*')
[ "$broken" -eq 0 ] && pass "no broken links between guides"

[ "$WARNED" -eq 1 ] && printf '  \033[33mwarnings are not failures, but they are how a guide starts lying\033[0m\n'
exit $FAILED
