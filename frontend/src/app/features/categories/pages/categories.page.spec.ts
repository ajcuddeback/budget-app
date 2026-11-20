import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { HouseholdRole } from '../../../core/auth.models';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import {
  OWNER,
  OWNER_MEMBERSHIP,
  apiError,
  buttonNamed,
  FakeAuth,
  fakeAuth,
  settle,
} from '../../../testing/helpers';
import { CategoriesService } from '../data/categories.service';
import { Category } from '../data/category.models';
import { CategoriesPage } from './categories.page';

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

const INCOME: Category = { ...BILLS, id: 'c3', name: 'Income', kind: 'INCOME' };
const RETIRED: Category = { ...BILLS, id: 'c4', name: 'Holidays', archived: true };

describe('CategoriesPage', () => {
  let auth: FakeAuth;
  const service = {
    list: vi.fn(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    archive: vi.fn(),
    unarchive: vi.fn(),
    remove: vi.fn(),
  };

  async function render(
    options: { role?: HouseholdRole; categories?: readonly Category[] } = {},
  ): Promise<ComponentFixture<CategoriesPage>> {
    auth = fakeAuth({
      ...OWNER,
      household: { ...OWNER_MEMBERSHIP, baseCurrency: 'GBP', role: options.role ?? 'OWNER' },
    });
    for (const fn of Object.values(service)) fn.mockReset();
    const categories = options.categories ?? [BILLS, UTILITIES, INCOME];
    service.list.mockReturnValue(
      of({
        content: [...categories],
        page: 0,
        size: 200,
        totalElements: categories.length,
        totalPages: 1,
      }),
    );

    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: CategoriesService, useValue: service },
      ],
    });
    await TestBed.inject(I18nService).use('en-GB');
    const fixture = TestBed.createComponent(CategoriesPage);
    await settle(fixture);
    return fixture;
  }

  const text = (fixture: ComponentFixture<CategoriesPage>): string =>
    (fixture.nativeElement as HTMLElement).textContent ?? '';
  const root = (fixture: ComponentFixture<CategoriesPage>): HTMLElement => fixture.nativeElement;

  it('groups categories by kind and nests children under their parent', async () => {
    const fixture = await render();

    expect(text(fixture)).toContain('Income');
    expect(text(fixture)).toContain('Spending');
    expect(root(fixture).querySelector('.children')?.textContent).toContain('Utilities');
  });

  it('hides archived categories until they are asked for', async () => {
    const fixture = await render({ categories: [BILLS, RETIRED] });

    expect(root(fixture).querySelector('#archived-categories')?.textContent ?? '').not.toContain(
      'Holidays',
    );

    buttonNamed(root(fixture), /Show 1 archived/).click();
    await settle(fixture);

    expect(root(fixture).querySelector('#archived-categories')?.textContent).toContain('Holidays');
  });

  it('says transactions are kept when deleting a category', async () => {
    const fixture = await render({ categories: [INCOME] });

    buttonNamed(root(fixture), /Delete/).click();
    await settle(fixture);

    // The thing somebody is actually afraid of, answered before they commit.
    expect(root(fixture).querySelector('dialog')?.textContent).toContain(
      'Your transactions are kept',
    );
  });

  it('warns that sub-categories go too when deleting a parent that has them', async () => {
    // Only Bills, so the first Delete button is unambiguously its own — income groups first.
    const fixture = await render({ categories: [BILLS, UTILITIES] });

    buttonNamed(root(fixture), /Delete/).click();
    await settle(fixture);

    expect(root(fixture).querySelector('dialog')?.textContent).toContain('sub-categories');
  });

  it('pre-selects the parent when adding a sub-category to a specific row', async () => {
    const fixture = await render({ categories: [BILLS, UTILITIES] });

    buttonNamed(root(fixture), /Add a sub-category/).click();
    await settle(fixture);

    const parent = root(fixture).querySelector<HTMLSelectElement>('#category-parent');
    expect(parent?.value).toBe('c1');
  });

  it('locks the kind when editing, because changing it would reinterpret history', async () => {
    const fixture = await render();

    buttonNamed(root(fixture), /Edit/).click();
    await settle(fixture);

    const kind = root(fixture).querySelector<HTMLSelectElement>('#category-kind');
    expect(kind?.disabled).toBe(true);
    expect(root(fixture).querySelector('dialog')?.textContent).toContain("kind can't change");
  });

  it('offers a viewer no write buttons', async () => {
    const fixture = await render({ role: 'VIEWER' });

    expect(text(fixture)).not.toContain('Add a category');
    expect(root(fixture).querySelectorAll('.row-actions').length).toBe(0);
  });

  it('keeps the list and explains itself when an archive is refused', async () => {
    const fixture = await render();
    service.archive.mockReturnValue(throwError(() => apiError(409, 'conflict')));

    buttonNamed(root(fixture), /Archive/).click();
    await settle(fixture);
    root(fixture)
      .querySelector<HTMLButtonElement>('dialog .btn-danger, dialog .btn-primary')!
      .click();
    await settle(fixture);

    expect(text(fixture)).toContain('Bills');
  });

  it('does not send a request when nothing on the form changed', async () => {
    const fixture = await render();

    buttonNamed(root(fixture), /Edit/).click();
    await settle(fixture);
    buttonNamed(root(fixture), /Save/).click();
    await settle(fixture);

    // A round trip that bumps the version for no reason is worse than closing the dialog.
    expect(service.update).not.toHaveBeenCalled();
  });
});
