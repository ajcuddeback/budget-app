# Log a payment against a debt

When you've paid a lender, write it down here. Budget Owl works out how much of it went on interest
and how much actually came off the balance, and keeps a row for every payment so the balance has a
history rather than being a figure somebody typed.

**Logging a payment doesn't touch your accounts.** It moves no money and creates no transaction —
Budget Owl never claims money moved on your behalf. If you want the payment in your own records as
well, [record it as a transaction](record-a-transaction.md).

## Before you start

- You need a debt. If you haven't added one, start with [Add a debt](add-a-debt.md).
- You need to be an **owner** or a **member**. A **viewer** sees every payment ever recorded, and
  can't add one.

## Steps

1. Select **Debt plan** in the menu across the top, then **Open** on the debt you've paid.

2. Select **Log a payment**.

   ![The debt's page with Log a payment circled, beside Edit terms, under the debt's name.](../images/log-a-debt-payment--log-button.png)

3. Check **Amount paid**.

   ![The Log a payment to Visa window: Amount paid filled in with 445, two one-tap alternatives beneath it, then Date paid and Paid from.](../images/log-a-debt-payment--sheet.png)

   It's filled in with what your plan puts against this debt. Change it if you paid something else.
   The two buttons underneath fill the box for you: **$445.00 — what is scheduled**, and **$495.00**
   — fifty more, for a month you can put a bit extra at it.

4. Change **Date paid** if it wasn't today.

5. Choose the account the money left, in **Paid from**.

   ![The Paid from box circled, with the note that nothing is recorded against that account and no transaction is created.](../images/log-a-debt-payment--paid-from.png)

   **Nothing is written to that account.** Its balance does not move, and no transaction appears in
   your ledger. All this does is remember where the money came from, so that a real charge can be
   matched to this payment later. **Somewhere I do not track** is a perfectly good answer.

6. Add a **Note** if you want one.

   ![The foot of the window: a Note box, and the line saying Budget Owl works out how much went on interest and how much came off the balance.](../images/log-a-debt-payment--split-note.png)

7. Select **Record it**.

   ![A banner reading Payment recorded: $123.73 interest, $321.27 principal. Balance now $6,162.62.](../images/log-a-debt-payment--recorded.png)

   The banner tells you the split Budget Owl worked out and what the balance is now. The payment
   appears at the top of **Payment history**, and the balance on the page has already changed to
   match.

## What you'll see

**The split is the server's arithmetic, not a guess in your browser.** Budget Owl doesn't show you
an interest figure before the payment is recorded — it tells you what it's going to do, then reports
what it did. That way there is only ever one version of the sum.

![The Payment history table: date, paid, interest, principal, balance after, and how each row got here — six You logged it rows and one Statement correction.](../images/log-a-debt-payment--history.png)

Every row says **how it got here**, so a figure a person entered is never mixed up with one Budget
Owl worked out:

- **You logged it** — somebody in your household recorded this.
- **Statement correction** — the balance was put right from a statement, and the difference was
  written down rather than tidied away. See [Confirm what you owe](confirm-what-you-owe.md).

**Balance after** reads down the column to the figure at the top of the page, so you can follow the
balance back through every payment.

The line under the table is the rule the splits come from: _"Interest is the balance times the annual
rate, divided by twelve, until a statement says otherwise — then the statement wins and the gap is
recorded as a correction. It is a simplification you can check with a calculator, and it is not what
your lender will charge to the penny."_

## Recording a payment you made a while ago

**Add a past payment**, at the top of **Payment history**, opens the same window. Set **Date paid**
to the day it went, and Budget Owl works the split out against the balance as it stood then.

## What the rest of the page tells you

![The whole page for one debt: the name and lender, the rung switcher, Log a payment and Edit terms, then Loan terms, Balance now, Balance over time and Payment history.](../images/log-a-debt-payment--page.png)

Four cards, top to bottom, and the buttons at the top work on whichever debt you're looking at. The
row of links above them — **1 Visa**, **2 Car loan**, **3 Student loan** — steps between your debts
in the order your plan is paying them.

![The Loan terms card: what it started at, the balance now, the annual rate, the minimum payment, what you are paying, months left, when it is gone and the interest still to come.](../images/log-a-debt-payment--terms.png)

**Loan terms** is the whole debt in eight figures, and it labels each one so you always know who
said it:

- **No tag** — you entered it. The balance, the rate and the minimum are yours.
- **Solved** — Budget Owl worked it out. **Months left**, **Gone by** and **Interest still to come**
  are arithmetic, not promises.
- **From your plan** — **What you are paying** is what the ladder on the
  [debt plan](choose-how-to-clear-your-debts.md) allocates to this debt.

The card says it in its own words at the top: _"Give me any three of the rate, the payment and the
horizon and I will work out the fourth."_ **The balance is never one of them** — it's the one number
you can read off a statement, so it only changes when you say so.

![The Balance now card: $6,180.00 owed now, and $4,470.00 paid off, 42% of $10,650.00.](../images/log-a-debt-payment--balance.png)

**Balance now** leads with what you owe and, underneath, how far you've come. That second line only
appears if you told Budget Owl what the debt started at.

![The Balance over time card: a solid line for what you are paying and a dashed one for the minimum, with the sentence that paying $260.00 extra is buying you 37 months.](../images/log-a-debt-payment--over-time.png)

**Balance over time** draws what you're paying against what the minimum alone would do, and says
what the difference buys: _"Paying $260.00 extra is buying you 37 months: gone by December 2027
instead of January 2031."_ The dashed line is kinder than reality, and the card says so — a real
card minimum falls as the balance falls, which stretches it out further still.

## Things that can go wrong

**"I logged a payment and my account balance didn't change."**
That's how it works. A debt payment is a statement about a balance a lender holds, not a movement in
your own ledger. **Paid from** only records where the money came from. To change an account's
balance, [record a transaction](record-a-transaction.md).

**"The balance went down by less than I paid."**
The rest went on interest. The **Interest** and **Principal** columns in the history show the split
for every payment, and the two always add up to exactly what you paid.

**"The interest figure isn't what my lender charged."**
It won't be, to the penny. Budget Owl uses the balance times the annual rate divided by twelve,
which is simple enough to check with a calculator; real lenders work it out daily and bill on
statement cycles. When a statement disagrees, the statement wins —
[Confirm what you owe](confirm-what-you-owe.md).

**"There's no Log a payment button."**
Your role is **Viewer**. You can read every payment, and an owner or member records them.

## Related

- [Confirm what you owe](confirm-what-you-owe.md)
- [Choose how to clear your debts](choose-how-to-clear-your-debts.md)
- [Add a debt](add-a-debt.md)
- [Record a transaction](record-a-transaction.md) — the money that actually moved
