import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ConfirmDialogComponent } from '../../../shared/components/confirm-dialog.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import { AccountDialogComponent, ConflictingField } from '../components/account-dialog.component';
import { AccountListComponent } from '../components/account-list.component';
import { groupByCurrency, orderedByName } from '../data/account-grouping';
import {
  AccountField,
  AccountFormValue,
  buildAccountCreate,
  buildAccountPatch,
  differingFields,
} from '../data/account-patch';
import { Account, accountTypeKey, isKnownToHaveHistory } from '../data/account.models';
import { AccountsService } from '../data/accounts.service';

/** What the dialog is open for. A `null` account means "add one". */
interface DialogTarget {
  account: Account | null;
}

interface PendingAction {
  kind: 'archive' | 'delete';
  account: Account;
}

const FIELD_LABEL_KEY: Readonly<Record<AccountField, MessageKey>> = {
  name: 'accounts.name',
  lastFour: 'accounts.lastFour',
  type: 'accounts.type',
  currency: 'accounts.currency',
  openingBalance: 'accounts.openingBalance',
  archived: 'accounts.state',
};

/**
 * Where the household's money lives.
 *
 * Smart: it fetches, coordinates and holds the state; the components below it take inputs and emit
 * outputs. A failed write leaves the screen as it was and says what happened beside the thing that
 * failed — a refused archive does not empty the list.
 *
 * Three behaviours here are the feature's rules rather than UI taste:
 *
 * - **Nothing is ever totalled across currencies** (ADR-0022). Accounts are grouped by currency
 *   and each group carries its own subtotal. There is no code path that adds two groups together.
 * - **A `409 stale-version` is answered by re-reading and saying what changed**, never by sending
 *   the same version again or quietly adopting the new one. The person chooses which copy wins.
 * - **The role decides what is offered; the server decides what is allowed.** A `VIEWER` sees no
 *   write buttons, and would still be refused with `403 read-only-role` if they reached one.
 */
