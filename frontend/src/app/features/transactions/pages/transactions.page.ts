import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { MoneyPipe } from '../../../core/i18n/format.pipes';
import { ConfirmDialogComponent } from '../../../shared/components/confirm-dialog.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import { Account } from '../../accounts/data/account.models';
import { AccountsService } from '../../accounts/data/accounts.service';
import { Category } from '../../categories/data/category.models';
import { CategoriesService } from '../../categories/data/categories.service';
import { pickableCategories } from '../../categories/data/category-tree';
import { TransactionDialogComponent } from '../components/transaction-dialog.component';
import { TransactionListComponent } from '../components/transaction-list.component';
import {
  TransactionFormValue,
  buildTransactionCreate,
  buildTransactionPatch,
  patchIsEmpty,
} from '../data/transaction-patch';
import { Ledger, LedgerQuery, Transaction } from '../data/transaction.models';
import { LEDGER_PAGE_SIZE, TransactionsService } from '../data/transactions.service';

/** What the dialog is open for. A `null` transaction means "record one". */
interface DialogTarget {
  transaction: Transaction | null;
}

/**
 * The ledger: what actually happened to the household's money.
 *
 * Smart: it fetches, coordinates and holds the state; the list and dialog below take inputs and
 * emit outputs. A failed write leaves the screen as it was.
 *
 * Four behaviours here are the feature's rules rather than UI taste:
 *
 * - **Nothing is totalled across currencies** (ADR-0022). The page total comes from the server and
 *   is absent when a page mixes them. There is no addition in this component.
 * - **"Needs a category" is a filter you can stand in**, because it is the work queue that turns a
 *   pile of transactions into a budget. It is a separate flag from the category filter, since "any
 *   category" and "no category" are different questions.
 * - **Archived accounts are not offered for new entries.** The server refuses with `409
 *   account-archived`; the picker simply does not list them.
 * - **The role decides what is offered; the server decides what is allowed.** A `VIEWER` sees no
 *   write buttons and would still be refused with `403 read-only-role`.
 */
