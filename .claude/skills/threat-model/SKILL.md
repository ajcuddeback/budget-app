---
name: threat-model
description: Threat-model a feature or change before building it — assets, actors, attacks, controls. Use before implementing anything touching auth, money movement, or user data. Invoked as /threat-model <feature-or-change>.
---

# Threat model

Cheap before the code exists. Expensive after.

Read `docs/architecture/security-model.md` for the baseline assumptions, and the feature doc for
what's being built.

## Work through it

### 1. Assets — what's worth stealing or breaking?
Financial records, balances, transaction history, credentials, session tokens, PII. Be specific
to this feature: "the list of every payee a user has ever paid" is an asset with real privacy
weight, not just "some data".

### 2. Actors
- Unauthenticated stranger
- Authenticated user attacking **another user's** data ← the one that matters most here
- Authenticated user attacking **their own** data in unintended ways (negative amounts,
  replayed requests, forged relationships to rows they don't own)
- A hostile page in the user's browser (CSRF, XSS)
- Someone who has stolen a session cookie

### 3. Entry points
Every new endpoint, parameter, header, cookie, file upload, and background job this feature adds.
List them explicitly — the one you forget is the one that's unprotected.

### 4. Attacks — walk STRIDE, but stay concrete

| | Ask |
|---|---|
| **S**poofing | Can someone act as another user? Is identity taken from the request anywhere? |
| **T**ampering | Can a request modify data it shouldn't? Any ID trusted from the client? |
| **R**epudiation | Is there a log of who did what, and is it trustworthy? |
| **I**nformation disclosure | What leaks — in responses, errors, timing, status codes, or logs? |
| **D**enial of service | Unbounded queries, unpaginated lists, expensive operations without limits? |
| **E**levation of privilege | Any path to acting outside one's own data? |

For each plausible attack write it as a sentence: *"An attacker sends `PATCH /api/accounts/{id}`
with someone else's account id and gets ..."*. If you can't finish the sentence, it isn't a
real attack — drop it and say so.

### 5. Controls
For each real attack: what stops it, and **where is it enforced**? Name the layer. "Validation"
is not a control; "`@Valid` on `CreateTransactionRequest`, plus a `CHECK` constraint on scale"
is a control.

### 6. Tests
Every control needs a test that fails when the control is removed. That test is the control's
only proof of existence. List them; they go into the feature doc's testing notes.

## Output

Add a filled-in security section to the feature doc:

- **Assets** — what this feature exposes
- **Attacks considered** — including the ones you ruled out, and why
- **Controls** — what, and where enforced
- **Tests** — proving each control
- **Accepted risks** — anything knowingly not mitigated, with the reasoning

The ruled-out attacks matter as much as the live ones — they stop the analysis being redone.

## If something is unclear

Ask. An unresolved security question belongs in the feature doc's open questions, not in a
guess that ships.
