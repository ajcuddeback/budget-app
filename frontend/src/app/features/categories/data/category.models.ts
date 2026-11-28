import { MessageKey } from '../../../core/i18n/messages';

/**
 * Whether a category classifies money arriving or money leaving. A closed set the server also
 * enforces — an unknown value is a `400` at the edge and a `CHECK` violation in the database.
 */
export const CATEGORY_KINDS = ['INCOME', 'EXPENSE'] as const;

export type CategoryKind = (typeof CATEGORY_KINDS)[number];

const CATEGORY_KIND_KEY: Readonly<Record<CategoryKind, MessageKey>> = {
  INCOME: 'categories.kind.INCOME',
  EXPENSE: 'categories.kind.EXPENSE',
};

export function isCategoryKind(value: string): value is CategoryKind {
  return (CATEGORY_KINDS as readonly string[]).includes(value);
}

/** A kind this client has never heard of is a newer server, not a bug: it gets a neutral word. */
export function categoryKindKey(kind: string): MessageKey {
  return isCategoryKind(kind) ? CATEGORY_KIND_KEY[kind] : 'categories.kind.unknown';
}

/**
 * One category, exactly as `CategoryResponse` sends it.
 *
 * **The tree arrives flat.** Every collection in this API is paginated, and a page boundary must
 * not be able to fall between a parent and its children, so the server sends rows with a
 * `parentId` and the client assembles the tree ({@link buildCategoryTree}). A household is capped
 * at the maximum page size, so one request always holds all of it.
 *
 * **`name` and `parentName` are never pre-joined.** The client composes `Bills → Utilities` through
 * a translated template, because a joined label cannot be re-ordered for a right-to-left locale
 * (ADR-0023).
 */
export interface Category {
  id: string;
  name: string;
  kind: CategoryKind;
  /** `null` for a top-level category. */
  parentId: string | null;
  /** `null` for a top-level category; the parent's name otherwise. */
  parentName: string | null;
  archived: boolean;
  createdAt: string;
  updatedAt: string;
  /** What a `PATCH` must send back, or the optimistic lock cannot work. */
  version: number;
}

export interface CreateCategoryRequest {
  name: string;
  /** Required at the top level. Omitted for a child, which takes its parent's kind. */
  kind?: CategoryKind;
  /** Omitted for a top-level category. */
  parentId?: string;
}

/**
 * A partial update. **An absent property means "leave it alone"**, which is what `PATCH` means.
 *
 * `parentId` is three-way and the three states are not interchangeable:
 *
 * | property   | meaning                        |
 * | ---------- | ------------------------------ |
 * | absent     | keep the parent it has         |
 * | `null`     | move it to the top level       |
 * | `"id"`     | move it under that category    |
 *
 * `JSON.stringify` drops a property whose value is `undefined` and keeps one whose value is
 * `null`, so the distinction survives the wire — but only if the absent case is built by *not
 * adding the key*. {@link buildCategoryPatch} is the only place that decides.
 *
 * There is no `kind`: a category's kind is fixed at creation, because re-kinding one in use would
 * reinterpret every transaction already filed under it.
 */
export interface UpdateCategoryRequest {
  /** Required: optimistic locking only works if the caller says which version it read. */
  version: number;
  name?: string;
  parentId?: string | null;
}
