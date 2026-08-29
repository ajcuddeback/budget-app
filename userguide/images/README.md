# Guide screenshots

Captured by `tools/userguide-capture.sh` from the **running app**. These are committed — a user
guide without pictures of the real thing is not a user guide.

- Filenames are `<guide-slug>--<name>.png`, set by `doc.guide()` and `doc.capture()` in the
  capture specs under `tools/ui/specs/docs/`.
- `manifest.json` records the caption, viewport, and the **commit and date** each was captured
  at. `tools/userguide-check.sh` uses that to flag screenshots older than the UI they show.
- Never add an image by hand. If it did not come from a capture run, it will go stale with
  nothing to detect it.

## Where the data comes from

Everything visible in these screenshots is defined in `tools/ui/fixtures/demo-data.ts`. The
capture run intercepts every `/api/**` request and answers it from that file, so there is no
database, no server and no credentials involved — the app renders fixtures and nothing else.

Captures also refuse to run against anything but a local address, enforced in
`tools/userguide-capture.sh` with no override.

Need a scenario the fixtures don't cover — an empty state, an overspent budget, a long account
name? Add it to `demo-data.ts`. That keeps captures reproducible: the same bytes every run, so
a screenshot only changes when the UI does. See ADR-0013.
