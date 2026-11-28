# ADR-0035: A debt payment is a plan record, not a transaction

- **Status:** Accepted
- **Date:** 2026-11-23
- **Deciders:** Repository owner

## Context

Slice 10 adds debts and a payoff plan. Paying a debt is money leaving an account, so the question
is unavoidable: does recording a payment against a debt write a row in the ledger?

The codebase has already answered the same shape of question twice, in opposite directions, and
this decision sits exactly between them.

**ADR-0025 said no, for bills.** A bill is a plan with occurrences; it projects a due date and an
expected amount, carries a status, and only becomes a ledger entry when real money actually moves.
Marking a bill paid records what the household says happened. It does not move money and it does
not create a transaction. The user guide states this in as many words: _"A bill is a plan, not a
payment."_

**ADR-0033 said yes, for transfers.** A transfer _is_ two linked transaction rows, one in each
account, because an account's balance is computed from the rows in that account — so each side has
to carry its own leg or the balances are wrong. A constraint trigger enforces that the pair exists,
and `TransactionService` refuses to delete or update half of one.

A debt payment looks like both and is neither:

- Like a transfer, it is real money that really left a real account. The account balance must
  reflect it.
- Like a bill, the debt's own balance is a **plan figure** that the household maintains, sourced
  from a statement (`balanceSource`), not computed by summing rows the way an account balance is.

The temptation is to write the transaction automatically, so that "money that left an account is
always in the ledger" holds without the user doing anything. That is the convention in most
budgeting apps.

## Decision

**Recording a payment against a debt does not write a transaction.** A `DebtPayment` is a plan
record. It carries a **nullable `transaction_id`** so a household may attach the ledger row they
already recorded, and that link is **evidence, not the source of truth**.

Concretely:

- `ON DELETE SET NULL` on `transaction_id`, in the column-list form `transactions.md` established
  for `category_id`. Deleting the ledger row **leaves the payment standing and does not move the
  debt balance.**
- The split between interest and principal is stored, not derived on read, because a later APR
  change must not rewrite what already happened.
- Marking a linked bill occurrence paid **prompts once** before writing a payment. Never silently.

## Why

**The debt screen must not write to your accounts behind your back.** A debt page that creates
ledger rows is a screen that edits data belonging to a different part of the app, in response to a
figure the user typed about something else. The household's accounts are the record of what
happened; the debt plan is a plan. ADR-0025 drew that line for bills and it is the same line.

**Automatic writes double-count the moment import arrives.** File import (slice 12) and bank
connections (16) will bring in the real payment from the bank. If logging a payment also wrote a
transaction, every household that connects an account later discovers two rows for every payment
they ever logged — and no way to tell which is which, because both are now "real". The optional
link makes the opposite true: the imported row is the one to attach.

**Transfers are different for a structural reason, not a stylistic one.** A transfer must be two
rows because account balances are _derived by summing rows_. There is no such forcing function
here: a debt's balance is anchored to a statement, not summed from payments. ADR-0033's reasoning
does not reach this case.

**The link still has to exist**, because the payment and the ledger row are the same event seen
from two sides, and a household reconciling their statement needs to say so. What the link must not
do is make one of them authoritative over the other.

## Consequences

- **The two screens can disagree, and that is allowed.** A household can log a payment and never
  record the transaction; the debt balance moves and the account balance does not. This is the same
  latitude bills already have, and the UI says which figures are plan and which are ledger.
- **Deleting a transaction cannot corrupt a debt.** The payment survives with a null link. The
  alternative — cascading the delete into the debt balance — would let an edit in the ledger
  silently change what a household believes they owe.
- **Nothing auto-reconciles.** Budget Owl never attaches a transaction to a payment on its own,
  however obvious the match looks. Same refusal as envelopes (ADR-0025's line, and what
  `log-spend-in-an-envelope.md` tells users): the match is the household's to make.
- **A reviewer will raise this as an inconsistency with transfers.** That is why this ADR exists.
  The inconsistency is real, deliberate, and justified by the fact that account balances are
  derived and debt balances are anchored.

## Alternatives considered

**Write the transaction automatically.** Rejected: see double-counting above, and because it makes
a debt screen a writer of ledger rows.

**Treat the pair as linked halves, as ADR-0033 does for transfers**, refusing to delete either
side. Rejected: it imports a constraint that exists to keep _account_ balances correct into a place
where no balance depends on it, and it makes correcting a mistyped payment a two-screen operation.

**An opt-in "also record this in my ledger" checkbox** on the log-payment sheet. Rejected, though
it is the most defensible alternative and was close. It is correct today and becomes a
double-counting generator the moment import or sync lands, at which point every household that
ticked it has a mess proportional to how diligent they were.