@Component({
  selector: 'app-accounts-page',
  imports: [
    TranslatePipe,
    ErrorAlertComponent,
    NoHouseholdComponent,
    AccountListComponent,
    AccountDialogComponent,
    ConfirmDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './accounts.page.html',
  styleUrl: './accounts.page.css',
})
export class AccountsPage {
  private readonly service = inject(AccountsService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);
  private readonly accounts = signal<readonly Account[]>([]);
  private readonly totalElements = signal(0);

  protected readonly showArchived = signal(false);

  /** A `VIEWER` may read everything here and write nothing. The server agrees, and is the gate. */
  protected readonly canWrite = computed(() => {
    const role = this.auth.membership()?.role;
    return role === 'OWNER' || role === 'MEMBER';
  });
  protected readonly baseCurrency = computed(() => this.auth.membership()?.baseCurrency ?? '');

  protected readonly active = computed(() => this.accounts().filter((a) => !a.archived));
  protected readonly archived = computed(() => this.accounts().filter((a) => a.archived));
  protected readonly activeGroups = computed(() => groupByCurrency(this.active()));
  protected readonly archivedGroups = computed(() => groupByCurrency(this.archived()));

  /**
   * Subtotals appear only once there is more than one currency to keep apart. With a single
   * currency a subtotal would be a household total, and whether this screen shows one is an open
   * question in the feature doc — inventing one here risks two totals computed two ways.
   */
  protected readonly multipleCurrencies = computed(() => this.activeGroups().length > 1);

  /** The server paginates. Saying so beats quietly showing part of a list of money. */
  protected readonly notShown = computed(() =>
    Math.max(0, this.totalElements() - this.accounts().length),
  );
  /** Two keys rather than "1 more accounts": the message catalogue has no plural machinery. */
  protected readonly notShownKey = computed<MessageKey>(() =>
    this.notShown() === 1 ? 'accounts.notShownOne' : 'accounts.notShown',
  );

  protected readonly dialog = signal<DialogTarget | null>(null);
  protected readonly dialogBusy = signal(false);
  protected readonly dialogError = signal<ApiError | null>(null);
  protected readonly conflict = signal<readonly ConflictingField[] | null>(null);
  /** The server's copy, fetched when a conflict was found. */
  private latest: Account | null = null;
  /** What was typed when the conflict happened, so "keep mine" has something to keep. */
  private submittedForm: AccountFormValue | null = null;

  protected readonly currencyLocked = computed(() => {
    const account = this.dialog()?.account;
    return account ? isKnownToHaveHistory(account) : false;
  });

  protected readonly busyId = signal<string | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly notice = signal<MessageKey | null>(null);
  protected readonly pending = signal<PendingAction | null>(null);

  protected readonly confirmText = computed(() => {
    const action = this.pending();
    if (!action) {
      return null;
    }
    const name = action.account.name;
    return action.kind === 'archive'
      ? {
          heading: this.i18n.t('accounts.archiveHeading', { name }),
          message: this.i18n.t('accounts.archiveMessage'),
          action: this.i18n.t('accounts.archive'),
          // Reversible, so it does not wear the colour that means "this cannot be undone".
          tone: 'ordinary' as const,
        }
      : {
          heading: this.i18n.t('accounts.deleteHeading', { name }),
          message: this.i18n.t('accounts.deleteMessage'),
          tone: 'destructive' as const,
          action: this.i18n.t('accounts.delete'),
        };
  });

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    await this.auth.ensureLoaded();
    if (!this.auth.membership()) {
      // Signed in, but nobody has added them to the household. Not an error.
      this.noHousehold.set(true);
      this.loading.set(false);
      return;
    }
    try {
      const page = await firstValueFrom(this.service.list());
      this.accounts.set(orderedByName(page.content));
      this.totalElements.set(page.totalElements);
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

  protected toggleArchived(): void {
    this.showArchived.update((shown) => !shown);
  }

  protected openAdd(): void {
    this.resetDialogState();
    this.dialog.set({ account: null });
  }

  protected openEdit(account: Account): void {
    this.resetDialogState();
    this.dialog.set({ account });
  }

  /** A notice is about the last thing that happened, and that moment has passed. */
  private forgetNotice(): void {
    this.notice.set(null);
  }

  protected closeDialog(): void {
    this.dialog.set(null);
    this.resetDialogState();
  }

  protected async submit(form: AccountFormValue): Promise<void> {
    const target = this.dialog();
    if (!target) {
      return;
    }
    this.submittedForm = form;
    await this.save(target.account, form);
  }

  /** "Load the latest": the server's copy replaces the form, their edit is discarded knowingly. */
  protected useLatest(): void {
    if (!this.latest) {
      return;
    }
    this.conflict.set(null);
    this.dialogError.set(null);
    this.dialog.set({ account: this.latest });
  }

  /** "Keep mine": the same edit, now against the version they have been shown. Not silent. */
  protected async overwrite(): Promise<void> {
    const latest = this.latest;
    const form = this.submittedForm;
    if (!latest || !form) {
      return;
    }
    this.conflict.set(null);
    await this.save(latest, form);
  }

  private async save(target: Account | null, form: AccountFormValue): Promise<void> {
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      if (target) {
        const saved = await firstValueFrom(
          this.service.update(target.id, buildAccountPatch(target, form)),
        );
        this.replace(saved);
        this.finish('accounts.saved');
      } else {
        const created = await firstValueFrom(this.service.create(buildAccountCreate(form)));
        this.accounts.update((all) => orderedByName([...all, created]));
        this.totalElements.update((total) => total + 1);
        this.finish('accounts.added');
      }
    } catch (thrown) {
      const error = asApiError(thrown);
      if (target && error.status === 409 && error.code === 'stale-version') {
        await this.explainConflict(target);
      } else {
        this.dialogError.set(error);
      }
    } finally {
      this.dialogBusy.set(false);
    }
  }

  /**
   * Somebody else saved first. Re-read, show the list the new truth, and tell the person which
   * fields moved and what they now say — then stop, because the next move is theirs.
   */
  private async explainConflict(opened: Account): Promise<void> {
    try {
      const latest = await firstValueFrom(this.service.get(opened.id));
      this.latest = latest;
      this.replace(latest);
      this.conflict.set(
        differingFields(opened, latest).map((field) => ({
          labelKey: FIELD_LABEL_KEY[field],
          value: this.describe(field, latest),
        })),
      );
    } catch (thrown) {
      // The re-read failed too (it may have been deleted). Say that, rather than inventing a diff.
      this.dialogError.set(asApiError(thrown));
    }
  }

  private describe(field: AccountField, account: Account): string {
    switch (field) {
      case 'name':
        return account.name;
      case 'lastFour':
        return account.lastFour ?? this.i18n.t('accounts.lastFourNone');
      case 'type':
        return this.i18n.t(accountTypeKey(account.type));
      case 'currency':
        return account.currency;
      case 'openingBalance':
        return this.i18n.formatMoney(account.openingBalance, account.currency);
      case 'archived':
        return this.i18n.t(account.archived ? 'accounts.archivedTag' : 'accounts.activeTag');
    }
  }

  protected askToArchive(account: Account): void {
    this.actionError.set(null);
    this.notice.set(null);
    this.pending.set({ kind: 'archive', account });
  }

  protected askToDelete(account: Account): void {
    this.actionError.set(null);
    this.notice.set(null);
    this.pending.set({ kind: 'delete', account });
  }

  protected cancelPending(): void {
    this.pending.set(null);
  }

  protected async confirmPending(): Promise<void> {
    const action = this.pending();
    if (!action) {
      return;
    }
    const { kind, account } = action;
    this.busyId.set(account.id);
    this.actionError.set(null);
    try {
      if (kind === 'archive') {
        this.replace(await firstValueFrom(this.service.archive(account.id)));
        this.notice.set('accounts.archivedNotice');
      } else {
        await firstValueFrom(this.service.remove(account.id));
        this.accounts.update((all) => all.filter((one) => one.id !== account.id));
        this.totalElements.update((total) => Math.max(0, total - 1));
        this.notice.set('accounts.deletedNotice');
      }
      this.pending.set(null);
    } catch (thrown) {
      // `account-not-empty` lands here: the sentence it renders names archiving as the way out.
      this.pending.set(null);
      this.actionError.set(asApiError(thrown));
    } finally {
      this.busyId.set(null);
    }
  }

  /** Un-archiving loses nothing, so it does not ask first. */
  protected async unarchive(account: Account): Promise<void> {
    this.busyId.set(account.id);
    this.actionError.set(null);
    this.notice.set(null);
    try {
      this.replace(await firstValueFrom(this.service.unarchive(account.id)));
      this.notice.set('accounts.unarchivedNotice');
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
    } finally {
      this.busyId.set(null);
    }
  }

  private replace(saved: Account): void {
    this.accounts.update((all) =>
      orderedByName(all.map((one) => (one.id === saved.id ? saved : one))),
    );
  }

  /** Close first, then say what happened: closing forgets the previous notice. */
  private finish(notice: MessageKey): void {
    this.closeDialog();
    this.actionError.set(null);
    this.notice.set(notice);
  }

  private resetDialogState(): void {
    this.forgetNotice();
    this.dialogError.set(null);
    this.conflict.set(null);
    this.latest = null;
    this.submittedForm = null;
  }
}
