# Glossary

What words mean **in this app**. When a term is ambiguous in general use, this file is the
tiebreaker. Add with `/remember`.

| Term | Meaning here |
|---|---|
| **Account** | A container where money sits — checking, savings, credit card, cash. *Not* a user login. When you mean the login, say **user** or **user account**. |
| **Amount** | A signed `Money` value. Negative is an outflow. Never an unsigned number plus a separate direction flag. |
| **Balance** | Derived: opening balance plus the sum of an account's transactions. Never a stored mutable column. |
| **Bill** | Legacy term from the old app for a planned expense. **Do not use in new code** — it is a `Transaction`, or a `BudgetLine`, or a `RecurringTransaction`, depending on what is actually meant. |
| **Budget** | A user's plan for one period. Contains budget lines. |
| **BudgetLine** | A planned allocation of an amount to a category within a budget. |
| **Category** | A classification for spending or income. Two levels maximum. |
| **Cleared** | A transaction the bank has actually processed, as opposed to `PENDING`. |
| **Envelope** | A budgeting metaphor for a category with a balance that carries over. We use "budget line with rollover" — the metaphor is not in the code. |
| **Income** | A transaction with a positive amount in a category of kind `INCOME`. Not a separate entity — the legacy app had an `income` table; we do not. |
| **Leftover** | Legacy term for income minus expenses in a month. New term: **net** or **available**. |
| **Money** | The value object: `BigDecimal` + currency. Never a bare number. |
| **Payee** | Who money went to or came from. Normalized so "STARBUCKS #1234" and "Starbucks" are one payee. |
| **Period** | A `YearMonth`. Serialized `"2026-08"`. Replaces the legacy month/year string pair. |
| **Reconciled** | A transaction confirmed against a bank statement. Stronger than `CLEARED`. |
| **Transfer** | Money moved between two of the user's own accounts. A linked pair of transactions sharing a `transferGroupId`. Never income or expense in reporting. |
| **User** | The person and the ownership root. Every other entity traces to exactly one. |
