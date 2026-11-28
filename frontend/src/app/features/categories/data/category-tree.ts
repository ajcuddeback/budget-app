import { Category, CategoryKind } from './category.models';

/** A top-level category with its children, which is how the screen and every picker read it. */
export interface CategoryBranch {
  readonly parent: Category;
  readonly children: readonly Category[];
}

/** One kind's branches, so income and expense are never mixed in a list or a picker. */
export interface CategoryGroup {
  readonly kind: CategoryKind;
  readonly branches: readonly CategoryBranch[];
}

const byName = (left: Category, right: Category): number =>
  left.name.localeCompare(right.name) || left.id.localeCompare(right.id);

/**
 * Assembles the flat list the server sends into the two-level tree it represents.
 *
 * **A child whose parent is not in the list is promoted rather than dropped.** That happens for
 * real: the default listing excludes archived rows, and an archived parent with unarchived children
 * is ordinary. Dropping them would hide a category that still has transactions filed under it,
 * which is worse than showing it a level up — and silently losing a row from a financial screen is
 * never the right failure.
 *
 * Sorted by name with a tiebreak on id, matching the server's own order, so the list does not
 * reshuffle between a render from cache and a render from a response.
 */
export function buildCategoryTree(categories: readonly Category[]): readonly CategoryBranch[] {
  const byId = new Map(categories.map((category) => [category.id, category]));
  const parents = categories.filter(
    (category) => category.parentId === null || !byId.has(category.parentId),
  );
  const childrenOf = new Map<string, Category[]>();
  for (const category of categories) {
    if (category.parentId !== null && byId.has(category.parentId)) {
      const siblings = childrenOf.get(category.parentId) ?? [];
      siblings.push(category);
      childrenOf.set(category.parentId, siblings);
    }
  }

  return parents
    .slice()
    .sort(byName)
    .map((parent) => ({
      parent,
      children: (childrenOf.get(parent.id) ?? []).slice().sort(byName),
    }));
}

/** Income first, then expenses — the order the screens were designed in. */
export function groupByKind(categories: readonly Category[]): readonly CategoryGroup[] {
  const order: readonly CategoryKind[] = ['INCOME', 'EXPENSE'];
  return order
    .map((kind) => ({
      kind,
      branches: buildCategoryTree(categories.filter((category) => category.kind === kind)),
    }))
    .filter((group) => group.branches.length > 0);
}

/**
 * The categories a picker may offer, flattened with children following their parent.
 *
 * Archived categories are excluded: they exist so history still reads correctly, not so new
 * transactions can be filed under them.
 */
export function pickableCategories(categories: readonly Category[]): readonly Category[] {
  const unarchived = categories.filter((category) => !category.archived);
  return groupByKind(unarchived).flatMap((group) =>
    group.branches.flatMap((branch) => [branch.parent, ...branch.children]),
  );
}

/**
 * The categories that may be chosen as a parent: top-level, unarchived, and of the given kind.
 *
 * A child cannot be a parent (the hierarchy is two levels), and a category with children of its own
 * cannot become a child — the server refuses both with `409 category-depth-exceeded`. This hides
 * the first case, which is most of them; the second is only knowable for the category being edited
 * and is excluded by passing its id as {@param excluding}.
 */
export function possibleParents(
  categories: readonly Category[],
  kind: CategoryKind,
  excluding?: string,
): readonly Category[] {
  return categories
    .filter(
      (category) =>
        category.parentId === null &&
        !category.archived &&
        category.kind === kind &&
        category.id !== excluding,
    )
    .slice()
    .sort(byName);
}