@Component({
  selector: 'app-transactions-page',
  imports: [
    RouterLink,
    TranslatePipe,
    MoneyPipe,
    ErrorAlertComponent,
    NoHouseholdComponent,
    TransactionListComponent,
    TransactionDialogComponent,
    ConfirmDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './transactions.page.html',
  styleUrl: './transactions.page.css',
})
export class TransactionsPage {
  private readonly service = inject(TransactionsService);
  private readonly accountsService = inject(AccountsService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);

  private readonly ledger = signal<Ledger | null>(null);
  protected readonly accounts = signal<readonly Account[]>([]);
  protected readonly categories = signal<readonly Category[]>([]);

  /** The live filter. Every change re-queries from page 0: a filtered page 3 is meaningless. */
  protected readonly filterAccountId = signal('');
  protected readonly filterCategoryId = signal('');
  protected readonly filterUncategorised = signal(false);
  protected readonly filterFrom = signal('');
  protected readonly filterTo = signal('');
  protected readonly filterSearch = signal('');
  protected readonly page = signal(0);

  protected readonly canWrite = computed(() => {
    const role = this.auth.membership()?.role;
    return role === 'OWNER' || role === 'MEMBER';
  });

  protected readonly transactions = computed(() => this.ledger()?.content ?? []);
  protected readonly totalElements = computed(() => this.ledger()?.totalElements ?? 0);
  protected readonly totalPages = computed(() => this.ledger()?.totalPages ?? 0);
  protected readonly pageTotal = computed(() => this.ledger()?.pageTotal ?? null);
  protected readonly pageTotalCurrency = computed(() => this.ledger()?.pageTotalCurrency ?? '');

  /** Only unarchived accounts may take a new transaction. */
  protected readonly openAccounts = computed(() =>
    this.accounts().filter((account) => !account.archived),
  );
  protected readonly pickableCategories = computed(() => pickableCategories(this.categories()));

  /** Whether any filter is set, so the empty state can say "no matches" rather than "none yet". */
  protected readonly filtered = computed(
    () =>
      this.filterAccountId() !== '' ||
      this.filterCategoryId() !== '' ||
      this.filterUncategorised() ||
      this.filterFrom() !== '' ||
      this.filterTo() !== '' ||
      this.filterSearch().trim() !== '',
  );

  /** With the ledger filtered to one account, the per-row account column is noise. */
  protected readonly showAccountColumn = computed(() => this.filterAccountId() === '');

  /** Pre-selects the filtered account when recording a new transaction. */
  protected readonly defaultAccountId = computed(() =>
    this.filterAccountId() === '' ? null : this.filterAccountId(),
  );

  /** Today as `YYYY-MM-DD`, in the viewer's own zone — a date is a calendar question. */
  protected readonly today = computed(() => {
    const now = new Date();
    const month = `${now.getMonth() + 1}`.padStart(2, '0');
    const day = `${now.getDate()}`.padStart(2, '0');
    return `${now.getFullYear()}-${month}-${day}`;
  });

  protected readonly dialog = signal<DialogTarget | null>(null);
  protected readonly dialogBusy = signal(false);
  protected readonly dialogError = signal<ApiError | null>(null);
  protected readonly payeeSuggestions = signal<readonly string[]>([]);

  protected readonly busyId = signal<string | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly actionErrorSubject = signal<string | null>(null);
  protected readonly notice = signal<MessageKey | null>(null);
  protected readonly pendingDelete = signal<Transaction | null>(null);

  protected readonly confirmText = computed(() => {
    const target = this.pendingDelete();
    if (!target) {
      return null;
    }
    return {
      heading: this.i18n.t('transactions.deleteHeading'),
      message: this.i18n.t('transactions.deleteMessage'),
      action: this.i18n.t('common.delete'),
      tone: 'destructive' as const,
    };
  });

  constructor() {
    void this.load();
  }

  /** First load: the ledger plus the two lists the pickers and filters need. */
  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    await this.auth.ensureLoaded();
    if (!this.auth.membership()) {
      this.noHousehold.set(true);
      this.loading.set(false);
      return;
    }
    try {
      const [ledger, accounts, categories] = await Promise.all([
        firstValueFrom(this.service.list(this.query())),
        firstValueFrom(this.accountsService.list()),
        firstValueFrom(this.categoriesService.list()),
      ]);
      this.ledger.set(ledger);
      this.accounts.set(accounts.content);
      this.categories.set(categories.content);
      this.noHousehold.set(false);
    } catch (thrown) {
      const error = asApiError(thrown);
      if (error.status === 403) {
        this.noHousehold.set(true);
      } else {
        this.loadError.set(error);
      }
    } finally {
      this.loading.set(false);
    }
  }

  /** Re-queries the ledger alone, leaving the accounts and categories already in hand. */
  private async reloadLedger(): Promise<void> {
    try {
      this.ledger.set(await firstValueFrom(this.service.list(this.query())));
    } catch (thrown) {
      this.loadError.set(asApiError(thrown));
    }
  }

  private query(): LedgerQuery {
    return {
      accountId: this.filterAccountId() || undefined,
      categoryId: this.filterCategoryId() || undefined,
      uncategorised: this.filterUncategorised() || undefined,
      from: this.filterFrom() || undefined,
      to: this.filterTo() || undefined,
      search: this.filterSearch() || undefined,
      page: this.page(),
    };
  }

  // -------------------------------------------------------------------------------- filtering

  protected async setAccountFilter(value: string): Promise<void> {
    this.filterAccountId.set(value);
    await this.refilter();
  }

  protected async setCategoryFilter(value: string): Promise<void> {
    this.filterCategoryId.set(value);
    if (value !== '') {
      // Choosing a category and "uncategorised only" together is a contradiction, not a
      // narrower search. The explicit choice wins and the flag clears.
      this.filterUncategorised.set(false);
    }
    await this.refilter();
  }

  protected async toggleUncategorised(): Promise<void> {
    const next = !this.filterUncategorised();
    this.filterUncategorised.set(next);
    if (next) {
      this.filterCategoryId.set('');
    }
    await this.refilter();
  }

  protected async setFrom(value: string): Promise<void> {
    this.filterFrom.set(value);
    await this.refilter();
  }

  protected async setTo(value: string): Promise<void> {
    this.filterTo.set(value);
    await this.refilter();
  }

  protected async setSearch(value: string): Promise<void> {
    this.filterSearch.set(value);
    await this.refilter();
  }

  protected async clearFilters(): Promise<void> {
    this.filterAccountId.set('');
    this.filterCategoryId.set('');
    this.filterUncategorised.set(false);
    this.filterFrom.set('');
    this.filterTo.set('');
    this.filterSearch.set('');
    await this.refilter();
  }

  /** Any filter change goes back to page 0: page 3 of a different query is not a page at all. */
  private async refilter(): Promise<void> {
    this.page.set(0);
    await this.reloadLedger();
  }

  protected async goToPage(next: number): Promise<void> {
    if (next < 0 || next >= this.totalPages()) {
      return;
    }
    this.page.set(next);
    await this.reloadLedger();
  }

  // ---------------------------------------------------------------------------------- writing

  protected openAdd(): void {
    this.resetDialogState();
    this.payeeSuggestions.set([]);
    this.dialog.set({ transaction: null });
  }

  protected openEdit(transaction: Transaction): void {
    this.resetDialogState();
    this.payeeSuggestions.set([]);
    this.dialog.set({ transaction });
  }

  protected closeDialog(): void {
    this.dialog.set(null);
    this.resetDialogState();
  }

  /** Fetches payee suggestions for what has been typed. A failure is silent: it is a convenience. */
  protected async suggestPayees(typed: string): Promise<void> {
    try {
      this.payeeSuggestions.set(await firstValueFrom(this.service.suggestPayees(typed)));
    } catch {
      // An autocomplete that cannot reach the server leaves an ordinary text field behind, which
      // is a perfectly good way to type a payee's name. Not worth an alert.
      this.payeeSuggestions.set([]);
    }
  }

  protected async submit(form: TransactionFormValue): Promise<void> {
    const target = this.dialog();
    if (!target) {
      return;
    }
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      if (target.transaction === null) {
        await firstValueFrom(this.service.create(buildTransactionCreate(form)));
        this.notice.set('transactions.created');
      } else {
        const patch = buildTransactionPatch(target.transaction, form);
        if (patchIsEmpty(patch)) {
          this.closeDialog();
          return;
        }
        await firstValueFrom(this.service.update(target.transaction.id, patch));
        this.notice.set('transactions.updated');
      }
      this.dialog.set(null);
      await this.reloadLedger();
      // The balances on the accounts screen have moved; this page shows account names from the
      // same list, so it is refreshed rather than left holding a stale copy.
      await this.refreshAccounts();
    } catch (thrown) {
      this.dialogError.set(asApiError(thrown));
    } finally {
      this.dialogBusy.set(false);
    }
  }

  protected askToDelete(transaction: Transaction): void {
    this.pendingDelete.set(transaction);
  }

  protected cancelDelete(): void {
    this.pendingDelete.set(null);
  }

  protected async confirmDelete(): Promise<void> {
    const target = this.pendingDelete();
    if (!target) {
      return;
    }
    this.pendingDelete.set(null);
    await this.run(target, () => this.service.remove(target.id), 'transactions.deleted');
  }

  private async run(
    transaction: Transaction,
    request: () => Observable<unknown>,
    said: MessageKey,
  ): Promise<void> {
    this.busyId.set(transaction.id);
    this.actionError.set(null);
    this.actionErrorSubject.set(null);
    this.notice.set(null);
    try {
      await firstValueFrom(request());
      this.notice.set(said);
      await this.reloadLedger();
      await this.refreshAccounts();
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
      this.actionErrorSubject.set(transaction.payeeName ?? transaction.accountName);
    } finally {
      this.busyId.set(null);
    }
  }

  private async refreshAccounts(): Promise<void> {
    try {
      this.accounts.set((await firstValueFrom(this.accountsService.list())).content);
    } catch {
      // The ledger is what this screen is for and it has already been refreshed. A stale account
      // name is not worth replacing a successful save with an error.
    }
  }

  protected readonly pageSize = LEDGER_PAGE_SIZE;

  private resetDialogState(): void {
    this.dialogBusy.set(false);
    this.dialogError.set(null);
    this.notice.set(null);
    this.actionError.set(null);
    this.actionErrorSubject.set(null);
  }
}
