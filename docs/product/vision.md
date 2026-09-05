# Budget Owl — Product Vision

**Budget Owl is a self-hosted, open-source budgeting app for households.** You run it on your own
hardware, your financial data never leaves it, and it has a mobile app that is actually good.

Status: **committed direction**, recorded in ADR-0016 through ADR-0021. This document says what
we are building and why; the ADRs say what we decided and what we rejected.

## The one-line thesis

Self-hosted budgeting exists and is popular. What does not exist is a self-hosted budgeting app
with a first-class mobile experience and a modern interface. That is the gap.

## Who it is for

Someone who already self-hosts — Immich, Jellyfin, Home Assistant, Paperless — and wants their
money in the same place as their photos: on hardware they control. They will run beta software,
file good bug reports, and tell other people. They are also completely unforgiving about anything
that phones home, and they are right to be.

Secondarily: their household. Budgets are rarely a solo activity, and every tool that treats them
as one makes couples share a login.

## The organising principle: privacy by architecture, not by policy

Every significant decision resolves against this.

A promise not to look at your data is worth what the company's next funding round says it is.
An architecture where the data is on your machine and we have no path to it is worth something
regardless of our intentions. So:

- The **default deployment is yours** — Docker Compose on your hardware (ADR-0016).
- **AI is opt-in, off by default, and local-capable.** Financial history is the most sensitive
  data most people have. Nothing is sent anywhere without an explicit choice, and the user can
  see exactly what would be sent before it goes (ADR-0021 defers the detail; the posture is
  settled).
- **Bank credentials are the user's own** and never touch our infrastructure (ADR-0020).
- If we ever sell anything, we sell **convenience, not access to data**.

## What we are building

**Core, always free and open source:**
accounts · transactions · categories · budgets and periods · transfers · recurring items ·
reporting · household sharing with roles · file import (CSV/OFX/QIF) · web app · mobile app

**Optional, user-configured:**
bank connections via the user's own aggregator credentials · AI insights via a local model, the
user's own API key, or a hosted endpoint

**Possible commercial layer, deliberately undecided:**
managed hosting for people who want the product without the server. Not an LLM endpoint that
processes other people's transactions — see the liability note in ADR-0020.

## Where we differ from the incumbents

| | Them | Us |
|---|---|---|
| **Actual Budget** | Excellent envelope budgeting, active, self-hosted. Mobile is the weak point | Mobile as a first-class client from the start (ADR-0019) |
| **Firefly III** | Mature and comprehensive, but a steep learning curve and a dated interface | Designed interface first (`design/`), opinionated defaults over configurability |

Being third into a category with better execution on its weakest axis is a normal way to win.
It is not a licence to be sloppy about the parts they already do well.

## What this is not

- **Not a bank.** We never move money. Read-only on connected accounts, forever.
- **Not a SaaS with a self-host option.** Self-hosting is the primary shape, not a downgrade.
- **Not an AI product.** It is a budgeting app that can optionally use a model. Anything an
  ordinary lookup table does better — transaction categorisation, for one — uses a lookup table.
- **Not multi-tenant-by-default.** An instance belongs to a household. See ADR-0017.

## How this changes the engineering

Recorded in full in the ADRs; the short version:

1. **Households, not users, own financial data** (ADR-0017). Single-user is a household of one.
2. **Auth is first-party with optional OIDC**, and mobile uses tokens (ADR-0018, superseding
   ADR-0004). You cannot require a self-hoster to run Keycloak to log into their own app.
3. **The deployable unit is a Compose file and published images** (ADR-0016), which makes
   upgrade and backup paths product features rather than afterthoughts.
4. **Flutter for mobile** (ADR-0019).

## Open questions

- **Licence** — AGPL-3.0 proposed in ADR-0021, awaiting a decision. It gates nothing yet but
  gets harder to change with every contributor.
- **Commercial layer** — whether managed hosting happens at all. No decision needed for a long
  time; the architecture does not depend on it.
- **AI delivery** — deferred until there is data to analyse. The privacy posture above is
  settled; the mechanism is not.
