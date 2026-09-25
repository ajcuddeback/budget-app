repo: ajcuddeback/budget-app
branch: main

## Last sync

date: 2026-08-29T13:30:00Z

### Updated in this project

- Read the docs-only repo (no `frontend/` or `backend/` code exists yet) and designed round-one UI against its domain vocabulary.
- Screens use the repo's model names: Account, Transaction, Category, RecurringTransaction, Goal, Period (`2026-08`), Money as a string.
- Followed the repo's stated rules: pending/cleared transaction status, transfers excluded from income/expense, no auth state in local storage.

## Screen map

| Screen | Built from |
| --- | --- |
| Mobile home, web home | docs/domain/model.md (Budget, BudgetLine, Goal), docs/roadmap.md |
| Transactions | docs/domain/model.md (Transaction, Payee, Category) |
| Recurring bills + charge mapping | docs/domain/model.md (RecurringTransaction, Payee) |
| Insights / analytics | docs/roadmap.md slice 9 (Reporting & insights) |
| Debt plan (1d) | New — no repo doc yet |
| Owl chat sheet | New — no repo doc yet |
| Onboarding / connect accounts | docs/features/accounts-and-auth.md, docs/architecture/security-model.md |
