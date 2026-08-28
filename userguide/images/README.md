# Guide screenshots

Captured by `tools/userguide-capture.sh` from the **running app**. These are committed — a user
guide without pictures of the real thing is not a user guide.

- Filenames are `<guide-slug>--<name>.png`, set by `doc.guide()` and `doc.capture()` in the
  capture specs under `tools/ui/specs/docs/`.
- `manifest.json` records the caption, viewport, and the **commit and date** each was captured
  at. `tools/userguide-check.sh` uses that to flag screenshots older than the UI they show.
- Never add an image by hand. If it did not come from a capture run, it will go stale with
  nothing to detect it.

## Demo data only

These images are committed to the repository and this is a budgeting app. **Never capture
against real financial records** — not a user's, not your own. Check the whole frame before
committing, including sidebars and notifications you weren't thinking about.
