import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { DateOnlyPipe, MoneyPipe } from '../../../core/i18n/format.pipes';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MessageKey } from '../../../core/i18n/messages';
import { sameAmount } from '../../../core/money';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { currentPeriod, shiftPeriod } from '../../../core/period';
import { ConfirmDialogComponent } from '../../../shared/components/confirm-dialog.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import { AccountsService } from '../../accounts/data/accounts.service';
import { Account } from '../../accounts/data/account.models';
import { CategoriesService } from '../../categories/data/categories.service';
import { Category } from '../../categories/data/category.models';
import { BillDialogComponent } from '../components/bill-dialog.component';
import { IncomeDialogComponent } from '../components/income-dialog.component';
import { RecordPaymentComponent } from '../components/record-payment.component';
import {
  BillFormValue,
  IncomeFormValue,
  OccurrenceFormValue,
  buildBillCreate,
  buildBillPatch,
  buildIncomeCreate,
  buildIncomePatch,
  buildPaidRecord,
  patchIsEmpty,
} from '../data/bill-patch';
import {
  Bill,
  BillOccurrence,
  IncomeSource,
  PeriodSummary,
  isNegative,
  occurrenceStatusKey,
  sourceOfTruthKey,
  statusSourceKey,
} from '../data/bills.models';
import { BillsService } from '../data/bills.service';
import { IncomeService } from '../data/income.service';
import { scheduleSaid } from '../data/schedule-text';
import { Schedule } from '../data/schedule.models';
import { PeriodSummaryService } from '../data/summary.service';

interface BillTarget {
  bill: Bill | null;
}

interface IncomeTarget {
  source: IncomeSource | null;
}

/** Which occurrence the payment sheet is about. A `null` occurrence is an irregular bill's new one. */
interface PaymentTarget {
  bill: Bill;
  occurrence: BillOccurrence | null;
}

type PendingAction =
  | { kind: 'retireBill' | 'deleteBill'; bill: Bill }
  | { kind: 'forget'; bill: Bill; occurrence: BillOccurrence }
  | { kind: 'retireIncome' | 'deleteIncome'; source: IncomeSource };

/**
 * Bills & Income — the predictable half of a household's money.
 *
 * Four blocks, in the order the designs show them: what's left, income, bills, and a link to
 * envelopes. Five behaviours here are the feature's rules rather than UI taste:
 *
 * - **The page leads with what's left, and that figure goes negative.** It is rendered negative,
 *   never clamped: "you are £180 short this month" is the information somebody needs, and the
 *   three terms beneath it are what makes a plan counted twice visible instead of hidden.
 * - **Bills count whether or not they are paid** (`summary.bills`). Subtracting only the unpaid
 *   ones would make what's left *rise* every time somebody ticked a bill off. The outstanding
 *   figure is reported alongside so the page can say "£340 of £1,200 still to pay" without
 *   leading with it.
 * - **A bill has 0..n occurrences this month**, each its own row with its own one-tap Mark paid,
 *   addressed by its due date (ADR-0032). A bill with none is still listed — a weekly bill falls
 *   four or five times, an irregular one never falls, and a yearly one falls in one month.
 * - **"Forget" is offered only on a recorded occurrence, and is not the same as marking it due.**
 *   Marking due is an assertion; forgetting is the absence of one, and the confirmation says so.
 * - **Nothing here does arithmetic on money.** Every figure comes from the server as a string and
 *   goes to `Intl` as one (ADR-0006); the server computes and the client displays.
 */
