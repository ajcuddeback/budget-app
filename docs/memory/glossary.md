# Glossary

What words mean **in this app**. When a term is ambiguous in general use, this file is the
tiebreaker. Add with `/remember`.

| Term | Meaning here |
|---|---|
| **Account** | A container where money sits — checking, savings, credit card, cash. *Not* a user login. When you mean the login, say **user** or **user account**. |
| **Amount** | A signed `Money` value. Negative is an outflow. Never an unsigned number plus a separate direction flag. |
| **Balance** | Derived: opening balance plus the sum of an account's transactions. Never a stored mutable column. |
| **Bill** | A named obligation that recurs — rent, a subscription. First-class since ADR-0025. Its per-period instance is a **BillOccurrence**, which carries the due date, the expected amount, a status, and what was actually paid. |
| **BillOccurrence** | One instance of a bill in one period. Planned and actual are both kept. Not a `Transaction` — it contributes to the plan, never to the ledger (ADR-0025). |
| **ChargeRule** | The mapping from a bill to the real charge that pays it. Lets a status set itself where an account is connected; entirely optional. |
| **Category** | A classification for spending or income. Two levels maximum. |
| **Cleared** | A transaction the bank has actually processed, as opposed to `PENDING`. |
| **Envelope** | An amount you decided on and spend down — groceries, fuel, adhoc. First-class, and the primary planning tool for variable spending. Entries are logged by hand; remaining is computed live. Not a `Bill`: a bill is owed on a date, an envelope is chosen. |
| **Household** | The ownership root for all financial data (ADR-0017). **Exactly one per instance** (ADR-0026) — a solo user is a household of one, and its `OWNER` is the person running the server. Not a synonym for "user" or "account". |
| **HouseholdMember** | A user's membership of a household, carrying a role: `OWNER`, `MEMBER` or `VIEWER` |
| **Aggregator** | A third party providing bank feeds (SimpleFIN, GoCardless, Plaid). The **user** holds the credentials, not us (ADR-0020) |
| **Self-hoster** | Our primary user: someone running Budget Owl on their own hardware. Assume no service of ours is reachable |
| **Base currency** | The household's currency for roll-ups. Not what any transaction is stored in |
| **Display currency** | A per-member preference for what they see. Changes presentation only, never stored data (ADR-0022) |
| **Converted** | A figure derived from a rate on a date. Always labelled as such, never persisted, never the input to stored arithmetic |
| **Locale** | A per-member language and formatting choice. Independent of currency — English text with German number formatting is legitimate |
| **Income** | A transaction with a positive amount in a category of kind `INCOME`. Distinct from an **IncomeSource**, which is the *scheduled* arrival — salary every other Wednesday — and carries whether its figure is derived from history or entered by hand. |
| **Left over** | What remains after income, bills and envelopes are accounted for. A product term the designs use on Bills & Income — in code and in the API, prefer **net** or **available**. |
| **Money** | The value object: `BigDecimal` + currency. Never a bare number. |
| **Payee** | Who money went to or came from. Normalized so "STARBUCKS #1234" and "Starbucks" are one payee. |
| **Period** | A `YearMonth`. Serialized `"2026-08"`. Replaces the legacy month/year string pair. |
| **Reconciled** | A transaction confirmed against a bank statement. Stronger than `CLEARED`. |
| **Debt** | A balance being reduced, with terms. Its minimum payment appears in Bills & Income as a bill row and is counted once. |
| **Balance source** | Where a debt's balance came from: a linked statement, a figure the user confirmed, or Owl's estimate. Never presented identically — an inferred number and a confirmed one are different claims. |
| **Check-in** | The weekly four-step pass: what's new, categorize, bills, next week. Steps with nothing to answer are skipped. Persisted as a draft; nothing takes effect until it is finished. |
| **Owl** | The optional assistant. Runs in a self-hosted sidecar or does not exist on that instance (ADR-0027). |
| **Operator** | The person running the instance. Same person as the household `OWNER` (ADR-0026) — not a separate role. |
| **Safe to spend** | Money on hand minus bills still due minus what envelopes still hold, over the days remaining. Derived, never stored. |
| **Transfer** | Money moved between two of the user's own accounts. A linked pair of transactions sharing a `transferGroupId`. Never income or expense in reporting. |
| **User** | A person with credentials. **Not** the ownership root — a user reaches financial data only through household membership (ADR-0017). Say **user** or **user account** for the login; say **Account** only for a place money sits. |
