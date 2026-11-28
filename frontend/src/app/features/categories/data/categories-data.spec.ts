import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  CategoryFormValue,
  buildCategoryCreate,
  buildCategoryPatch,
  patchIsEmpty,
} from './category-patch';
import {
  buildCategoryTree,
  groupByKind,
  pickableCategories,
  possibleParents,
} from './category-tree';
import { Category, categoryKindKey } from './category.models';
import { CategoriesService } from './categories.service';

const BILLS: Category = {
  id: 'c1',
  name: 'Bills',
  kind: 'EXPENSE',
  parentId: null,
  parentName: null,
  archived: false,
  createdAt: '2026-11-01T09:00:00Z',
  updatedAt: '2026-11-01T09:00:00Z',
  version: 0,
};

const UTILITIES: Category = {
  ...BILLS,
  id: 'c2',
  name: 'Utilities',
  parentId: 'c1',
  parentName: 'Bills',
};

const GROCERIES: Category = { ...BILLS, id: 'c3', name: 'Groceries' };
const SALARY: Category = {
  ...BILLS,
  id: 'c4',
  name: 'Salary',
  kind: 'INCOME',
  parentId: 'c5',
  parentName: 'Income',
};
const INCOME: Category = { ...BILLS, id: 'c5', name: 'Income', kind: 'INCOME' };

describe('buildCategoryTree', () => {
  it('nests children under their parent', () => {
    const tree = buildCategoryTree([UTILITIES, BILLS, GROCERIES]);

    expect(tree.map((branch) => branch.parent.name)).toEqual(['Bills', 'Groceries']);
    expect(tree[0].children.map((child) => child.name)).toEqual(['Utilities']);
    expect(tree[1].children).toEqual([]);
  });

  it('promotes a child whose parent is missing rather than dropping it', () => {
    // The default listing excludes archived rows, and an archived parent with unarchived children
    // is ordinary. Losing a row from a financial screen is never the right failure.
    const tree = buildCategoryTree([UTILITIES]);

    expect(tree.map((branch) => branch.parent.name)).toEqual(['Utilities']);
  });

  it('orders by name with a stable tiebreak so the list does not reshuffle', () => {
    const duplicate: Category = { ...GROCERIES, id: 'c9' };

    const tree = buildCategoryTree([duplicate, GROCERIES, BILLS]);

    expect(tree.map((branch) => branch.parent.id)).toEqual(['c1', 'c3', 'c9']);
  });
});

describe('groupByKind', () => {
  it('puts income before spending and never mixes them', () => {
    const groups = groupByKind([GROCERIES, SALARY, INCOME, BILLS, UTILITIES]);

    expect(groups.map((group) => group.kind)).toEqual(['INCOME', 'EXPENSE']);
    expect(groups[0].branches.map((branch) => branch.parent.name)).toEqual(['Income']);
    expect(groups[0].branches[0].children.map((child) => child.name)).toEqual(['Salary']);
    expect(groups[1].branches.map((branch) => branch.parent.name)).toEqual(['Bills', 'Groceries']);
  });

  it('leaves out a kind nothing belongs to', () => {
    expect(groupByKind([BILLS]).map((group) => group.kind)).toEqual(['EXPENSE']);
  });
});

describe('pickableCategories', () => {
  it('excludes archived categories, which exist for history and not for new entries', () => {
    const retired: Category = { ...GROCERIES, id: 'c8', name: 'Retired', archived: true };

    const names = pickableCategories([BILLS, UTILITIES, retired]).map((c) => c.name);

    expect(names).toEqual(['Bills', 'Utilities']);
  });

  it('lists a child straight after its parent so a flat picker still reads as a tree', () => {
    const names = pickableCategories([GROCERIES, UTILITIES, BILLS]).map((c) => c.name);

    expect(names).toEqual(['Bills', 'Utilities', 'Groceries']);
  });
});

