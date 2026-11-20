# Getting started

> **Status: partly written.** Steps 1, 1b, 2 and 6 below are written from the running app. The
> rest are still outlines, and each gets written, with real screenshots, once somebody has run
> `tools/userguide-capture.sh` against the screen it describes. See `docs/roadmap.md` for the
> order.
>
> **Do not write steps here from the feature docs.** Feature docs describe what we intend to
> build; a user guide describes what shipped. Write each section only after running
> `tools/userguide-capture.sh` and looking at what is actually on screen.

Budget Owl helps you see where your money goes and plan where it should go. This walks you
through your first fifteen minutes, from creating your household to reading your first month's
budget.

**Already installed?** Good — start at step 1. If not, see
[Installing Budget Owl](installing.md) first.

## 1. Create your account and household

The first time Budget Owl starts, it asks you to create one account. That account becomes the
**owner** of a new household — the shared space everything else lives in.

1. Open Budget Owl in your web browser, at the address of your server.

   You'll see **Set up Budget Owl**.

2. Fill in **Your name**, **Email address**, **Password** and **Household name**.

   The password needs at least 12 characters. **Base currency** starts as **USD — US Dollar**;
   change it if your household uses another.

3. Select **Create account**.

   You are signed in, and you land on the **Household** screen with yourself listed as **Owner**.

![The Set up Budget Owl screen: a notice about who can see what, then the form for your name, email address, password, household name and base currency.](images/create-your-account--setup-form.png)

**You will be able to see everything anyone in your household records.** You are the person
running this instance, so every transaction, every balance and every note in it is visible to you.
Budget Owl does not hide it, and anyone you invite is told this before they join.

Full steps, and what to do when something goes wrong:
[Create your account and household](features/create-your-account.md). Next time, you
[sign in](features/sign-in.md) instead. If your server is set up to use your own login provider,
that option is not covered here yet — email and password is.

## 1b. Invite the rest of your household

Budget Owl does not send email. You create an invitation link and send it to the person yourself.

1. Go to **Household** and select **Invite someone**.

2. Enter their email address, choose their role, and select **Create link**.

   You'll see the link, and a warning: **Treat this link like a password.**

3. Select **Copy link**, and send it to them.

   ![The Invite someone window showing the invitation link, with the warning "Treat this link like a password" outlined.](images/invite-someone--invite-link.png)

**The link works like a password.** Anyone who has it can join your household, so send it only to the
person you mean, and revoke it if it reaches anyone else. It works once and expires on the date shown
under it.

**The roles:**

- **Owner** — everything, including inviting and removing people.
- **Member** — can see and change the household's money.
- **Viewer** — can see the household's money, but not change it.

The person who runs the server can see everything your household records, whatever anyone's role.
The person you invite is told this before they join.

Full steps: [Invite someone to your household](features/invite-someone.md). What the person you
invite goes through: [Join a household](features/join-a-household.md). Changing roles, removing
people, and leaving: [See who is in your household](features/manage-members.md).

## 2. Add your accounts

Tell Budget Owl where your money sits. Do this once for each account, and everything you record
later belongs to one of them.

1. Select **Accounts** in the menu across the top, then select **Add your first account**.

2. Fill in **Name**, choose the **Type** and the **Currency**, and scroll down inside the window
   to **Opening balance**.

   **Last four digits** is optional — it is shown beside the name, like **Travel Card •8820**, so
   you can tell two accounts at the same bank apart.

3. Select **Save account**.

   You'll see **Account added.** and the account in the list, with its balance.

   ![The Accounts screen with the message "Account added." and accounts grouped under Euro (EUR) and US Dollar (USD), each group with its own subtotal.](images/add-an-account--added.png)

**The opening balance is what the account held when you started tracking it** — usually the
balance your bank shows you today. **An account you owe money on starts with a minus:** a credit
card with 420.50 outstanding opens at **-420.50**, and its balance stays negative until you pay
it off. This is the step people most often get wrong, and getting it wrong leaves every later
balance out by the same amount.

Once you have accounts in more than one currency, they are grouped by currency and each group
gets its own **Subtotal**, as above. There is no total across currencies, on purpose: adding
dollars to euros needs a rate and a date, and the answer would be a guess printed as a fact.

Full steps: [Add an account](features/add-an-account.md). Closing an account later:
[Archive or delete an account](features/archive-or-delete-an-account.md).

## 3. Set up your categories

*Awaiting slice 4.*

Will cover: the categories you start with, renaming them to match how you actually think about
your money, and adding your own.

## 4. Record your first transactions

*Awaiting slice 5.*

Will cover: adding an expense, adding income, categorising as you go, and how your account
balance updates.

## 5. Give your spending an envelope

*Awaiting slice 6.*

Will cover: setting an amount for groceries, logging what you spend against it as you go, and
reading the one number that matters — what is left. Works whether or not you ever connect a bank.

## 6. Put in your bills and income

Rent, subscriptions and payday are the predictable half of your money. Put them in once and they
appear in every month they fall in — and Budget Owl can tell you what's left.

1. Select **Bills** in the menu across the top.

   You'll see **Bills & income**, opened on the month you're in.

2. Select **Add an income source**, and enter a name, what arrives **each time**, and when.

   The month's total is worked out from the schedule, so something arriving every other week is
   worth more in the months that hold three arrivals.

3. Select **Add a bill**, under **Your bills**, and enter a name, the amount you expect, and when
   it's due.

   Monthly, yearly, weekly, every other week, or irregular — for the vet, where there's no date to
   predict.

4. Select **Mark paid** on a bill's row when you pay it.

   Each date gets its own row, so a weekly bill is ticked off a week at a time.

![The What's left this month card: $980.90 left to spend, with income expected $3,640.00, bills this month $1,959.10, set aside in envelopes $700.00, and still to pay $266.00 of $1,959.10.](images/see-whats-left-this-month--whats-left.png)

**A bill is a plan, not a payment.** Marking one paid records what your household says happened.
It doesn't move money and doesn't create a transaction — only a transaction is ever money that
really went.

**Bills count towards the month whether or not you've paid them.** Rent that has already gone is
not money you still have, so ticking it off doesn't make **What's left this month** go up. The
**Still to pay** line is the one that drops.

Full steps: [See what's left this month](features/see-whats-left-this-month.md),
[Add your income](features/add-your-income.md), [Add a bill](features/add-a-bill.md) and
[Mark a bill paid](features/mark-a-bill-paid.md).

## 7. Move money between your own accounts

*Awaiting slice 8.*

Will cover: recording a transfer, and why a transfer is neither income nor an expense — the
thing people most often get wrong and then wonder why their totals look off.

## 8. Make a plan for what you owe

*Awaiting slice 10.*

Will cover: entering a debt's terms, choosing between paying the highest rate first or the
smallest balance first, and seeing what each one does to the date you are finally clear.

## 9. See where your money went

*Awaiting slice 13.*

Will cover: reading the reports, spotting the category that surprised you, and comparing months.

## 10. Put it on your phone

*Awaiting slice 15.*

Will cover: installing the mobile app, pointing it at your own server, and adding a transaction
while you are standing in the shop — which is where most budgeting actually happens.

## Where to go next

Once these exist, each section links to the full guide for that feature in
[the help index](README.md).
