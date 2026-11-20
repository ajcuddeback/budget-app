# Feature: Categories

- **Slice:** 4
- **Status:** Shipped
- **Depends on:** authentication-and-households (slice 1)
- **Depended on by:** transactions (slice 5), envelopes (slice 6), bills (slice 7), reporting (slice 13)

## Purpose

A category is how a household says what its money was **for**. On its own it does nothing; it
exists so that transactions can be grouped, envelopes can be set against something, and reporting
has an axis other than time.

It ships one slice ahead of transactions for one reason: **a transaction must be able to have a
category from the first one ever recorded.** Shipping them the other way round would mean either a
release where categorising is impossible, or a migration that back-fills categories onto history.

## User stories

- As a member, I want a sensible set of categories on day one, so I can start recording spending
  without designing a taxonomy first.
- As a member, I want to rename and reorganise them, because "Groceries" means different things in
  different households.
- As a member, I want to retire a category I no longer use **without** breaking the history filed
  under it.
- As a member, I want to delete one I created by mistake, and I want to be certain that does not
  delete any money.

## Rules and behavior

1. **Two levels, no deeper.** A category is top-level or a child of a top-level one. A child's
   parent must itself be top-level, and a category that already has children may not become a
   child. Both directions are enforced by `trg_categories_two_levels` in `V11`, not only by the
   service — the rule is about another row, which a `CHECK` cannot see.
2. **A child takes its parent's kind.** `INCOME` or `EXPENSE`. A create that states a kind
   contradicting the parent's is `400`, never a silent preference for one of them.
3. **A kind is fixed at creation.** Re-kinding a category in use would reinterpret every
   transaction already filed under it — a year of salary becomes a year of spending. `PATCH`
   cannot express it; the field is absent from the contract rather than present and refused.
4. **Names are unique per household per level, case-insensitively.** Two "Groceries" at the top
   level are a data-entry mistake. `Bills → Phone` and `Transport → Phone` are both reasonable, so
   the parent is part of the key (`ux_categories_household_parent_name`).
5. **Archiving keeps history readable.** An archived category vanishes from every picker and from
   the default list, and past transactions still show it.
6. **Deleting never deletes money.** Its children go with it (`fk_categories_parent` cascades), and
   its transactions are set back to "Needs a category" by
   `fk_transactions_category ON DELETE SET NULL (category_id)`. That is a database behaviour rather
   than a loop in the service, so it holds for an import path too.
7. **A household is capped at 200 categories**, which is the API's maximum page size. The whole
   tree therefore always fits in one response and a picker never has to page.

## Data model

`categories` (`V11__categories.sql`): `id`, `household_id`, `name`, `kind`, `parent_id`,
`archived`, `created_at`, `updated_at`, `version`.

- A **direct** `household_id` (ADR-0017), so the scoping predicate is one condition on the row.
- `uq_categories_id_household` exists only as a composite FK target, so `transactions` can
  reference `(category_id, household_id)` together — see transactions.md.
- `ix_categories_household_kind_name` answers the listing's filter _and_ its order.

### The default set

Ten top-level categories and twelve children, in
`DefaultCategories.ALL`: Income (Salary, Other income), Bills (Utilities, Rent or mortgage, Phone
and internet, Insurance, Subscriptions), Groceries, Transport (Fuel, Transit, Car), Eating Out,
Shopping, Health (Pharmacy, Medical), Entertainment, Home, Other.

The vocabulary comes from the Money screen in the design system, so the app a household opens on
day one matches the screens the product was designed around. It is deliberately short: a list
somebody has to prune before they can use it is worse than one they add to.

**This list exists twice and cannot be deduplicated.** New households are seeded by
`CategoryService.seedDefaultsFor`, inside the transaction that creates the household — a household
is never created with half a list, or none. An instance that was already running when categories
shipped is seeded by `V11` itself, because no application code runs for a household that already
exists. Neither copy can read the other, so `DefaultCategoriesTest` parses the migration and fails
the build if they drift.

## API