describe('possibleParents', () => {
  it('offers only top-level categories of the same kind', () => {
    const parents = possibleParents([BILLS, UTILITIES, GROCERIES, INCOME, SALARY], 'EXPENSE');

    expect(parents.map((parent) => parent.name)).toEqual(['Bills', 'Groceries']);
  });

  it('never offers a category as its own parent', () => {
    const parents = possibleParents([BILLS, GROCERIES], 'EXPENSE', 'c1');

    expect(parents.map((parent) => parent.name)).toEqual(['Groceries']);
  });

  it('leaves out archived categories', () => {
    const retired: Category = { ...GROCERIES, archived: true };

    expect(possibleParents([BILLS, retired], 'EXPENSE').map((p) => p.name)).toEqual(['Bills']);
  });
});

describe('buildCategoryCreate', () => {
  it('states a kind for a top-level category', () => {
    const form: CategoryFormValue = { name: '  Travel ', kind: 'EXPENSE', parentId: '' };

    expect(buildCategoryCreate(form)).toEqual({ name: 'Travel', kind: 'EXPENSE' });
  });

  it('sends a parent instead of a kind for a child, which takes its parent"s', () => {
    const form: CategoryFormValue = { name: 'Water', kind: 'INCOME', parentId: 'c1' };

    // The kind is deliberately absent: a child takes its parent's, and sending a contradicting
    // one is a 400 rather than a silent preference for either.
    expect(buildCategoryCreate(form)).toEqual({ name: 'Water', parentId: 'c1' });
  });
});

describe('buildCategoryPatch', () => {
  const original = { name: 'Bills', parentId: null, version: 4 };

  it('sends only the fields that changed', () => {
    const patch = buildCategoryPatch(original, {
      name: 'Household bills',
      kind: 'EXPENSE',
      parentId: '',
    });

    expect(patch).toEqual({ version: 4, name: 'Household bills' });
  });

  it('omits parentId entirely when the parent did not change', () => {
    const patch = buildCategoryPatch(original, { name: 'Bills', kind: 'EXPENSE', parentId: '' });

    // Absent, not null: a null would move it to the top level, which is a different instruction
    // from "leave the parent alone" — and this category is already top-level either way, so only
    // the absence of the key proves the two are being kept distinct.
    expect('parentId' in patch).toBe(false);
    expect(patchIsEmpty(patch)).toBe(true);
  });

  it('sends an explicit null to move a child to the top level', () => {
    const child = { name: 'Utilities', parentId: 'c1', version: 2 };

    const patch = buildCategoryPatch(child, { name: 'Utilities', kind: 'EXPENSE', parentId: '' });

    expect(patch).toEqual({ version: 2, parentId: null });
  });

  it('sends the id to move a category under a parent', () => {
    const patch = buildCategoryPatch(original, { name: 'Bills', kind: 'EXPENSE', parentId: 'c3' });

    expect(patch).toEqual({ version: 4, parentId: 'c3' });
  });

  it('trims the name before comparing, so whitespace alone is not a change', () => {
    const patch = buildCategoryPatch(original, {
      name: '  Bills  ',
      kind: 'EXPENSE',
      parentId: '',
    });

    expect(patchIsEmpty(patch)).toBe(true);
  });
});

describe('categoryKindKey', () => {
  it('gives a neutral word for a kind this client has never heard of', () => {
    expect(categoryKindKey('SOMETHING_NEW')).toBe('categories.kind.unknown');
  });
});

describe('CategoriesService', () => {
  let service: CategoriesService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(CategoriesService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('asks for a page big enough to hold the whole tree', () => {
    service.list().subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/categories');
    // The server caps a household at 200, so this guarantees one request is always enough.
    expect(request.request.params.get('size')).toBe('200');
    expect(request.request.params.get('includeArchived')).toBe('true');
    request.flush({ content: [], page: 0, size: 200, totalElements: 0, totalPages: 0 });
  });

  it('archives through a sub-resource rather than a field on PATCH', () => {
    service.archive('c1').subscribe();

    const request = http.expectOne('/api/categories/c1/archive');
    expect(request.request.method).toBe('POST');
    request.flush(BILLS);
  });

  it('encodes an id so it cannot alter the path it is placed in', () => {
    service.get('../accounts').subscribe();

    const request = http.expectOne('/api/categories/..%2Faccounts');
    request.flush(BILLS);
  });
});
