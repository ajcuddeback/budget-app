---
name: adr
description: Record an architecture decision as a numbered ADR in docs/adr/. Use when a decision is hard to reverse, rejects a reasonable alternative, or changes a security posture, data model, or public contract. Invoked as /adr <decision>.
---

# Record an architecture decision

## Is this an ADR?

Yes if: it's hard or expensive to reverse · someone will ask "why is it like this?" later ·
a reasonable alternative was rejected and the reason matters · it changes a security posture,
a data model, or a public contract.

No if: it's a naming choice (`docs/memory/conventions.md`) · a surprise or trap
(`docs/memory/gotchas.md`) · a small decision with no real alternative
(`docs/memory/decision-log.md`).

When unsure, ask whether the *reasoning* is what needs preserving. If yes, ADR.

## Steps

1. **Find the next number**: `ls docs/adr/` and take the highest + 1, zero-padded to four.
2. **Create** `docs/adr/NNNN-<imperative-kebab-title>.md` from `docs/adr/template.md`.
   Title is imperative and specific: `0011-use-idempotency-keys-for-transfers`, not
   `0011-transfers`.
3. **Write it**:
   - **Context** — the situation forcing the decision, written so it makes sense to someone
     who wasn't there. State the constraints honestly, including the awkward ones.
   - **Decision** — active voice, present tense. "We use X." Not "X should be used."
   - **Alternatives considered** — a table, with a real reason for each rejection. This is the
     most valuable section. An ADR with no genuine alternatives usually documents a decision
     that wasn't actually made — if that's the case, it may belong in the decision log instead.
   - **Consequences** — good *and* bad. An ADR listing only benefits is marketing. Name what
     this costs us and what we're now committed to.
4. **Add it to the index table** in `docs/adr/README.md`.
5. **Cross-link**: if it changes a rule, reference it from the guide or doc that states the rule.

## Superseding

Never rewrite an accepted ADR's substance — the record of what we believed and why is the point.

Write a new ADR, then add to the old one:

```
- **Status:** Superseded by ADR-0014
```

and update both rows in the index.

## Tone

Write for someone six months from now who has no context and is about to change this. Give them
enough to decide whether the reasoning still holds.
