# ADR-0028: Store an optional last four digits on an account

- **Status:** Accepted
- **Date:** 2026-10-10
- **Deciders:** Repository owner

## Context

The designs show accounts on every transaction row as `Chase •4412`, `Amex •1009`. Those four
digits do real work: a household with two cards at the same bank cannot tell its rows apart
without them, and "Chase" and "Chase" in a picker is a usability failure that produces
mis-categorised money.

The digits are a fragment of a real account or card number. Budget Owl's entire premise is that
financial data stays on the instance (ADR-0016), and the project's first non-negotiable is that
nothing sensitive leaks into logs, errors or the repository. So storing even a fragment is a
decision to make deliberately rather than a column to add quietly.

Three shapes were available: fold the digits into the free-text account name, store them in a
dedicated validated field, or drop them and diverge from the designs.

## Decision

**An account has an optional `lastFour` field: either absent, or exactly four ASCII digits.**

- Validated at the edge (Bean Validation) **and** in the database
  (`CHECK (last_four ~ '^[0-9]{4}$')`), so the shape survives an import path or a CLI that does
  not go through the API.
- **Never more than four digits.** The field cannot hold a full account number even if somebody
  tries, because the constraint rejects it. That is the main reason it is a typed field rather
  than free text.
- **Treated as a partial financial identifier throughout.** It is never written to a log, never
  placed in an error message or a problem-detail body, and never appears in a URL or query
  string where a reverse proxy or access log would capture it.
- **The server never assembles the display string.** It returns `name` and `lastFour` separately
  and the client composes `name •1234`, because a pre-joined label cannot be re-ordered for a
  right-to-left locale (ADR-0023).
- It is **optional**, and an account with no digits is entirely ordinary — cash in a tin has no
  number.

## Alternatives considered

| Option                                                               | Why not                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Fold the digits into the account name** (user types `Chase •4412`) | Nothing validates it, so the field can hold a full card number the moment one user decides that is tidier — and then a _complete_ PAN sits in a free-text column that gets rendered, exported, logged by accident and searched. A typed four-digit field makes the bad outcome unrepresentable rather than merely discouraged. It also makes the digits unavailable for sorting or matching later, and forces every locale to accept the one string order the user happened to type. |
| **Drop the digits entirely**                                         | Cleanest privacy story, and genuinely tempting. Rejected because the designs show them on every transaction row and the two-cards-at-one-bank case is real; the feature would be re-proposed within a slice or two, and probably as free text.                                                                                                                                                                                                                                       |
| **Store the full number, display only the last four**                | No. It buys nothing a four-digit field does not, and converts a mild disclosure risk into a severe one. Nothing in the product needs a full account number — Budget Owl never moves money, and bank connections (ADR-0020) authenticate through the provider, not through a number we hold.                                                                                                                                                                                          |
| **Encrypt the field at rest**                                        | ADR-0026 already settles this: the operator can read the database, and the control is disclosure rather than encryption. Encrypting four digits against an adversary who holds the key is theatre, and the user was explicit about not taking on that complexity.                                                                                                                                                                                                                    |

## Consequences

**Good:** the designs are buildable as drawn. Two accounts at one institution are
distinguishable. The validated shape means the column cannot grow into a full account number by
user behaviour or by a careless import, which is the failure that actually matters.

**Bad / costs:** the database now contains a partial financial identifier, which it did not
before. That is a real if small increase in what a stolen backup discloses, and it is why the
logging and error-message rules above are requirements rather than style. It also adds a field
that every future surface touching accounts — import, export, the mobile app, the admin console's
backup — has to keep out of its logs. The feature doc's testing notes require an explicit
assertion that the value never reaches a log, on the create, update and error paths.

Four digits alone are not an authenticator anywhere, so the disclosure is bounded: this is not
the same class of secret as a password or a token, and it is deliberately not stored like one.

**Follow-ups:** none blocking. When export or backup lands (admin console, slice 9), confirm the
field is included in a backup — it is user data and must round-trip — and excluded from any
diagnostic bundle or log export that is meant to be shareable.
