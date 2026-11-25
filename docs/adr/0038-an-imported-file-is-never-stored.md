# ADR-0038: An imported file is never stored — not its bytes, not its name, not its hash

- **Status:** Accepted
- **Date:** 2026-11-25
- **Deciders:** Repository owner

## Context

A bank statement carries far more than the ledger keeps: the full account number, the sort code or
routing number, often the holder's name and postal address, running and available balances, and in
some QFX files the person's online-banking username (`INTU.USERID`). ADR-0028 deliberately limited
the account identifier Budget Owl stores to four digits.

Keeping the uploaded file is tempting for reasons that sound responsible: re-processing after a
parser fix, "debugging a bad import", showing "you imported chase-aug.ofx yesterday". Each of those
is one setting or one column away, and none of them looks dangerous on its own. That is exactly
why this needs to be written down: the decision is easy to erode one well-meant change at a time.

Everything the API stores is in every backup, and the person who runs the instance can read all of
it (ADR-0026). The log stream leaves the box in bug reports.

## Decision

**The uploaded file exists in the API process's memory for the length of one request, and then it
is gone.** It is never written to the database, to a volume, to a temp file or to a log. Neither is
its name, nor any hash of it.

What makes that true rather than merely intended:

1. **The file travels as a base64 field in a JSON body**, never as `multipart/form-data`. Spring
   Boot's multipart support spools parts to `java.io.tmpdir` by default, so a multipart upload would
   leave statements in the container's `/tmp`, which nothing reads and nobody would notice.
2. **There is no file-name field** in any request. An unknown property is refused, so a client
   cannot send one.
3. **No column holds file content, a file name or a file hash.** `import_batches` stores the
   account, who imported, the format, the counts, the preview digest (a hash of the *parsed
   classification*, used for idempotency — ADR-0037) and the time. Nothing else.
4. **What is read from the file and then discarded is enumerated** in the feature doc: `ACCTID`,
   `BANKID`, `BRANCHID`, `SIGNONMSGSRSV1`, `INTU.USERID`, `AVAILBAL`, the bank's closing balance and
   the rest. `ACCTID` is compared in memory with the account's `lastFour` to produce a three-valued
   hint and is never stored, logged or returned. The closing balance is returned to the person who
   uploaded it and never stored.
5. **Nothing from the file is logged.** Every request, response and internal record in the import
   package has a redacted `toString`, parser exceptions are replaced by fixed reason codes, and
   `ImportDisclosureIT` plants sentinels in every field and asserts none reaches the log at `INFO`
   or `DEBUG`.
6. **No heap dump is configured** (`backend/Dockerfile`). An operator who adds one accepts that a
   dump taken mid-import contains the file.

## Alternatives considered

| Option | Why not |
|---|---|
| **Store the raw file** for re-processing after a parser fix | Undoes ADR-0028 for every imported account: the full account number, routing number, holder name and address would sit in the database and every backup. The person can always re-download the statement from their bank, which is the accepted cost |
| **Store it temporarily** "for debugging a bad import", with an expiry | An expiry is a job that must run, a backup taken before it runs keeps the file for ever, and household deletion must remember it. "How long do you keep my statements?" deserves the answer "we don't" |
| **Store the file name** for the import history | File names carry account numbers and holder names (`chase-4412-ada-lovelace-aug.ofx`). Decision 4: history shows format, account, counts and time |
| **Store a hash of the file** to say "you imported this before" | Tier-1 duplicate detection already makes a re-import visible row by row and adds nothing. A hash would be one more stored artefact with no other use, and it identifies a file across instances |
| **Multipart upload with an in-memory threshold** | Possible, but it adds a second request parser with its own limits to configure and keep configured, and the default is the unsafe one. One forgotten property puts statements on disk |

## Consequences

**Good.** The instance holds no more about an account than ADR-0028 allows, whatever is imported.
Nothing needs expiring, nothing extra lands in backups, and household deletion has nothing new to
remember. "We don't keep your statements" is true and checkable.

**Bad / costs.**

- **A file imported with a parser bug cannot be re-processed after the fix.** The person
  re-downloads it from their bank and imports it again; tier-1 duplicates make that safe.
- **Support cannot ask for "the file you imported"** from the instance. A bug report about a bad
  import needs the person to share a redacted file themselves.
- **Transient heap.** Base64 in JSON costs about 33% on the wire and roughly 3–4× the file size in
  heap while decoding, bounded by the 5 MiB cap and the two-parses-per-instance limit.
- **A heap dump taken during an import contains the file**, if an operator enables heap dumps. That
  is accepted and documented rather than prevented.

**Follow-ups.** Bank connections (slice 16) will be tempted to keep raw provider payloads, as the
canvas's Admin · Storage page suggests. This ADR does not decide that, but slice 16 should have to
argue against it.
