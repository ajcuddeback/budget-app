# Budget Owl — Product Vision

**Budget Owl is a self-hosted, open-source budgeting app for households.** You run it on your own
hardware, your financial data never leaves it, and it has a mobile app that is actually good.

Status: **committed direction**, recorded in ADR-0016 through ADR-0021. This document says what
we are building and why; the ADRs say what we decided and what we rejected.

## The one-line thesis

Self-hosted budgeting exists and is popular. What does not exist is a self-hosted budgeting app
with a first-class mobile experience and a modern interface. That is the gap.

## Who it is for

Someone who already self-hosts — Jellyfin, Home Assistant, Paperless, Nextcloud — and wants their
money in the same place as their media and documents: on hardware they control. They will run beta software,
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
envelopes · bills & income · debt plan · the weekly check-in · accounts · transactions ·
categories · transfers · goals · reporting · file import (CSV/OFX/QIF) · household sharing with
roles · the instance console · web app · mobile app

**As many currencies and languages as we can support.** Not a late-stage nicety — a stated goal,
because the self-hosting audience is global and this is software about people's money, often
shared with family, often used while stressed. Accounts hold their own currency and each member
picks their own display currency and language (ADR-0022, ADR-0023). Two people in one household
seeing the same data in different currencies and different languages is a normal case, not an
edge one. Translations are community-contributed; English is the only one we own.

**Optional, user-configured:**
bank connections via the user's own aggregator credentials (ADR-0020) · the Owl assistant, which
runs in a container on the user's own hardware or does not exist on that instance (ADR-0027)

**Not offered, and not deferred:** an assistant endpoint we operate, or a bring-your-own-key path
to a commercial model. Both would send a household's complete financial picture off their machine,
which is the thing this product exists not to do. That was a real commercial option and ADR-0027
rejects it.

**Possible commercial layer, deliberately undecided:**
managed hosting for people who want the product without the server — and only that.

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

- ~~**Licence**~~ — settled: AGPL-3.0 (ADR-0021, accepted 2026-09-25).
- **Commercial layer** — whether managed hosting happens at all. No decision needed for a long
  time; the architecture does not depend on it.
- **AI delivery** — the shape is settled by ADR-0027 (self-hosted sidecar or nothing). Which
  model, and how it is packaged and updated, is still open.
- **Charging for it** — AGPL permits selling the software, support and hosting, but compels
  nobody to pay; most self-hosters will run it free. If a paid tier is ever wanted, open core is
  still reachable — but only while the owner holds all the copyright, so the first accepted
  outside contribution is the real deadline (ADR-0021).
