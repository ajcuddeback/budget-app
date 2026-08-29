#!/usr/bin/env bash
# Refresh the vendored third-party agent skills and show what changed.
#
# These are other people's instructions to our agents, so an update is a REVIEWABLE COMMIT,
# never a silent background refresh. Run this deliberately — typically after an Angular major —
# read the diff, and pay attention to whether any override in the table at the top of
# docs/guides/angular-style.md has become unnecessary or newly needed.
#
set -uo pipefail
cd "$(dirname "$0")/.."

SOURCES=("https://github.com/angular/skills")

if ! git diff --quiet -- .agents skills-lock.json 2>/dev/null; then
  echo "You have uncommitted changes under .agents/ or skills-lock.json." >&2
  echo "Commit or stash them first, so the update diff is readable." >&2
  exit 1
fi

before=$(sha256sum skills-lock.json 2>/dev/null | cut -d' ' -f1 || echo none)

for src in "${SOURCES[@]}"; do
  echo "Updating $src ..."
  npx --yes skills add "$src" || { echo "Failed to update $src" >&2; exit 1; }
done

after=$(sha256sum skills-lock.json 2>/dev/null | cut -d' ' -f1 || echo none)

echo
if [ "$before" = "$after" ] && git diff --quiet -- .agents 2>/dev/null; then
  echo "No changes — vendored skills are already current."
  exit 0
fi

echo "Skills changed. Review before committing:"
echo
git --no-pager diff --stat -- .agents skills-lock.json
echo
echo "Read the full diff with:  git diff -- .agents skills-lock.json"
echo
echo "Then check, in docs/guides/angular-style.md:"
echo "  - does the override table still describe what the skill actually says?"
echo "  - has an override become unnecessary (they changed their default to match ours)?"
echo "  - does a new default of theirs conflict with a project decision, needing a new row?"
echo
echo "Record anything material with /adr or /remember."