| Method   | Path                             | Notes                                    |
| -------- | -------------------------------- | ---------------------------------------- |
| `GET`    | `/api/categories`                | Flat, paginated. `?includeArchived=true` |
| `POST`   | `/api/categories`                | `OWNER` / `MEMBER`                       |
| `GET`    | `/api/categories/{id}`           |                                          |
| `PATCH`  | `/api/categories/{id}`           | `name`, `parentId`; requires `version`   |
| `POST`   | `/api/categories/{id}/archive`   | Idempotent                               |
| `POST`   | `/api/categories/{id}/unarchive` | Idempotent                               |
| `DELETE` | `/api/categories/{id}`           | `204`. Children go; transactions stay    |

**The tree arrives flat.** Every collection is paginated (`docs/guides/api-style.md`) and a page
boundary must not be able to fall between a parent and its children, so each row carries `parentId`
and the client assembles the tree. The per-household cap is what makes that safe.

`parentId` on `PATCH` is three-way: absent keeps the parent, `null` moves it to the top level, an
id moves it under that category. Collapsing absent and `null` would mean a client that patched only
the name silently promoted every child it edited.

Error codes: `category-name-taken`, `category-depth-exceeded`, `category-limit-reached`,
`category-kind-immutable`, `stale-version`.

## UI

`/categories`. Income and spending are separate groups; children are indented under their parent
with a rule rather than a bullet, so the two levels read as a hierarchy without relying on colour.
Archived categories are behind a disclosure that states how many there are.

The delete confirmation is the screen's most important sentence: it says **your transactions are
kept**, and, when the category has children, that those go too. That is the thing somebody is
actually afraid of, answered before they commit rather than after.

The dialog disables rather than hides: an existing category's kind is shown greyed with the reason,
and a category with children is told why it cannot be moved.

## Security considerations

- Every query is scoped by the household resolved from the caller's verified membership. A
  category id in a path is a lookup key; another household's is `404`, never `403`.
- The trigger also checks that a child's parent belongs to the **same household**, so nesting under
  another household's category cannot leak its name even if the service were wrong.
- A `VIEWER` may read and never write; `MembershipService.requireWriteAccess` guards every write.
- Nothing logs a category name. Audit lines carry ids.

## Edge cases

- **An archived parent with unarchived children** is ordinary, and the default listing excludes the
  parent. The client promotes such a child to the top level rather than dropping it — losing a row
  from a financial screen is never the right failure.
- **Two members adding the same name at once**: the unique index decides, and the loser gets
  `409 category-name-taken`. A look-before-you-leap check would let both through.
- **Deleting the category a transaction is filed under** leaves the transaction in "Needs a
  category", which is a state the UI already shows.

## Out of scope

- Reordering by hand. The order is by name; a `sortOrder` column is a real request but it is not
  what makes the app usable.
- Merging two categories, and bulk re-filing. Both want reporting to show why they matter.
- Per-category colours and icons. The design system has no vocabulary for them yet.
- Localised default names — see Open questions.

## Open questions

- **The defaults are English.** ADR-0023 puts runtime locale on the display layer, but these are
  user-editable _data_, not labels, so they are seeded as typed. A household in another language
  renames them, which is one of the two things this screen is for. Seeding in the household's own
  language needs the locale at creation time and a translation per category; worth doing, not yet
  done.
- **Four buttons per row** (Add a sub-category, Edit, Archive, Delete) make a long list read as a
  wall of buttons. It matches the accounts screen, so it is consistent rather than wrong, but a
  menu may be the better pattern once more rows exist. A design call, deliberately not made
  unilaterally here.

## Testing notes

- `CategoryIT` — the rules that only a real database proves: the depth limit in both directions,
  case-insensitive name collision against the index, the same name under two different parents, and
  the one that would hurt most if wrong — **deleting a category keeps its transactions**, checked by
  reading the transaction back afterwards.
- `DefaultCategoriesTest` — parses `V11` and compares names, kinds and parentage against
  `DefaultCategories.ALL`. Counting would pass while the vocabulary diverged.
- `categories-data.spec.ts` — the tree assembly, including the promoted orphan, and the three-way
  `parentId` patch.
- `categories.page.spec.ts`, `tools/ui/specs/app/categories.spec.ts` — the screen, in both
  viewports, with axe.
