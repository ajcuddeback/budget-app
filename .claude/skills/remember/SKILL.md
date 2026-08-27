---
name: remember
description: Record something learned into docs/memory/ — a convention, a glossary term, a gotcha, or a small decision — so the next session doesn't rediscover it. Use whenever something surprised you, cost real time, or settled a choice. Invoked as /remember <thing>.
---

# Remember this

The trigger is **"huh, I didn't know that"** — or "we just decided something and nobody wrote it
down". Thirty seconds now against however long it took to work out, every session, forever.

## Route it

| The thing is… | File |
|---|---|
| A small agreed choice — naming, pattern, "we do it this way" | `docs/memory/conventions.md` |
| A word that means something specific here | `docs/memory/glossary.md` |
| A trap, surprise, or thing that cost time | `docs/memory/gotchas.md` |
| A decision without ADR weight | `docs/memory/decision-log.md` |
| A decision **with** reversal cost or a real rejected alternative | **`/adr` instead** |
| How a feature behaves | **`/feature-doc` instead** |

Routing matters. Wrong-file writing is how this tree decays into a pile nobody reads.

## Write it

**Gotchas** — what happened, why, what to do instead, dated:

```markdown
### `BigDecimal.equals` compares scale, `compareTo` does not

`new BigDecimal("1.10").equals(new BigDecimal("1.1"))` is false...

Use `isEqualByComparingTo` in assertions.

*Added 2026-08-27.*
```

**Decision log** — newest first, dated heading, what was decided, why, and what was rejected.

**Conventions** — one line under the right section. Terse. It's a rule, not an essay.

**Glossary** — a table row. Say what the word means *here*, and flag it if the general meaning
differs or if it's a legacy term not to use.

## Rules

- **Date everything.** A gotcha about a library version is worthless without a date.
- **Say why, not just what.** "Use X" without the reason gets overridden by the next person with
  an opinion.
- **Don't duplicate.** If it's already in a guide or ADR, link rather than restate — two copies
  means one is already wrong.
- **Never record secrets, credentials, or personal data.** Not in memory, not anywhere in this
  repo.
- If a memory entry grows into a real pattern used across the codebase, promote it into the
  relevant guide and leave a link behind.
