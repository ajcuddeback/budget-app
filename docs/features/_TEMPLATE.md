# Feature: <name>

- **Status:** Planned | In progress | Shipped | Deprecated
- **Owner:** <who>
- **Last updated:** YYYY-MM-DD
- **Related:** ADR-NNNN, other feature docs

## Purpose

What a user can do because this exists, and why they want to. One paragraph, in their language,
not ours.

## User stories

- As a <role>, I want <capability> so that <outcome>.

## Rules and behavior

The actual logic. Be specific and unambiguous — this is the spec an implementer follows without
having to ask.

- ...

## Data model

Entities, fields, and relationships this feature adds or touches. Link to
`../domain/model.md` rather than restating what's already there.

Migrations: `V<n>__<name>.sql`

## API

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `GET` | `/api/...` | ... | authenticated, own data only |

Request and response shapes, or a link to the generated OpenAPI operation.

## UI

Screens, routes, components. What the user sees and how they move through it.

## Security considerations

**Always fill this in.** Which parts of `../architecture/security-model.md` does this touch?

- Who is allowed to see and change this data?
- What's the ownership check, and where is it enforced?
- What input is untrusted, and where is it validated?
- Anything logged that shouldn't be?

## Edge cases

The awkward ones. Empty states, concurrent edits, zero and negative amounts, timezone
boundaries, deleted references, partial failures.

## Out of scope

What this deliberately does **not** do, and why. This section prevents the same rejected idea
being re-proposed every few sessions.

## Open questions

Unresolved decisions, with whoever needs to answer them.

## Testing notes

Beyond the mandatory tests in `../guides/testing-style.md`: what specifically must be proven
for this feature to be trustworthy?
