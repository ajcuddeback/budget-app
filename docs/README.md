# Documentation Map

> **This tree is for developers and agents.** Customer-facing help for people *using* the app
> lives in [`userguide/`](../userguide/README.md) — different reader, different vocabulary,
> different source of truth (the running app). Don't mix them. See ADR-0012.

Four kinds of knowledge live here. Put new writing in the right one — the value of this tree is
that an agent can find the answer without reading code.

| Directory | Holds | Lifetime | Write it with |
|---|---|---|---|
| `product/` | What Budget Owl is, who it is for, and why | Changes rarely | by hand |
| `architecture/` | How the system is shaped, and how it defends itself | Changes rarely | by hand |
| `domain/` | What the words mean and how the data is modeled | Changes with the model | by hand |
| `features/` | One doc per user-facing feature: intent, rules, API, edge cases, status | Lives with the feature | `/feature-doc` |
| `adr/` | Numbered, dated, immutable records of decisions and their reasons | Append-only | `/adr` |
| `memory/` | Conventions, glossary, gotchas, running decision log | Continuously | `/remember` |
| `guides/` | Style guides. How we write code here | Changes rarely | by hand |

## The rule that makes this work

**If you learned it the hard way, write it down before you move on.**

A fact discovered by reading five files and running three commands costs the same to rediscover
next session. Ten seconds in `memory/gotchas.md` saves that forever.

## Which one do I use?

- "Why did we pick Postgres over MySQL?" → **ADR**. Decisions with alternatives and consequences.
- "How does recurring-transaction rollover work?" → **feature doc**.
- "What's the difference between an Account and an Envelope?" → **glossary** / **domain model**.
- "Testcontainers hangs unless you set X" → **gotchas**.
- "We name Spring services `<Noun>Service` not `<Noun>Manager`" → **conventions**.
- "How do sessions and CSRF actually work here?" → **architecture/security-model.md**.

## Index

- [**Product vision**](product/vision.md) — start here for what we are building and why
- [Architecture overview](architecture/overview.md)
- [Security model](architecture/security-model.md) — read before touching auth
- [Tech stack + versions](architecture/tech-stack.md)
- [Domain model](domain/model.md)
- [Legacy app behavior](domain/legacy-app.md) — what the deleted MERN app did, and why we rewrote
- [Feature docs](features/README.md)
- [Decision records](adr/README.md)
- [Memory](memory/README.md)
- Style guides: [Java](guides/java-style.md) · [Angular](guides/angular-style.md) ·
  [API](guides/api-style.md) · [Database](guides/database-style.md) ·
  [Testing](guides/testing-style.md) · [Git & review](guides/git-style.md) ·
  [Flutter](guides/flutter-style.md) ·
  [UI validation](guides/ui-validation.md) · [User docs](guides/user-docs.md)
- [Roadmap](roadmap.md)
