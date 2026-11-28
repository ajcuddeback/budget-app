# ADR-0037: An import preview is stateless; the commit re-uploads the file and proves it with a digest

- **Status:** Accepted
- **Date:** 2026-11-25
- **Deciders:** Repository owner

## Context

File import (slice 12, [file-import.md](../features/file-import.md)) turns a bank statement into
transactions. The person importing must see exactly what will be written before anything is, so
the flow is two steps: a **preview** that parses and classifies every row, and a **commit** that
writes them.

Something has to connect the two. Between them the person reads the preview, perhaps flips a few
suspected duplicates, perhaps walks away and never comes back. Whatever carries the parsed rows from
the first request to the second is, for that time, **a copy of somebody's bank statement held by
the server** — and it is a copy of a file the server did not write, parsed from bytes an attacker
may have chosen.

Constraints that shaped the choice:

- **Nothing parsed from the file should exist on the server between the two requests.** A household
  that abandons a preview should leave nothing behind: not in a table, not in a backup, not in a
  cache that household deletion (domain invariant 6) has to remember.
- **The server must only ever write what it parsed itself.** If the commit accepted rows from the
  client, the preview would be decoration and the client could write anything.
- The instance may be a single-board computer with a small heap, and one user must not be able to
  make the server hold an unbounded amount of state on their behalf.
- The mobile app (slice 15) will call the same API, so this is a public contract, not an internal
  detail.

## Decision

**The preview writes nothing and keeps nothing. The commit sends the same file and options again,
the server parses it again through the same code path, and a `previewDigest` proves that what is
about to be written is what the person was shown.**

1. `POST /api/imports/preview` takes the file (base64 in JSON), the target account and the options.
   It parses, classifies and answers with a summary, the rows needing a decision, a sample, and a
   `previewDigest`. It writes no transaction, no payee, no batch, no staging row and no temp file.
2. `POST /api/imports` takes the same file and options plus the person's decisions and the
   `previewDigest`. It re-parses, re-classifies against the ledger as it is *now*, recomputes the
   digest, and:
   - if the digest matches, writes every accepted row and one `import_batches` row **in one
     database transaction**, or nothing at all;
   - if it differs, writes nothing and answers `409 import-preview-stale`.
3. **The digest** is SHA-256 over a canonical, length-prefixed serialisation of the target account
   id, the account's currency, and for every row in file order its record number, classification,
   date, amount at scale 4, payee text, note text and `external_id`. It excludes the account's
   balance, so an unrelated transaction typed between the two requests does not make a preview
   stale; it includes the classification, so a row someone else imported in the meantime does.
4. **The digest is an integrity check for an honest user's screen, not a security boundary.** It
   carries no HMAC and no secret. A client that forges one achieves at most writing what the server
   itself parsed from the uploaded file into an account the caller may already write to, which is
   less than `POST /api/transactions` allows.
5. **Commit is idempotent on the digest.** `import_batches` is `UNIQUE (household_id,
   preview_digest)`. A commit retried after the first attempt succeeded finds that batch and
   returns it with `200`, which meets the intent of `Idempotency-Key` in
   [api-style.md](../guides/api-style.md) without a header the codebase does not otherwise have. A
   second commit that races the first loses on the index (or on
   `ux_transactions_household_external_id`), rolls back completely, and is told
   `409 import-preview-stale`.

## Alternatives considered

| Option | Why not |
|---|---|
| **A staging table** holding the parsed rows between preview and commit | Persists financial data from files the user then abandons. Needs an expiry job, appears in every backup taken in the meantime, has to be included in household deletion, and is one more table with statement content in it for ADR-0038 to argue against |
| **An in-memory draft keyed by a preview id** | Lost on restart, so a preview taken just before an upgrade commits nothing and the person does not know why. It is also memory one user controls: thirty previews of a 5 MiB file each is a heap the instance did not budget for. Multi-instance deployments would need a shared store, which is the staging table again |
| **The client sends the parsed rows back for commit** | The server would write whatever the client said. The preview would be decoration and the parser's guarantees would end at the network boundary |
| **No preview: commit directly, then offer undo** | The person sees inverted signs or the wrong account only after hundreds of rows have landed among their own. Undo exists (decision 5) as a second line of defence, not the first |
| **An HMAC'd digest, or a signed preview token** | There is no attacker it would stop — see point 4 — and a key is one more secret to provision, rotate and keep out of logs on a self-hosted box |
| **An `Idempotency-Key` header** | Nothing in the backend implements one today. The digest already identifies the commit exactly, so a second mechanism would be a second source of truth for the same fact |

## Consequences

**Good.** Abandoning a preview leaves nothing behind, so there is nothing to expire, back up or
delete. What is written is always what the server read from the file. A restart between preview
and commit costs nothing but a stale-preview message. Concurrent imports of the same file are safe
by construction, because the commit re-classifies and the unique indexes are the final arbiter.

**Bad / costs.**

- **The file is uploaded twice.** At the 5 MiB cap that is acceptable on a phone, but it is real
  bandwidth and it doubles the parse cost of every import. The rate limit counts previews and
  commits together for that reason.
- **The parser must be deterministic.** Anything that varies between two parses of the same bytes
  — a hash-ordered map, a clock read inside the parser, an unstable sort — turns into a spurious
  `409`. The digest is computed from file order, and the only clock is the injected one used for
  the date range.
- **A digest computed on one release may not match the next.** A parser fix that changes how a row
  reads makes a preview taken before an upgrade stale after it. That is the correct answer.
- **The client must keep the file in memory** between the two requests. The web client holds it in
  a variable and never in storage; the mobile client must do the same.

**Follow-ups.** The mobile client (slice 15) implements the same two calls. If the size cap is ever
raised far enough that a second upload matters, revisit this ADR rather than adding a cache.
