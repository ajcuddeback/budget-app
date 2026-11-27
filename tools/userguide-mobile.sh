#!/usr/bin/env bash
# Copy phone screenshots for the user guide out of the Flutter goldens.
#
#   tools/userguide-mobile.sh            copy every image listed, and stamp the manifest
#   tools/userguide-mobile.sh --render   re-render the goldens first (tools/mobile-check.sh)
#
# The web capture (tools/userguide-capture.sh) cannot draw the Flutter app. The golden tests
# can: every phone screen at 390x844, from fixtures recorded from the real API, the same bytes
# every run. So a phone screenshot in the guide is a golden's light variant, copied, never drawn
# by hand.
#
# What is copied is listed in userguide/images/mobile-captures.txt, one per line:
#
#   <guide-slug> <name> <golden-scenario> <caption, to the end of the line>
#
# mobile/test/goldens/png/<golden-scenario>__light.png becomes
# userguide/images/<guide-slug>--<name>.png, and is recorded in userguide/images/manifest.json
# as web captures are (name, file, guide, caption, capturedAt, commit, viewport), plus `source`:
# the golden it came from. tools/userguide-check.sh reads `source` to judge staleness against
# mobile/ rather than frontend/.
#
# This script owns the manifest entries whose source is under mobile/: an entry (and its image)
# whose line has left the list is removed. Web entries are never touched.
set -euo pipefail
cd "$(dirname "$0")/.."

LIST=userguide/images/mobile-captures.txt
GOLDENS=mobile/test/goldens/png
IMAGES=userguide/images
MANIFEST=$IMAGES/manifest.json

case "${1:-}" in
  --render) tools/mobile-check.sh ;;
  "") ;;
  -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "unknown option: $1" >&2; exit 2 ;;
esac

[ -f "$LIST" ] || { echo "No $LIST — nothing to copy." >&2; exit 1; }

commit=$(git rev-parse --short HEAD 2>/dev/null || echo unknown)
today=$(date -u +%F)

python3 - "$LIST" "$GOLDENS" "$IMAGES" "$MANIFEST" "$commit" "$today" <<'PY'
import json, os, re, shutil, subprocess, sys

listing, goldens, images, manifest, commit, today = sys.argv[1:]
slug = re.compile(r'^[a-z0-9]+(-[a-z0-9]+)*$')

wanted, failed = [], False
with open(listing, encoding='utf-8') as f:
    for number, line in enumerate(f, 1):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split(None, 3)
        if len(parts) < 4:
            print(f'{listing}:{number}: needs <guide-slug> <name> <golden-scenario> <caption>', file=sys.stderr)
            failed = True
            continue
        guide, name, scenario, caption = parts
        if not all(slug.match(p) for p in (guide, name, scenario)):
            print(f'{listing}:{number}: slug, name and scenario are lower-case words joined by hyphens', file=sys.stderr)
            failed = True
            continue
        source = f'{goldens}/{scenario}__light.png'
        if not os.path.isfile(source):
            print(f'{listing}:{number}: no golden {source} (tools/mobile-check.sh renders them)', file=sys.stderr)
            failed = True
            continue
        wanted.append((guide, name, source, caption))

names = [f'{g}--{n}' for g, n, _, _ in wanted]
if len(set(names)) != len(names):
    print(f'{listing}: the same <guide-slug> <name> is listed twice', file=sys.stderr)
    failed = True
if failed:
    sys.exit(1)

try:
    with open(manifest, encoding='utf-8') as f:
        entries = json.load(f)
except FileNotFoundError:
    entries = []

def is_mobile(entry):
    return str(entry.get('source', '')).startswith('mobile/')

web_names = {e['name'] for e in entries if not is_mobile(e)}
clash = web_names.intersection(names)
if clash:
    print(f'refusing: a web capture already uses {", ".join(sorted(clash))}', file=sys.stderr)
    sys.exit(1)

# Lines removed from the list: their entries and images go too.
for e in entries:
    if is_mobile(e) and e['name'] not in names:
        if os.path.exists(e['file']):
            os.remove(e['file'])
        print(f'  removed {e["file"]} (no longer listed)')
entries = [e for e in entries if not is_mobile(e) or e['name'] in names]

def dirty(path):
    out = subprocess.run(['git', 'status', '--porcelain', '--', path],
                         capture_output=True, text=True).stdout
    return bool(out.strip())

for (guide, name, source, caption), full in zip(wanted, names):
    target = f'{images}/{full}.png'
    shutil.copyfile(source, target)
    entry = {
        'name': full,
        'file': target,
        'guide': guide,
        'caption': caption,
        'capturedAt': today,
        'commit': commit,
        'viewport': '390x844',
        'source': source,
    }
    note = '  (golden not committed yet)' if dirty(source) else ''
    print(f'  {target}  <-  {source}{note}')
    for i, e in enumerate(entries):
        if e['name'] == full:
            entries[i] = entry
            break
    else:
        # Keep the file in name order without reordering anybody else's entries.
        at = next((i for i, e in enumerate(entries) if e['name'] > full), len(entries))
        entries.insert(at, entry)

with open(manifest, 'w', encoding='utf-8') as f:
    f.write(json.dumps(entries, indent=2, ensure_ascii=False) + '\n')
print(f'Copied {len(wanted)} phone screenshot(s); the manifest lists {len(entries)} image(s).')
PY

echo
echo "  READ every image listed above before writing about it."
echo "  Write what you SEE in them, not what the feature doc says should be there."
