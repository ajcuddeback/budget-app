---
name: feature-doc
description: Create or update a feature doc in docs/features/. Use before building a feature (to write the spec) and after changing one (to keep it honest). Invoked as /feature-doc <feature-name>.
---

# Feature doc

A feature doc is the spec and the memory for one user-facing feature. Writing it first is
cheaper than discovering the design in Java.

## Creating one

1. **Check it doesn't exist.** `ls docs/features/` — if it does, you're updating, not creating.
2. **Copy the template**: `cp docs/features/_TEMPLATE.md docs/features/<kebab-name>.md`
3. **Fill every section.** Sections that don't apply get "None" — not deletion. An absent
   section reads as "nobody thought about it"; "None" reads as "considered, doesn't apply".
4. **Two sections carry the most weight:**
   - **Security considerations** — never leave this thin. Which parts of
     `docs/architecture/security-model.md` does this touch? Who may see this data? Where is
     ownership enforced? What input is untrusted?
   - **Out of scope** — what this deliberately doesn't do, and why. This is what stops the
     same rejected idea being re-proposed every few sessions.
5. **Register it** in the table in `docs/features/README.md` with an honest status.
6. **Add it to `docs/roadmap.md`** if it's a new slice.

## Interviewing for the content

Don't invent the spec. Ask about anything genuinely ambiguous — but ask in one batch, not one
question at a time, and only about things where different answers mean materially different
work. Make the routine calls yourself and state them as assumptions.

Questions worth asking: what happens on the empty state? Can this be deleted, and what happens
to what referenced it? What's the behavior on concurrent edits? Are amounts ever negative?
What should a user *not* be able to see?

## Updating one

- Update it **in the same change** as the behavior. Not after, not in a follow-up.
- Bump `Last updated` and the status line.
- If behavior changed because of a decision, record that decision too — `/adr` if it's
  structural, `/remember` if it's smaller.
- Read the doc against the code before you edit: if they already disagree, the drift is itself
  worth reporting.

## Quality bar

Specific enough that someone could implement from it without asking you a question. If the rules
section says "handle errors appropriately", it isn't done.
