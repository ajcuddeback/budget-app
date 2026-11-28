import { CreateCategoryRequest, UpdateCategoryRequest, CategoryKind } from './category.models';

/** What the dialog's form holds. `parentId` of `''` means "no parent", i.e. top-level. */
export interface CategoryFormValue {
  name: string;
  kind: CategoryKind;
  parentId: string;
}

export function buildCategoryCreate(form: CategoryFormValue): CreateCategoryRequest {
  const request: CreateCategoryRequest = { name: form.name.trim() };
  if (form.parentId === '') {
    // A top-level category must state its kind; a child takes its parent's, and sending one that
    // disagrees is a 400 rather than a silent preference for either.
    request.kind = form.kind;
  } else {
    request.parentId = form.parentId;
  }
  return request;
}

/**
 * The patch for an edit: **only what actually changed**, so a field nobody touched is absent from
 * the body rather than sent back unchanged.
 *
 * That matters for `parentId`, where absent and `null` are different instructions — absent keeps
 * the parent, `null` moves the category to the top level. Building the absent case means *not
 * adding the key*, which is why this function exists rather than a spread of the form.
 */
export function buildCategoryPatch(
  original: { name: string; parentId: string | null; version: number },
  form: CategoryFormValue,
): UpdateCategoryRequest {
  const patch: UpdateCategoryRequest = { version: original.version };

  const name = form.name.trim();
  if (name !== original.name) {
    patch.name = name;
  }

  const wanted = form.parentId === '' ? null : form.parentId;
  if (wanted !== original.parentId) {
    patch.parentId = wanted;
  }

  return patch;
}

/** Whether a patch would change anything beyond the version it has to carry. */
export function patchIsEmpty(patch: UpdateCategoryRequest): boolean {
  return Object.keys(patch).length === 1;
}
