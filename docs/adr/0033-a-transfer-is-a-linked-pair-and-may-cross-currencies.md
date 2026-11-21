# ADR-0033: A transfer is a linked pair of rows, and a cross-currency pair does not sum to zero

- **Status:** Accepted
- **Date:** 2026-11-21
- **Deciders:** Repository owner

## Context

Moving money between two of your own accounts is not income and not spending. It has been in the
data model since slice 5: `transactions.transfer_group_id` exists, `ix_transactions_transfer_group`
indexes it, the ledger already excludes transfers from the "needs a category" queue, and
`TransactionService.delete` already refuses to delete half of one. What has never been settled is
how a transfer is **created and edited** — slice 8 — and one question inside that has no obvious
answer.

`docs/domain/model.md` states an invariant:

> 3. A transfer's two legs always sum to zero and always share a `transferGroupId`.

That is true for two accounts in the same currency and **false** the moment they are not. A
household can hold a GBP current account and a EUR savings account — ADR-0022 exists precisely
because they can — and £200 leaving one does not make €200 arrive in the other. The two legs are
in different currencies, so they cannot sum to anything at all: `Money` arithmetic throws on mixed
currencies by design (ADR-0006).

The obvious escape is to convert, and we cannot. ADR-0022 already decided that amounts are stored
in the currency they happened in and are **never** rewritten by a conversion, and ADR-0016 forbids
the core from requiring any service we operate — so there is no rate to fetch, and on an offline
instance there never will be. Inventing one would mean writing a number into somebody's ledger
that no bank ever agreed to.

## Decision

**A transfer is two transaction rows sharing a `transferGroupId`, created, edited and deleted as
one thing. Each leg is denominated in its own account's currency, and the household supplies both
amounts when those currencies differ.**

- **Same currency**: one amount is asked for. The legs are equal and opposite, generated from it.
- **Different currencies**: both amounts are asked for, because **the household is the only party
  that knows what actually landed**. The bank's rate, its fee and the day it settled are facts
  about the real world that this app has no access to; the figure that arrived is on a statement
  the person is holding, and typing it is less work than explaining why our number disagrees
  with it.
- **No rate is stored.** An implied rate can be shown — it is a division — but it is derived for
  display and is never an input to anything, exactly as ADR-0022 requires of every converted
  figure.
- **A transfer has no category and no payee.** It is not spending and the other side is not a
  third party. The columns stay null and the API refuses both.
- **Both legs appear in the ledger**, because each account's own ledger has to show its own
  movement — an account whose balance changed for no visible reason is a bug report. They are
  marked as a transfer and are already excluded from income, spending and the uncategorised queue.
- **Neither leg can be edited or deleted on its own.** `PATCH`/`DELETE` on a transaction that has
  a `transferGroupId` is refused; the transfer's own endpoints rewrite or remove the pair inside
  one transaction.

### The invariant, amended

Invariant 3 in `docs/domain/model.md` becomes:

> A transfer is exactly two rows sharing a `transferGroupId`, in two different accounts of the
> same household, with opposite signs. Where both accounts share a currency the two amounts are
> equal in magnitude. Where they do not, each leg is the true figure for its own account and the
> pair does **not** sum to zero — which is not a defect but the absence of a lie.

The part that holds in every case, and the part worth enforcing, is **two rows, two accounts,
opposite signs**. That is what the database checks.

## Alternatives considered

| Option                                                              | Why not                                                                                                                                                                                                                                                                              |
| ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **One row with `fromAccountId` and `toAccountId`**                  | Every balance query, every ledger, every filter and every report would need to know that one row sometimes means two movements, and would be wrong on the day one of them forgot. Two rows means an account's balance is the sum of its transactions and nothing special is needed |
| **Refuse cross-currency transfers**                                 | The household does the transfer whether or not we support it, and then records it as a withdrawal and a deposit that are not linked — which is worse than what we are trying to prevent, and silently reappears in reporting as income                                              |
| **Convert with a stored rate**                                      | The rate must come from somewhere. Fetching it breaks ADR-0016; asking for it and then deriving the second amount produces a figure that disagrees with the statement, because the bank's rate included a fee we do not know. The person has the real number in front of them       |
| **Store a `rate` column on the pair**                               | It would be a derived figure sitting in the database inviting someone to multiply by it. The two amounts are the facts; a rate is a division anybody can do at read time, and ADR-0022 is explicit that a converted figure is never the input to anything                           |
| **Keep the legs equal and record the loss as a separate fee row**   | Requires knowing the fee, which is exactly what the bank does not tell you — the fee is usually *inside* the rate. It would also put a fabricated expense in the ledger                                                                                                              |
| **One leg, and infer the other from the account pair at read time** | The second account's balance would then depend on a join rather than on its own rows, breaking invariant 2 (balance = opening balance + its transactions) for one account and not the other                                                                                        |

## Consequences

**Good.** Nothing in the ledger is ever a number we made up. Balances stay exactly "opening balance
plus this account's transactions" in every currency. A cross-currency transfer is recorded as
honestly as a same-currency one, with no rate source, no fee model and no internet. Reporting
(slice 13) can keep excluding transfers with a single `transfer_group_id is not null`.

**Bad / costs.** The create form has a second amount field whenever the two accounts differ, which
is a field most people will never see and a conditional in the form that has to be right. The
"amount" of a cross-currency transfer is not a single number, so any screen that wants to show one
must choose a side and say which — and a household roll-up cannot net the pair out without
converting, which it may not be able to do. Invariant 3 is now conditional, and a conditional
invariant is weaker than an absolute one; the database therefore enforces the part that is
absolute and the service enforces the rest.

**Follow-ups.** Reporting must exclude both legs, not net them. Import (slice 12) will meet two
rows that are really one transfer and has no way to know it — matching imported pairs is its own
problem and is not solved here.
