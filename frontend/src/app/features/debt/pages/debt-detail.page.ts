import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { map } from 'rxjs/operators';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { DateOnlyPipe, MoneyPipe } from '../../../core/i18n/format.pipes';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MessageKey, MessageParams } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { compareAmounts } from '../../../core/money';
import { currentPeriod } from '../../../core/period';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import {
  ChartMarker,
  ChartSeries,
  LineChartComponent,
} from '../../../shared/components/line-chart.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import { Account } from '../../accounts/data/account.models';
import { AccountsService } from '../../accounts/data/accounts.service';
import { Bill } from '../../bills/data/bills.models';
import { BillsService } from '../../bills/data/bills.service';
import { ConfirmEstimateComponent } from '../components/confirm-estimate.component';
import { DebtDialogComponent } from '../components/debt-dialog.component';
import { LogPaymentComponent } from '../components/log-payment.component';
import {
  BalanceFormValue,
  DebtFormValue,
  PaymentFormValue,
  buildConfirmBalance,
  buildDebtPatch,
  buildLogPayment,
  monthBefore,
  monthsBetween,
  patchIsEmpty,
} from '../data/debt-patch';
import {
  Debt,
  DebtDetail,
  DebtPayment,
  PaymentPreview,
  SeriesPoint,
  TermSource,
  balanceSourceKey,
  debtKindKey,
  isPaymentPreview,
  projectionReasonKey,
  provenanceKey,
} from '../data/debt.models';
import { DebtsService } from '../data/debts.service';

/** A translated sentence waiting for its parameters — the template does no logic. */
interface Said {
  readonly key: MessageKey;
  readonly params?: MessageParams;
}

/**
 * One cell of the solver panel, with **which of the three kinds of figure it is**.
 *
 * `ENTERED` carries no tag — an unmarked cell is the household's own word. `SOLVED` carries
 * `Solved`. `FROM_PLAN` says *from your plan*. A `null` source is a cell with no figure at all,
 * which is not the same as a zero and is never tagged.
 */
interface TermCell {
  readonly id: string;
  readonly label: MessageKey;
  readonly value: string;
  readonly source: TermSource | null;
  /** No figure: a rate that was neither entered nor derivable, or an absent original balance. */
  readonly missing: MessageKey | null;
}

/** One row of the history, with how it got here. */
interface PaymentRow {
  readonly id: string;
  readonly payment: DebtPayment;
  readonly provenance: MessageKey;
  readonly paidFrom: string | null;
}

/**
 * One debt: what its terms are, and what has actually been paid against it.
 *
 * Six behaviours here are the feature's rules rather than UI taste:
 *
 * - **Three labels, not two.** Entered cells carry nothing, derived ones carry `Solved`, and the
 *   payment the plan assigned says *from your plan*. A figure the app worked out must never be
 *   mistaken for one the household asserted — that is what the whole panel exists for, and the
 *   count above it (*"4 entered · 3 solved"*) is computed from the cells rather than written down.
 * - **The balance is never solved.** It is the one number a household can read off a statement,
 *   and it moves through its own endpoint with the date it was true, never through the terms
 *   editor.
 * - **The at-minimum line is the comparison, not the plan**, and is labelled as such. It is the
 *   same figure deliberately kept off the plan page's "Clears" column, and this is where it
 *   belongs.
 * - **An inferred split is never shown as a confirmed one.** Every payment row says how it got
 *   here, and a `STATEMENT_CORRECTION` says so in the household's own words.
 * - **Progress is absent without an original balance**, never rendered as 0%.
 * - **A `VIEWER` sees every figure and has no control at all** — no *Edit terms*, no *Log a
 *   payment*, no *Confirm*.
 */
