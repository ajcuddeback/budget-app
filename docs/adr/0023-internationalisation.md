# ADR-0023: Internationalisation — runtime locale, codes over prose, community translations

- **Status:** Accepted
- **Date:** 2026-09-07
- **Deciders:** Repository owner

## Context

Budget Owl should support as many languages as possible (`../product/vision.md`). The
self-hosting audience is heavily non-English-speaking, and a budgeting app is exactly the kind of
software people want in their own language — it is about their money, often shared with family,
sometimes used while stressed.

Three constraints shape how, rather than whether:

- **One image, many users.** A self-hoster ships one deployment and their household may not share
  a language. Locale is a per-user runtime choice, not a build-time or deployment-time one.
- **We will not write the translations.** A solo maintainer cannot translate into thirty
  languages. Everything about this must be built for contribution.
- **Adding a language must not require a backend release**, or languages will lag features
  permanently.

## Decision

**Locale is chosen at runtime, per user, and translation lives entirely in the clients.**

**The API returns codes, not prose.** An error carries a stable machine-readable code and
structured parameters; the client renders the sentence.

```json
{ "type": "...", "title": "Validation failed", "status": 400,
  "code": "transaction.amount.exceeds_balance",
  "params": { "available": "120.00", "currency": "GBP" } }
```

`title` stays as a developer-facing English fallback for logs and debugging. **No user-facing
sentence is ever assembled on the server.** This is what lets a translation ship without a
backend release, and it means the API is not quietly an English API.

**Clients:**
- **Angular: runtime i18n**, not Angular's build-time compilation. Build-time i18n produces one
  bundle per locale, which for a self-hosted single-image deployment means shipping and serving
  thirty bundles to pick one. Runtime translation loads a language file instead.
- **Flutter:** `flutter_localizations` with ARB files and runtime locale switching.
- Locale follows the user's preference, defaulting to the platform locale, stored per user.

**Formatting is locale-aware and separate from language:** dates, numbers, and currency all
format by locale (`1.234,56 €` vs `€1,234.56`), and a user may reasonably want English text with
German number formatting. Never hand-format a number or a date.

**RTL from the start.** Arabic, Hebrew, Persian and Urdu need logical properties (`margin-inline`,
not `margin-left`), mirrored icons, and layouts that do not assume left-to-right. Retrofitting RTL
after fifty screens exist is a rewrite; honouring it from the first component is nearly free.

**Translations are community-contributed**, through a translation platform rather than pull
requests against raw files. English is the source language and the only one the maintainer is
responsible for. A missing string falls back to English rather than showing a key.

## Alternatives considered

| Option | Why not |
|---|---|
| English only, translate later | i18n retrofits are brutal: every hard-coded string, every `left`, every date format. The cost is near-zero up front and enormous later |
| Server-side translation with `Accept-Language` | Adding a language would need a backend release, and it makes the API's contract English prose. Two clients then duplicate nothing but depend on the server for their vocabulary |
| Angular's built-in build-time i18n | The Angular-recommended path, and wrong for a single-image self-hosted deployment — one bundle per locale, chosen at build time, when the user picks at runtime. This is an override of the `angular-developer` skill's default and belongs in the ADR-0014 table |
| Translations by pull request against JSON files | Merge conflicts, no translator tooling, no way to see what is missing. Deters exactly the contributors we need |

## Consequences

**Good:** a language can be added without touching the backend or cutting a release. The API is
honest about being machine-readable rather than accidentally English. Formatting is right for
each user rather than right for the author. RTL works because it was never broken.

**Bad / costs:** every user-facing string goes through a translation layer from day one, which is
friction on every feature. Error codes must be designed and kept stable — a code is now part of
the API contract, so renaming one is a breaking change. Translation quality is outside our
control, and a bad translation of a financial term is worse than English. Pluralisation and
gendered grammar are genuinely hard in some languages and ICU message format is the floor, not a
nicety. RTL needs testing, which means the UI harness needs an RTL pass.

**Follow-ups:** add the runtime-i18n override to the table in `../guides/angular-style.md`
(ADR-0014). Error codes get a registry so they stay stable and unique. The UI harness should
render at least one RTL locale. Choose the translation platform before the first non-English
language, not after.
