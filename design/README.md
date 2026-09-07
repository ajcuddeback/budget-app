# Design

The visual source of truth for Budget Owl, exported from Claude Design.

> **Never put real financial data in here.** The design tool allows file uploads, and an export
> carries them along. See [What must not land here](#what-must-not-land-here).

## What is in here

```
canvas/
  Budget Owl.dc.html            The canvas — 26 artboards across three sections
  support.js                    Canvas runtime (needed to open the file in a browser)
  thumbnail.html                Project cover
  sync-state.md                 What the design was built from, and when (was github.md)
  design-project-conventions.md The design project's own rules (was CLAUDE.md — see below)
  _ds/organic-…/                The "Organic" design system
    styles.css                  The token sheet — colours, type, spacing, radii, shadows
    readme.md                   How the system is meant to be used
    _ds_manifest.json           Card index for the Design System pane
```

Open `canvas/Budget Owl.dc.html` in a browser to look at the designs. The relative paths inside
it are why the export's directory structure is preserved verbatim.

**One file was renamed.** The export ships a `CLAUDE.md` describing the *design project's*
conventions. A second `CLAUDE.md` inside this repository would be picked up as agent instructions
and quietly compete with the root one, so it is here as `design-project-conventions.md`. Its
content is unchanged, and its two rules are real requirements — see below.

## Source of truth, and what is derived

This matters more than it sounds. The canvas is a 465 KB single-file HTML document; nothing should
read it wholesale, and nothing should be *built* from it by eye twice.

| Artefact | Status | Who reads it |
|---|---|---|
| `canvas/Budget Owl.dc.html` | **Source** — visual intent | A human, in a browser. Not an agent, not wholesale |
| `canvas/_ds/organic-…/styles.css` | **Source** — the tokens | Extracted once into the app's stylesheet in slice 1 |
| The app's token stylesheet | Derived | Every component, via `var(--color-*)` etc. |
| Per-screen layout notes | Derived | Written into `docs/features/<feature>.md` as each slice is built |

So: a feature slice reads its **feature doc**, which describes its screens in words, and consumes
**tokens** as CSS variables. It does not open the canvas. If a feature doc does not describe the
screen, that is the gap to fix — in the doc.

Colours, spacing, type sizes and radii are **never hard-coded** in application code. If a value
you need is not in the token sheet, that is a design-system question, not a licence to write a hex.

## The two rules from the design project

1. **Every screen ships both viewports.** Any screen, state, dialog or action gets a mobile view
   *and* a desktop view — same fields, same order, same numbers. The canvas holds both for all 26
   artboards; keep it that way, and note that this also feeds the Flutter app (ADR-0019).
2. **Dark mode is first-class**, not an afterthought — warm near-black grounds (`#1a1613` /
   `#241e19` / `#2d2620`), accents stepping up to their 400 tints.

## Known gaps

These are recorded rather than fixed, because the frontend does not exist yet (slice 1).

- **Dark mode is not in the token sheet.** `styles.css` carries only the light theme; the dark
  palette exists as inline styles inside the canvas's dark-mode artboards. The tokens need a dark
  block before any component is written against them, or every screen will re-invent it.
- **The token sheet pulls fonts from Google.** `styles.css` opens with an `@import` of
  `fonts.googleapis.com`, and the canvas links it too. That is fine for a design tool and **not
  fine for this product**: it makes a page load require the public internet and tells a third
  party the user's IP address every time they open their own budget. Both fonts (Caprasimo,
  Figtree) are Open Font License, so they get vendored into the app and served locally. This is
  non-negotiable #9, and it is a privacy leak, not just an availability one.
- **The design-system export is partial.** `readme.md` refers to `theme.json`, `components/*.html`,
  `foundations/*.html`, `templates/` and `assets/photo.jpg`; the export contains none of them. The
  token sheet and the written guidance are the parts that matter, so this is not blocking.
- **`sync-state.md` refers to `docs/features/accounts-and-auth.md`**, which has since been renamed
  to `authentication-and-households.md`. The designs were built against the older document.

## What must not land here

The Claude Design project had a spreadsheet of **real personal finances** attached to it —
household bills, loan balances, real names. It came down inside the export and was deliberately
**not** committed.

This is not a small thing. Budget Owl's entire premise is that a person's financial data stays on
their own machine (ADR-0016); shipping the maintainer's own budget in a public repository would
be an unusually direct contradiction, and git history is permanent.

`tools/verify.sh` fails if a spreadsheet or data file is tracked under `design/`, and
`.gitignore` excludes the export's `uploads/` directory. Neither is a reason to relax: strip
attachments *before* handing an export over.

Screens are populated with invented figures. If a design ever needs realistic data, take it from
`tools/ui/fixtures/demo-data.ts` — the same fixtures the UI harness and the user guide render
against (ADR-0013).

## Refreshing the designs

There is no automatic sync. `DesignSync` is for design-*system* projects and pushes the wrong
direction; a claude.ai/code session cannot authenticate to Claude Design at all.

Export the project and replace `canvas/` wholesale, **minus any `uploads/`**, then re-read
[Known gaps](#known-gaps) — an export overwrites `styles.css`, so a dark-mode block or a
self-hosted font fix applied here will be lost unless it was made in the design project instead.
That is the argument for making those two fixes upstream rather than locally.