@Component({
  selector: 'app-bills-page',
  imports: [
    RouterLink,
    TranslatePipe,
    MoneyPipe,
    DateOnlyPipe,
    ErrorAlertComponent,
    NoHouseholdComponent,
    ConfirmDialogComponent,
    BillDialogComponent,
    IncomeDialogComponent,
    RecordPaymentComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './bills.page.html',
  styleUrl: './bills.page.css',
})
export class BillsPage {
  private readonly bills = inject(BillsService);
  private readonly income = inject(IncomeService);
  private readonly summaries = inject(PeriodSummaryService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly accountsService = inject(AccountsService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);

  protected readonly period = signal(currentPeriod());

  private readonly allBills = signal<readonly Bill[]>([]);
  private readonly allIncome = signal<readonly IncomeSource[]>([]);
  protected readonly summary = signal<PeriodSummary | null>(null);
  protected readonly billTotals = signal<{
    total: string;
    outstanding: string;
    overdue: string;
  } | null>(null);
  protected readonly incomeTotal = signal<string | null>(null);
  protected readonly categories = signal<readonly Category[]>([]);
  protected readonly accounts = signal<readonly Account[]>([]);

  protected readonly canWrite = computed(() => {
    const role = this.auth.membership()?.role;
    return role === 'OWNER' || role === 'MEMBER';
  });

  protected readonly activeBills = computed(() => this.allBills().filter((bill) => bill.active));
  protected readonly retiredBills = computed(() => this.allBills().filter((bill) => !bill.active));
  protected readonly activeIncome = computed(() =>
    this.allIncome().filter((source) => source.active),
  );
  protected readonly retiredIncome = computed(() =>
    this.allIncome().filter((source) => !source.active),
  );

  protected readonly currency = computed(
    () => this.summary()?.currency ?? this.auth.membership()?.baseCurrency ?? '',
  );
  /** The month, named for the viewer's locale — "August 2026", never "2026-08". */
  protected readonly periodName = computed(() => this.i18n.formatMonth(this.period()));

  protected readonly statusKey = occurrenceStatusKey;
  protected readonly truthKey = sourceOfTruthKey;
  protected readonly sourceKey = statusSourceKey;

  protected readonly billDialog = signal<BillTarget | null>(null);
  protected readonly incomeDialog = signal<IncomeTarget | null>(null);
  protected readonly paymentDialog = signal<PaymentTarget | null>(null);
  protected readonly dialogBusy = signal(false);
  protected readonly dialogError = signal<ApiError | null>(null);

  /** The row a request is in flight for, so only its own buttons go quiet. */
  protected readonly busyId = signal<string | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly actionErrorSubject = signal<string | null>(null);
  protected readonly notice = signal<MessageKey | null>(null);
  protected readonly pending = signal<PendingAction | null>(null);

  /** Today as `YYYY-MM-DD`, in the viewer's own calendar — a date is a calendar question. */
  protected readonly today = computed(() => {
    const now = new Date();
    return `${now.getFullYear()}-${`${now.getMonth() + 1}`.padStart(2, '0')}-${`${now.getDate()}`.padStart(2, '0')}`;
  });

  protected readonly confirmText = computed(() => {
    const action = this.pending();
    if (!action) {
      return null;
    }
    switch (action.kind) {
      case 'retireBill':
        return {
          heading: this.i18n.t('bills.retireHeading', { name: action.bill.name }),
          message: this.i18n.t('bills.retireMessage'),
          action: this.i18n.t('bills.retire'),
          tone: 'ordinary' as const,
        };
      case 'deleteBill':
        return {
          heading: this.i18n.t('bills.deleteHeading', { name: action.bill.name }),
          // Says what goes and what stays. The transactions are the thing somebody is afraid of.
          message: this.i18n.t('bills.deleteMessage'),
          action: this.i18n.t('common.delete'),
          tone: 'destructive' as const,
        };
      case 'forget':
        return {
          heading: this.i18n.t('bills.forgetHeading'),
          // Forgetting is the absence of an assertion, not an assertion that it is due.
          message: this.i18n.t('bills.forgetMessage'),
          action: this.i18n.t('bills.forget'),
          tone: 'destructive' as const,
        };
      case 'retireIncome':
        return {
          heading: this.i18n.t('bills.income.retireHeading', { name: action.source.name }),
          message: this.i18n.t('bills.income.retireMessage'),
          action: this.i18n.t('bills.retire'),
          tone: 'ordinary' as const,
        };
      case 'deleteIncome':
        return {
          heading: this.i18n.t('bills.income.deleteHeading', { name: action.source.name }),
          message: this.i18n.t('bills.income.deleteMessage'),
          action: this.i18n.t('common.delete'),
          tone: 'destructive' as const,
        };
    }
  });

  constructor() {
    void this.load();
  }

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
      const [categories, accounts] = await Promise.all([
        firstValueFrom(this.categoriesService.list()),
        firstValueFrom(this.accountsService.list()),
      ]);
      this.categories.set(categories.content);
      this.accounts.set(accounts.content);
      await this.readPeriod();
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

  /**
   * Reads one month: its bills, its income and the summary.
   *
   * The headline comes from the summary endpoint rather than from these three responses added up,
   * so the figure the page leads with cannot disagree with the blocks beneath it.
   */
  private async readPeriod(): Promise<void> {
    const period = this.period();
    const [bills, income, summary] = await Promise.all([
      firstValueFrom(this.bills.list(period)),
      firstValueFrom(this.income.list(period)),
      firstValueFrom(this.summaries.get(period)),
    ]);
    this.allBills.set(bills.content);
    this.billTotals.set({
      total: bills.total,
      outstanding: bills.outstanding,
      overdue: bills.overdue,
    });
    this.allIncome.set(income.content);
    this.incomeTotal.set(income.total);
    this.summary.set(summary);
  }

  /** Re-reads the month after a write, leaving the categories and accounts already in hand. */
  private async reload(): Promise<void> {
    try {
      await this.readPeriod();
    } catch (thrown) {
      this.loadError.set(asApiError(thrown));
    }
  }

  // ------------------------------------------------------------------------------- the period

  protected async shift(step: number): Promise<void> {
    this.period.set(shiftPeriod(this.period(), step));
    this.resetDialogState();
    await this.reload();
  }

  // --------------------------------------------------------------------------------- the bills

  protected openAddBill(): void {
    this.resetDialogState();
    this.billDialog.set({ bill: null });
  }

  protected openEditBill(bill: Bill): void {
    this.resetDialogState();
    this.billDialog.set({ bill });
  }

  protected closeBillDialog(): void {
    this.billDialog.set(null);
    this.resetDialogState();
  }

  protected async submitBill(form: BillFormValue): Promise<void> {
    const target = this.billDialog();
    if (!target) {
      return;
    }
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      if (target.bill === null) {
        await firstValueFrom(this.bills.create(buildBillCreate(form)));
        this.notice.set('bills.created');
      } else {
        const patch = buildBillPatch(target.bill, form);
        if (patchIsEmpty(patch)) {
          this.closeBillDialog();
          return;
        }
        await firstValueFrom(this.bills.update(target.bill.id, patch));
        this.notice.set('bills.updated');
      }
      this.billDialog.set(null);
      await this.reload();
    } catch (thrown) {
      this.dialogError.set(asApiError(thrown));
    } finally {
      this.dialogBusy.set(false);
    }
  }

  // --------------------------------------------------------------------------- the occurrences

  /**
   * The one-tap action: paid, for what was budgeted, today.
   *
   * No amount and no date in the body — both are optional, and the server defaults them to the
   * expected amount and today. "I paid it" is the thing somebody taps while looking at a bank app.
   */
  protected async markPaid(bill: Bill, occurrence: BillOccurrence): Promise<void> {
    await this.run(
      occurrenceId(occurrence),
      bill.name,
      () => this.bills.record(bill.id, occurrence.dueDate, { status: 'PAID' }),
      'bills.markedPaid',
    );
  }

  protected async skip(bill: Bill, occurrence: BillOccurrence): Promise<void> {
    await this.run(
      occurrenceId(occurrence),
      bill.name,
      () => this.bills.record(bill.id, occurrence.dueDate, { status: 'SKIPPED' }),
      'bills.skipped',
    );
  }

  /** An assertion that it is due — which is not the same as forgetting what was recorded. */
  protected async markDue(bill: Bill, occurrence: BillOccurrence): Promise<void> {
    await this.run(
      occurrenceId(occurrence),
      bill.name,
      () => this.bills.record(bill.id, occurrence.dueDate, { status: 'DUE' }),
      'bills.markedDue',
    );
  }

  protected openPayment(bill: Bill, occurrence: BillOccurrence | null): void {
    this.resetDialogState();
    this.paymentDialog.set({ bill, occurrence });
  }

  protected closePayment(): void {
    this.paymentDialog.set(null);
    this.resetDialogState();
  }

  protected async submitPayment(form: OccurrenceFormValue): Promise<void> {
    const target = this.paymentDialog();
    if (!target) {
      return;
    }
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      // The due date identifies the occurrence; the sheet only chooses it for an irregular bill.
      const dueDate = target.occurrence?.dueDate ?? form.dueDate;
      await firstValueFrom(this.bills.record(target.bill.id, dueDate, buildPaidRecord(form)));
      this.notice.set('bills.markedPaid');
      this.paymentDialog.set(null);
      await this.reload();
    } catch (thrown) {
      this.dialogError.set(asApiError(thrown));
    } finally {
      this.dialogBusy.set(false);
    }
  }

  // -------------------------------------------------------------------------------- the income

  protected openAddIncome(): void {
    this.resetDialogState();
    this.incomeDialog.set({ source: null });
  }

  protected openEditIncome(source: IncomeSource): void {
    this.resetDialogState();
    this.incomeDialog.set({ source });
  }

  protected closeIncomeDialog(): void {
    this.incomeDialog.set(null);
    this.resetDialogState();
  }

  protected async submitIncome(form: IncomeFormValue): Promise<void> {
    const target = this.incomeDialog();
    if (!target) {
      return;
    }
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      if (target.source === null) {
        await firstValueFrom(this.income.create(buildIncomeCreate(form)));
        this.notice.set('bills.income.created');
      } else {
        const patch = buildIncomePatch(target.source, form);
        if (patchIsEmpty(patch)) {
          this.closeIncomeDialog();
          return;
        }
        await firstValueFrom(this.income.update(target.source.id, patch));
        this.notice.set('bills.income.updated');
      }
      this.incomeDialog.set(null);
      await this.reload();
    } catch (thrown) {
      this.dialogError.set(asApiError(thrown));
    } finally {
      this.dialogBusy.set(false);
    }
  }

  // ------------------------------------------------------------------- asking before destroying

  protected askToRetireBill(bill: Bill): void {
    this.pending.set({ kind: 'retireBill', bill });
  }

  protected askToDeleteBill(bill: Bill): void {
    this.pending.set({ kind: 'deleteBill', bill });
  }

  protected askToForget(bill: Bill, occurrence: BillOccurrence): void {
    this.pending.set({ kind: 'forget', bill, occurrence });
  }

  protected askToRetireIncome(source: IncomeSource): void {
    this.pending.set({ kind: 'retireIncome', source });
  }

  protected askToDeleteIncome(source: IncomeSource): void {
    this.pending.set({ kind: 'deleteIncome', source });
  }

  protected cancelPending(): void {
    this.pending.set(null);
  }

  protected async confirmPending(): Promise<void> {
    const action = this.pending();
    if (!action) {
      return;
    }
    this.pending.set(null);
    switch (action.kind) {
      case 'retireBill':
        await this.run(
          action.bill.id,
          action.bill.name,
          () => this.bills.update(action.bill.id, { version: action.bill.version, active: false }),
          'bills.retired',
        );
        break;
      case 'deleteBill':
        await this.run(
          action.bill.id,
          action.bill.name,
          () => this.bills.remove(action.bill.id),
          'bills.deleted',
        );
        break;
      case 'forget':
        await this.run(
          occurrenceId(action.occurrence),
          action.bill.name,
          () => this.bills.forget(action.bill.id, action.occurrence.dueDate),
          'bills.forgotten',
        );
        break;
      case 'retireIncome':
        await this.run(
          action.source.id,
          action.source.name,
          () =>
            this.income.update(action.source.id, {
              version: action.source.version,
              active: false,
            }),
          'bills.income.retired',
        );
        break;
      case 'deleteIncome':
        await this.run(
          action.source.id,
          action.source.name,
          () => this.income.remove(action.source.id),
          'bills.income.deleted',
        );
        break;
    }
  }

  protected async restoreBill(bill: Bill): Promise<void> {
    await this.run(
      bill.id,
      bill.name,
      () => this.bills.update(bill.id, { version: bill.version, active: true }),
      'bills.restored',
    );
  }

  protected async restoreIncome(source: IncomeSource): Promise<void> {
    await this.run(
      source.id,
      source.name,
      () => this.income.update(source.id, { version: source.version, active: true }),
      'bills.income.restored',
    );
  }

  /**
   * Runs one row-level request, keeping the failure beside the thing it was about.
   *
   * A refused write must not empty the screen: the state is reloaded only on success, and on
   * failure the page is exactly as it was with an explanation naming the bill.
   */
  private async run(
    id: string,
    subject: string,
    request: () => Observable<unknown>,
    said: MessageKey,
  ): Promise<void> {
    this.busyId.set(id);
    this.actionError.set(null);
    this.actionErrorSubject.set(null);
    this.notice.set(null);
    try {
      await firstValueFrom(request());
      this.notice.set(said);
      await this.reload();
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
      this.actionErrorSubject.set(subject);
    } finally {
      this.busyId.set(null);
    }
  }

  // ------------------------------------------------------------------------------ what to show

  /** A schedule in words — "Every other Wednesday, from 5 August 2026". */
  protected said(schedule: Schedule): string {
    return scheduleSaid(schedule, this.i18n);
  }

  /** Whether a figure the server sent is negative. Read from its sign, never from a parse. */
  protected readonly negative = isNegative;

  /** The id a row's in-flight state is keyed on. An occurrence has no id, so its date serves. */
  protected readonly rowId = occurrenceId;

  /**
   * Which sentence an occurrence's figures get.
   *
   * A payment that surprised shows **both** numbers, because the difference is the useful one. A
   * payment that did not is one figure: repeating it would make every row look like a discrepancy.
   */
  /**
   * Whether to print the budgeted figure as well as the paid one.
   *
   * <b>Only when they differ.</b> The difference is the useful number (ADR-0025); printing
   * "Budgeted $40.00  Paid $40.00" on every settled row says nothing twice, and a weekly bill
   * would say it four times a month. When they agree there is one figure to report, so there is
   * one figure.
   */
  protected showsBothFigures(occurrence: BillOccurrence): boolean {
    return (
      occurrence.status === 'PAID' &&
      occurrence.actualAmount !== null &&
      !sameAmount(occurrence.actualAmount, occurrence.expectedAmount)
    );
  }

  /** What the single figure is: what was really paid, when that is known. */
  protected theFigure(occurrence: BillOccurrence): string {
    return occurrence.status === 'PAID' && occurrence.actualAmount !== null
      ? occurrence.actualAmount
      : occurrence.expectedAmount;
  }

  /** Why a bill has no occurrence this month, which depends on the kind of bill it is. */
  protected emptyOccurrencesKey(bill: Bill): MessageKey {
    return bill.schedule.kind === 'IRREGULAR' ? 'bills.noneIrregular' : 'bills.noneThisMonth';
  }

  /** Two keys rather than "1 arrivals": the catalogue has no plural machinery. */
  protected arrivalsKey(source: IncomeSource): MessageKey {
    return source.dates.length === 1 ? 'bills.income.arrivalsOne' : 'bills.income.arrivals';
  }

  private resetDialogState(): void {
    this.dialogBusy.set(false);
    this.dialogError.set(null);
    this.notice.set(null);
    this.actionError.set(null);
    this.actionErrorSubject.set(null);
  }
}

/** An occurrence is identified by its bill and its due date — it usually has no row at all. */
function occurrenceId(occurrence: BillOccurrence): string {
  return `${occurrence.billId}:${occurrence.dueDate}`;
}