@Component({
  selector: 'app-debt-detail-page',
  imports: [
    RouterLink,
    TranslatePipe,
    MoneyPipe,
    DateOnlyPipe,
    ErrorAlertComponent,
    NoHouseholdComponent,
    LineChartComponent,
    DebtDialogComponent,
    LogPaymentComponent,
    ConfirmEstimateComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './debt-detail.page.html',
  styleUrl: './debt-detail.page.css',
})
export class DebtDetailPage {
  private readonly debtsService = inject(DebtsService);
  private readonly accountsService = inject(AccountsService);
  private readonly billsService = inject(BillsService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  /** The route takes the debt's **id**, not a slug: a slug would leak the name into logs. */
  private readonly id = toSignal(
    inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('id') ?? '')),
    { initialValue: '' },
  );

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);

  protected readonly detail = signal<DebtDetail | null>(null);
  private readonly payments = signal<readonly DebtPayment[]>([]);
  private readonly paymentTotal = signal(0);
  private readonly siblings = signal<readonly Debt[]>([]);
  protected readonly accounts = signal<readonly Account[]>([]);
  protected readonly bills = signal<readonly Bill[]>([]);

  protected readonly editing = signal(false);
  protected readonly logging = signal(false);
  protected readonly confirming = signal(false);
  protected readonly preview = signal<PaymentPreview | null>(null);
  protected readonly previewUnavailable = signal(false);

  /**
   * Which preview request is the current one.
   *
   * Typing produces a stream of questions and the answers can arrive in any order. Without this,
   * a slow answer about `44` lands after a quick one about `445` and the sheet shows the split for
   * an amount the field no longer holds — a figure that is true of nothing on screen.
   */
  private previewRequest = 0;

  protected readonly dialogBusy = signal(false);
  protected readonly dialogError = signal<ApiError | null>(null);
  protected readonly notice = signal<Said | null>(null);

  protected readonly canWrite = computed(() => {
    const role = this.auth.membership()?.role;
    return role === 'OWNER' || role === 'MEMBER';
  });

  protected readonly debt = computed(() => this.detail()?.debt ?? null);

  /** Today as `YYYY-MM-DD`, in the viewer's own calendar — a date is a calendar question. */
  protected readonly today = computed(() => {
    const now = new Date();
    return `${now.getFullYear()}-${`${now.getMonth() + 1}`.padStart(2, '0')}-${`${now.getDate()}`.padStart(2, '0')}`;
  });

  protected readonly naming = computed<Said | null>(() => {
    const debt = this.debt();
    if (!debt) {
      return null;
    }
    return debt.servicer
      ? {
          key: 'debt.naming',
          params: { kind: this.i18n.t(debtKindKey(debt.kind)), servicer: debt.servicer },
        }
      : { key: debtKindKey(debt.kind) };
  });

  /**
   * The household's debts in rung order — the switcher on desktop, the stepper on a phone.
   *
   * Narrowed to a non-null rung here rather than in the template: a debt outside the ladder has
   * no position to step through, and the switcher is about position.
   */
  protected readonly ladder = computed<readonly { id: string; name: string; rung: number }[]>(() =>
    this.siblings()
      .filter((debt): debt is Debt & { rung: number } => debt.rung !== null)
      .sort((left, right) => left.rung - right.rung)
      .map((debt) => ({ id: debt.id, name: debt.name, rung: debt.rung })),
  );

  private readonly position = computed(() =>
    this.ladder().findIndex((debt) => debt.id === this.id()),
  );

  protected readonly previous = computed(() => {
    const at = this.position();
    return at > 0 ? this.ladder()[at - 1] : null;
  });

  protected readonly next = computed(() => {
    const at = this.position();
    return at !== -1 && at < this.ladder().length - 1 ? this.ladder()[at + 1] : null;
  });

  // -------------------------------------------------------------------------- 1. loan terms

  /**
   * The solver panel.
   *
   * Eight cells, and the count above them is derived from these rather than written down: the
   * designs' own *"4 entered · 3 solved"* only adds up under the reading where the eighth cell
   * comes from the plan, so a hard-coded count would eventually describe a different panel.
   */
  protected readonly terms = computed<readonly TermCell[]>(() => {
    const debt = this.debt();
    if (!debt) {
      return [];
    }
    const money = (amount: string | null): string => this.i18n.formatMoney(amount, debt.currency);
    const cells: TermCell[] = [
      {
        id: 'originalBalance',
        label: 'debt.term.originalBalance',
        value: money(debt.originalBalance),
        // Entered, and simply absent when nobody gave one — never a zero, and never solved.
        source: debt.originalBalance === null ? null : 'ENTERED',
        missing: debt.originalBalance === null ? 'debt.term.noOriginal' : null,
      },
      {
        id: 'balance',
        label: 'debt.term.balance',
        value: money(debt.balance),
        // Never solved, ever. An app that derived what you owe from a rate and a payment schedule
        // would have replaced a fact with an inference and put it in the headline position.
        source: 'ENTERED',
        missing: null,
      },
      {
        id: 'apr',
        label: 'debt.term.apr',
        value: debt.apr === null ? '' : `${this.i18n.formatRate(debt.apr)}%`,
        // A rate that was neither entered nor derivable carries no tag: `Solved` means "this was
        // worked out", and there is nothing here to have been worked out.
        source: debt.apr === null ? null : debt.aprSource,
        missing: debt.apr === null ? 'debt.rate.unavailable' : null,
      },
      {
        id: 'minimumPayment',
        label: 'debt.term.minimum',
        value: money(debt.minimumPayment),
        // Always entered. It never takes part in this debt's own projection — it is the floor the
        // plan allocates, and the second input to the at-minimum comparison line.
        source: 'ENTERED',
        missing: null,
      },
      {
        id: 'payment',
        label: 'debt.term.payment',
        value: money(debt.payment),
        source: debt.payment === null ? null : debt.paymentSource,
        missing: debt.payment === null ? 'debt.term.noPayment' : null,
      },
      {
        id: 'monthsLeft',
        label: 'debt.term.monthsLeft',
        value: debt.monthsLeft === null ? '' : String(debt.monthsLeft),
        source: debt.monthsLeft === null ? null : debt.horizonSource,
        missing: debt.monthsLeft === null ? this.noHorizonKey(debt) : null,
      },
      {
        id: 'payoffMonth',
        label: 'debt.term.payoffMonth',
        value: debt.payoffMonth === null ? '' : this.i18n.formatMonth(debt.payoffMonth),
        source: debt.payoffMonth === null ? null : debt.horizonSource,
        missing: debt.payoffMonth === null ? this.noHorizonKey(debt) : null,
      },
      {
        id: 'totalInterestLeft',
        label: 'debt.term.totalInterest',
        value: money(debt.totalInterestLeft),
        // Always derived: it is a sum over the projection and no column holds it.
        source: debt.totalInterestLeft === null ? null : 'SOLVED',
        // Its own words: "Never, at this payment" answers *when*, and this cell asks *how much*.
        missing: debt.totalInterestLeft === null ? this.noTotalKey(debt) : null,
      },
    ];
    return cells;
  });

  /** *"4 entered · 3 solved"*, counted from the panel rather than asserted. */
  protected readonly termCount = computed<Said>(() => {
    const cells = this.terms();
    return {
      key: 'debt.terms.count',
      params: {
        entered: cells.filter((cell) => cell.source === 'ENTERED').length,
        solved: cells.filter((cell) => cell.source === 'SOLVED').length,
      },
    };
  });

  /** Where the balance came from, named. **No two sources read the same.** */
  protected readonly balanceLine = computed<Said | null>(() => {
    const debt = this.debt();
    if (!debt) {
      return null;
    }
    const anchor = this.i18n.formatMoney(debt.anchorBalance, debt.currency);
    const date = this.i18n.formatDate(debt.anchorDate);
    if (debt.balanceStale) {
      return said('debt.balance.staleLine', { amount: anchor, date });
    }
    if (debt.balanceSource === 'ESTIMATED') {
      return said('debt.balance.estimatedLine', {
        months: debt.estimatedMonths,
        amount: anchor,
        date,
      });
    }
    return said('debt.balance.confirmedLine', {
      source: this.i18n.t(balanceSourceKey(debt.balanceSource), {
        date: this.i18n.formatDate(debt.balanceAsOf),
      }),
      amount: anchor,
      date,
    });
  });

  /** The short word beside the headline figure. **No two sources read the same.** */
  protected readonly balanceSourceLabel = computed<MessageKey>(() => {
    const debt = this.debt();
    if (!debt) {
      return 'debt.balance.owedNow';
    }
    if (debt.balanceStale) {
      return 'debt.balance.staleLabel';
    }
    return debt.balanceSource === 'ESTIMATED'
      ? 'debt.balance.estimatedLabel'
      : 'debt.balance.owedNow';
  });

  protected readonly estimated = computed(() => {
    const debt = this.debt();
    return debt !== null && (debt.balanceSource === 'ESTIMATED' || debt.balanceStale);
  });

  // ------------------------------------------------------------------------- 2. balance now

  /** *"$4,470 paid off · 42% of $10,650"* — **absent** without an original balance. */
  protected readonly progress = computed<Said | null>(() => {
    const debt = this.debt();
    if (
      !debt ||
      debt.progressPercent === null ||
      debt.paidOffSoFar === null ||
      debt.originalBalance === null
    ) {
      return null;
    }
    return {
      key: 'debt.progress',
      params: {
        paid: this.i18n.formatMoney(debt.paidOffSoFar, debt.currency),
        percent: this.i18n.formatRate(debt.progressPercent),
        original: this.i18n.formatMoney(debt.originalBalance, debt.currency),
      },
    };
  });

  // --------------------------------------------------------------------- 3. balance over time

  /**
   * Two lines: what this debt is actually paying, and **the comparison** at its minimum.
   *
   * The at-minimum line is named as a comparison rather than left to be inferred — it is a
   * projection of a payment the household is not making, and the same figure deliberately kept
   * out of the plan page's "Clears" column. It is also **kinder than reality**: a real card
   * minimum falls as the balance falls, which stretches the payoff well beyond a flat figure.
   */
  protected readonly chart = computed<readonly ChartSeries[]>(() => {
    const detail = this.detail();
    const debt = this.debt();
    if (!detail || !debt) {
      return [];
    }
    const pace = this.lineOf(detail.pace, debt, 'debt.chart.pace', 'pace', 'selected');
    if (detail.atMinimumIsTheSameLine) {
      // Paying the minimum IS the plan here, so there is one line and one story.
      return [pace];
    }
    return [
      pace,
      this.lineOf(detail.atMinimum, debt, 'debt.chart.atMinimum', 'at-minimum', 'compared'),
    ];
  });

  /**
   * Why there is no line, when there is none.
   *
   * A debt whose payment does not cover its interest has no projection and therefore no series —
   * a fact about the terms, not a fault in them. The card says so where the picture would be.
   */
  protected readonly chartUnavailable = computed(() => {
    const debt = this.debt();
    if (!debt || this.chart().some((line) => line.points.length >= 2)) {
      return '';
    }
    return this.i18n.t('debt.chart.noLine', {
      reason: this.i18n.t(projectionReasonKey(debt.projectionReason ?? '')),
    });
  });

  /** Whether the comparison line is on screen — the note about it is pointless otherwise. */
  protected readonly comparisonDrawn = computed(
    () => this.chart().filter((line) => line.points.length >= 2).length > 1,
  );

  protected readonly chartMarker = computed<ChartMarker | null>(() => {
    const debt = this.debt();
    if (!debt?.payoffMonth) {
      return null;
    }
    return {
      month: debt.payoffMonth,
      label: this.i18n.t('debt.chart.clearsMarker', {
        month: this.i18n.formatMonth(debt.payoffMonth),
      }),
    };
  });

  protected readonly chartAssumption = computed(() => {
    const detail = this.detail();
    const debt = this.debt();
    if (!detail || !debt) {
      return '';
    }
    const assumes = this.i18n.t('debt.chart.detailAssumes', {
      amount: this.i18n.formatMoney(debt.payment ?? debt.minimumPayment, debt.currency),
    });
    return detail.seriesMonthStride > 1
      ? `${assumes} ${this.i18n.t('debt.chart.stride', { months: detail.seriesMonthStride })}`
      : assumes;
  });

  /**
   * *"Paying $260 extra is buying you 38 months"* — the sentence that makes the chart mean
   * something.
   *
   * **Months only.** The designs also name a money saving, which is the difference between two
   * interest totals; the API carries the total for the plan's payment and not for the minimum,
   * and subtracting two figures in the browser is arithmetic on money whichever two they are.
   */
  protected readonly chartSaid = computed<Said | null>(() => {
    const detail = this.detail();
    const debt = this.debt();
    if (!detail || !debt || detail.atMinimumIsTheSameLine) {
      return null;
    }
    const extra = debt.planExtra;
    // The debt's own `payoffMonth` is authoritative for the line it is paying; the comparison has
    // no such field, so it is read off the series the same way — and the two agree, which is what
    // makes the reading sound rather than assumed.
    const paceEnd = debt.payoffMonth ?? clearedMonth(detail.pace);
    const minimumEnd = clearedMonth(detail.atMinimum);
    const sooner = monthsBetween(paceEnd, minimumEnd);
    if (extra === null || sooner === null || sooner <= 0) {
      return null;
    }
    const positive = compareAmounts(extra, '0');
    if (positive === null || positive <= 0) {
      return null;
    }
    return {
      key: 'debt.chart.buying',
      params: {
        extra: this.i18n.formatMoney(extra, debt.currency),
        months: sooner,
        soon: this.i18n.formatMonth(paceEnd ?? ''),
        late: this.i18n.formatMonth(minimumEnd ?? ''),
      },
    };
  });

  // ------------------------------------------------------------------- 4. payment history

  protected readonly rows = computed<readonly PaymentRow[]>(() => {
    const names = new Map(this.accounts().map((account) => [account.id, account.name]));
    return this.payments().map((payment) => ({
      id: payment.id,
      payment,
      provenance: provenanceKey(payment.provenance),
      paidFrom:
        payment.paidFromAccountId === null ? null : (names.get(payment.paidFromAccountId) ?? null),
    }));
  });

  /** *"6 you logged · 1 statement correction"*, counted from the rows. */
  protected readonly provenanceCount = computed<Said>(() => ({
    key: 'debt.history.counts',
    params: {
      logged: this.payments().filter((one) => one.provenance === 'USER_LOGGED').length,
      corrections: this.payments().filter((one) => one.provenance === 'STATEMENT_CORRECTION')
        .length,
    },
  }));

  /** True when the history runs past one page: the rest is a page away, not missing. */
  protected readonly morePages = computed(() => this.paymentTotal() > this.payments().length);

  protected readonly paymentCountSaid = computed<Said>(() => ({
    key: 'debt.history.showAll',
    params: { count: this.payments().length },
  }));

  constructor() {
    // The rung switcher and the stepper move between debts on the SAME route, so the component is
    // reused and only the parameter changes. Reading it in the constructor alone left the page
    // showing the debt somebody had just navigated away from — the URL said one thing and the
    // heading another. The read is untracked so this reacts to the id and to nothing else.
    effect(() => {
      const id = this.id();
      if (id !== '') {
        untracked(() => void this.load(id));
      }
    });
  }

  protected async load(id = this.id()): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    this.resetDialogs();
    this.notice.set(null);
    await this.auth.ensureLoaded();
    if (!this.auth.membership()) {
      this.noHousehold.set(true);
      this.loading.set(false);
      return;
    }
    try {
      await this.read(id);
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

  private async read(id: string): Promise<void> {
    const [detail, payments, debts, accounts, bills] = await Promise.all([
      firstValueFrom(this.debtsService.get(id)),
      firstValueFrom(this.debtsService.payments(id)),
      firstValueFrom(this.debtsService.list()),
      firstValueFrom(this.accountsService.list()),
      firstValueFrom(this.billsService.list(currentPeriod(), false)),
    ]);
    this.detail.set(detail);
    this.payments.set(payments.content);
    this.paymentTotal.set(payments.totalElements);
    this.siblings.set(debts.debts);
    this.accounts.set(accounts.content);
    this.bills.set(bills.content);
  }

  private async reload(): Promise<void> {
    try {
      await this.read(this.id());
    } catch (thrown) {
      this.loadError.set(asApiError(thrown));
    }
  }

  // ---------------------------------------------------------------------------- the actions

  protected openEdit(): void {
    this.resetDialogs();
    this.editing.set(true);
  }

  protected openLog(): void {
    this.resetDialogs();
    this.logging.set(true);
  }

  protected openConfirm(): void {
    this.resetDialogs();
    this.confirming.set(true);
  }

  protected closeDialogs(): void {
    this.resetDialogs();
  }

  /** *"Log a payment instead"* — one sheet hands over to the other without closing the page. */
  protected logInstead(): void {
    this.resetDialogs();
    this.logging.set(true);
  }

  protected async submitTerms(form: DebtFormValue): Promise<void> {
    const debt = this.debt();
    if (!debt) {
      return;
    }
    const patch = buildDebtPatch(debt, form);
    if (patchIsEmpty(patch)) {
      this.closeDialogs();
      return;
    }
    await this.run(
      () => firstValueFrom(this.debtsService.update(debt.id, patch)),
      // Naming the released field is the interaction: hand one back and the screen says it is
      // solved from now on.
      patch.apr === null ? { key: 'debt.rateReleased' } : { key: 'debt.updated' },
    );
  }

  /**
   * Asks what an amount would record.
   *
   * **A convenience, never a gate.** If it fails or is refused the sheet says the split will come
   * once the payment is recorded and goes on accepting it; nothing here computes a split, not
   * even as a fallback, because having two implementations of the interest formula is the thing
   * this request exists to avoid.
   */
  protected async askPreview(amount: string): Promise<void> {
    const debt = this.debt();
    const request = ++this.previewRequest;
    if (!debt || amount === '') {
      this.preview.set(null);
      this.previewUnavailable.set(false);
      return;
    }
    try {
      const answer: unknown = await firstValueFrom(
        this.debtsService.previewPayment(debt.id, { amount }),
      );
      if (request !== this.previewRequest) {
        return;
      }
      // `unknown` plus a guard: this goes on screen beside the word "interest", and a body that
      // is merely shaped like JSON would render as a split with blanks in it.
      if (isPaymentPreview(answer)) {
        this.preview.set(answer);
        this.previewUnavailable.set(false);
      } else {
        this.preview.set(null);
        this.previewUnavailable.set(true);
      }
    } catch {
      if (request !== this.previewRequest) {
        return;
      }
      this.preview.set(null);
      this.previewUnavailable.set(true);
    }
  }

  protected async submitPayment(form: PaymentFormValue): Promise<void> {
    const debt = this.debt();
    if (!debt) {
      return;
    }
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      // The split comes back from the server, computed under the rate in force on the day. It is
      // reported here rather than predicted in the sheet, because a second implementation of
      // `balance × apr ÷ 1200` in the browser is the one place the two could disagree.
      const logged = await firstValueFrom(
        this.debtsService.logPayment(debt.id, buildLogPayment(form)),
      );
      this.resetDialogs();
      this.notice.set({
        key: 'debt.payment.recorded',
        params: {
          interest: this.i18n.formatMoney(logged.interest, logged.currency),
          principal: this.i18n.formatMoney(logged.principal, logged.currency),
          balance: this.i18n.formatMoney(logged.balanceAfter, logged.currency),
        },
      });
      await this.reload();
    } catch (thrown) {
      this.dialogError.set(asApiError(thrown));
    } finally {
      this.dialogBusy.set(false);
    }
  }

  protected async submitBalance(form: BalanceFormValue): Promise<void> {
    const debt = this.debt();
    if (!debt) {
      return;
    }
    await this.run(
      () => firstValueFrom(this.debtsService.confirmBalance(debt.id, buildConfirmBalance(form))),
      { key: 'debt.balanceConfirmed' },
    );
  }

  /**
   * One write, with its failure kept beside the sheet that caused it.
   *
   * A refused write must not empty the screen: the page is reloaded only on success, and on
   * failure the sheet stays open with an explanation in it.
   */
  private async run(request: () => Promise<unknown>, said: Said): Promise<void> {
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      await request();
      this.resetDialogs();
      this.notice.set(said);
      await this.reload();
    } catch (thrown) {
      this.dialogError.set(asApiError(thrown));
    } finally {
      this.dialogBusy.set(false);
    }
  }

  private resetDialogs(): void {
    this.editing.set(false);
    this.logging.set(false);
    this.confirming.set(false);
    this.dialogBusy.set(false);
    this.dialogError.set(null);
    // A preview belongs to the sheet that asked for it. Leaving it behind would show the next
    // sheet a split for an amount nobody has typed yet.
    this.previewRequest += 1;
    this.preview.set(null);
    this.previewUnavailable.set(false);
  }

  // ------------------------------------------------------------------------- what to show

  private lineOf(
    points: readonly SeriesPoint[],
    debt: Debt,
    name: MessageKey,
    id: string,
    role: 'selected' | 'compared',
  ): ChartSeries {
    const label = this.i18n.t(name);
    const mapped = points.map((point) => ({
      month: point.month,
      label: this.i18n.formatMonth(point.month),
      amount: point.balance,
    }));
    const first = mapped[0];
    const last = mapped[mapped.length - 1];
    const said =
      first && last
        ? this.i18n.t('debt.chart.said', {
            name: label,
            from: this.i18n.formatMoney(first.amount, debt.currency),
            fromMonth: first.label,
            to: this.i18n.formatMoney(last.amount, debt.currency),
            toMonth: last.label,
          })
        : this.i18n.t('debt.chart.saidNothing', {
            name: label,
            reason: this.i18n.t(projectionReasonKey(debt.projectionReason ?? '')),
          });
    return { id, name: label, said, points: mapped, role };
  }

  /** Why there is no horizon: `never-clears` is a fact about the terms, not a fault in them. */
  private noHorizonKey(debt: Debt): MessageKey {
    return debt.projectionReason === 'never-clears' ? 'debt.term.neverClears' : 'debt.term.noValue';
  }

  /** The same fact, answering "how much" rather than "when". */
  private noTotalKey(debt: Debt): MessageKey {
    return debt.projectionReason === 'never-clears'
      ? 'debt.term.noInterestTotal'
      : 'debt.term.noValue';
  }
}

/** A sentence and its parameters, typed once so a branch cannot widen the shape. */
function said(key: MessageKey, params?: MessageParams): Said {
  return { key, params };
}

/**
 * The month a line reaches zero in.
 *
 * A point is what is still owed at the **start** of that month, so the first point at zero is the
 * month after it cleared: the month before that is the answer. For a debt's own pace this
 * reproduces `payoffMonth` exactly.
 */
function clearedMonth(points: readonly SeriesPoint[]): string | null {
  const zero = points.find((point) => compareAmounts(point.balance, '0') === 0);
  return zero === undefined ? null : monthBefore(zero.month);
}
