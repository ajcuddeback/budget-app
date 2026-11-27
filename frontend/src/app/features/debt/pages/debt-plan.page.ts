import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { MoneyPipe } from '../../../core/i18n/format.pipes';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MessageKey, MessageParams } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { addAmounts, compareAmounts, sameAmount, trimAmount } from '../../../core/money';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
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
import { PeriodSummaryService } from '../../bills/data/summary.service';
import { currentPeriod } from '../../../core/period';
import { DebtDialogComponent } from '../components/debt-dialog.component';
import {
  DebtFormValue,
  buildDebtCreate,
  buildDebtPatch,
  monthsBetween,
  patchIsEmpty,
} from '../data/debt-patch';
import {
  CurrencyGroup,
  Debt,
  DebtList,
  PayoffStrategy,
  Plan,
  Rung,
  StrategyProjection,
  balanceSourceKey,
  debtKindKey,
  projectionReasonKey,
  strategyKey,
} from '../data/debt.models';
import { DebtPlanService } from '../data/debt-plan.service';
import { DebtsService } from '../data/debts.service';

/** A translated sentence waiting for its parameters — the template does no logic (style guide). */
interface Said {
  readonly key: MessageKey;
  readonly params?: MessageParams;
}

/** One debt as both structures render it: a table row on desktop, a ladder card on a phone. */
interface DebtRowView {
  readonly id: string;
  readonly name: string;
  /** Whether this rung has somewhere to go. The first has no up; the last has no down. */
  readonly canMoveUp: boolean;
  readonly canMoveDown: boolean;
  readonly currency: string;
  readonly balance: string;
  readonly naming: Said;
  readonly rung: number | null;
  readonly rate: Said;
  readonly rateSolved: boolean;
  readonly clears: Said;
  readonly paying: Said;
  readonly progress: Said | null;
  readonly balanceNote: Said;
  readonly balanceEstimated: boolean;
  readonly debt: Debt;
}

/** One comparison card. */
interface StrategyCardView {
  readonly strategy: PayoffStrategy;
  readonly name: string;
  readonly rationale: MessageKey;
  readonly selected: boolean;
  readonly debtFree: Said;
  readonly firstWin: Said | null;
  readonly totalInterest: string | null;
}

/** Everything one currency's section draws. */
interface GroupView {
  readonly currency: string;
  readonly planCurrency: boolean;
  readonly headline: Said;
  readonly assumption: string;
  readonly series: readonly ChartSeries[];
  readonly unavailable: string;
  readonly coincidentLabel: string;
  readonly marker: ChartMarker | null;
  readonly cards: readonly StrategyCardView[];
  /** What the order in force costs, when it is not one of the two cards below. */
  readonly selected: Said | null;
  readonly comparison: Said | null;
  readonly belowMinimums: Said | null;
  readonly rows: readonly DebtRowView[];
}

/**
 * The what-if's step, in the household's base currency.
 *
 * A round figure in whatever the household's money is, the way the designs put it — not a
 * converted one, and never read back from the server. It is added to the plan's monthly amount by
 * `core/money.ts`'s exact `bigint` arithmetic and handed to a `GET` that **writes nothing**; every
 * figure the sentence then states comes back from the server.
 */
const WHAT_IF_STEP = '50';

/**
 * Debt plan — in what order do I attack these, and when am I free?
 *
 * Six behaviours here are the feature's rules rather than UI taste, and each would otherwise
 * render as a lie:
 *
 * - **Both strategies are always drawn**, whichever is selected. The comparison is a product
 *   requirement — *"compute both, show both, let the household choose"* — not a view of the
 *   selection.
 * - **`extra` can be negative** (ADR-0036): a debt owing less than its own minimum clears in month
 *   one, and `minimum + extra` is what was really allocated. The row says *"all that is left to
 *   clear it"* rather than putting a minus sign beside the word "extra".
 * - **A rate that is neither entered nor derivable says so.** `aprDerivable: false` with a null
 *   `apr` never prints 0% — a figure nobody can check is worse than an admission.
 * - **A stale balance asks for a statement.** Past twelve estimated months the screen stops
 *   extrapolating and says what it needs.
 * - **The empty state renders no chart, no $0 and no debt-free date.** A household with no debt
 *   being told it is debt-free *this month* reads as a bug, not a congratulation.
 * - **A `VIEWER` sees every figure and has no control at all** — not disabled-looking, absent.
 *
 * Nothing here does arithmetic on money except the what-if's `+50`, which is `core/money.ts`'s
 * exact `bigint` path and goes to a projection that stores nothing. Every figure on screen is a
 * string the server computed (ADR-0006).
 */
@Component({
  selector: 'app-debt-plan-page',
  imports: [
    RouterLink,
    ReactiveFormsModule,
    TranslatePipe,
    MoneyPipe,
    ErrorAlertComponent,
    FieldErrorComponent,
    NoHouseholdComponent,
    LineChartComponent,
    DebtDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './debt-plan.page.html',
  styleUrl: './debt-plan.page.css',
})
export class DebtPlanPage {
  private readonly fb = inject(NonNullableFormBuilder);
  private readonly debtsService = inject(DebtsService);
  private readonly plans = inject(DebtPlanService);
  private readonly accountsService = inject(AccountsService);
  private readonly billsService = inject(BillsService);
  private readonly summaries = inject(PeriodSummaryService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);

  private readonly plan = signal<Plan | null>(null);
  protected readonly debts = signal<DebtList | null>(null);
  private readonly whatIf = signal<Plan | null>(null);
  protected readonly accounts = signal<readonly Account[]>([]);
  protected readonly bills = signal<readonly Bill[]>([]);

  protected readonly dialog = signal<{ debt: Debt | null } | null>(null);
  protected readonly dialogBusy = signal(false);
  protected readonly dialogError = signal<ApiError | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly notice = signal<MessageKey | null>(null);

  /**
   * *"This plan's extra is more than what is left this month"* (`goals.md`, decision 3), or `null`.
   *
   * Goals and the plan are not coupled by arithmetic: the plan's monthly amount stays the
   * household's own figure. The only interaction is this sentence, and the comparison behind it
   * is the server's — the summary reports both figures and whether one exceeds the other.
   */
  protected readonly extraExceedsLeft = signal<Said | null>(null);
  protected readonly savingStrategy = signal(false);
  protected readonly savingAmount = signal(false);
  protected readonly editingAmount = signal(false);

  /**
   * The monthly amount the plan spends — **the one figure every date on this page derives from**.
   *
   * It had nowhere to be entered: the header strip reported it and nothing set it, so a household
   * could read a debt-free date computed from a number they had never been asked for. It is a
   * plain amount beside the figure it changes, and a `PUT` with the plan's version, so two members
   * editing it collide rather than one silently winning.
   *
   * Unsigned `type="text"` with `inputmode="decimal"`, never `type="number"` (ADR-0006).
   */
  readonly amountForm = this.fb.group({
    monthlyAmount: ['', [Validators.required, Validators.pattern(/^\d{1,15}(\.\d{1,4})?$/)]],
  });

  protected readonly canWrite = computed(() => {
    const role = this.auth.membership()?.role;
    return role === 'OWNER' || role === 'MEMBER';
  });

  /**
   * Nothing recorded yet.
   *
   * Read from the debts the household has, not from the plan's `debtCount`: a debt that is paid
   * off or archived is not ranked, and a household that has cleared everything has not gone back
   * to never having had a debt.
   */
  protected readonly empty = computed(() => (this.debts()?.debts.length ?? 0) === 0);

  protected readonly strategies = computed(() =>
    (['AVALANCHE', 'SNOWBALL', 'CUSTOM'] as const).map((strategy) => ({
      strategy,
      name: this.i18n.t(strategyKey(strategy)),
      selected: this.plan()?.strategy === strategy,
    })),
  );

  protected readonly selectedStrategyName = computed(() => {
    const plan = this.plan();
    return plan ? this.i18n.t(strategyKey(plan.strategy)) : '';
  });

  /**
   * How the list says which order it is in.
   *
   * Its own sentence for `CUSTOM`, because the template that works for the named strategies —
   * *"{strategy} order."* — produces *"Your own order order."* for this one. Seen in a snapshot,
   * not in a test.
   */
  protected readonly orderSaid = computed<Said>(() =>
    this.plan()?.strategy === 'CUSTOM'
      ? { key: 'debt.orderCustom' }
      : { key: 'debt.order', params: { strategy: this.selectedStrategyName() } },
  );

  protected readonly tableCaption = computed<Said>(() =>
    this.plan()?.strategy === 'CUSTOM'
      ? { key: 'debt.tableCaptionCustom' }
      : { key: 'debt.tableCaption', params: { strategy: this.selectedStrategyName() } },
  );

  /** *"$645/mo committed · $560 of it strategic"* — the two terms the API defines. */
  protected readonly header = computed<Said | null>(() => {
    const plan = this.plan();
    const group = this.planGroup();
    if (!plan || !group) {
      return null;
    }
    return {
      key: 'debt.header',
      params: {
        committed: this.money(plan.monthlyAmount, plan.currency),
        strategic: this.money(group.surplus, group.currency),
      },
    };
  });

  protected readonly mixedCurrency = computed(() => this.plan()?.mixedCurrency === true);

  /**
   * The comparison's own action — *"Use avalanche"*.
   *
   * Offered only when it is not already the selection, and only to somebody who may write: a
   * control that is always on screen and always refused teaches people to ignore refusals.
   */
  protected readonly offerAvalanche = computed(
    () => this.canWrite() && this.plan() !== null && this.plan()?.strategy !== 'AVALANCHE',
  );

  /**
   * *"Minimums · each counted once in Bills &amp; Income (the car payment is its bill row) —
   * $645"*.
   *
   * **The total of the minimums**, which is what "counted once" is a claim about: all of them are
   * committed, and the rule is that none is committed *twice*. The smaller figure — the part not
   * already carried by a bill row — is the one Bills &amp; Income subtracts, and it is shown
   * there, beside the three terms it belongs with, because the split depends on the month and this
   * page names none.
   */
  protected readonly footer = computed<Said | null>(() => {
    const debts = this.debts();
    if (!debts) {
      return null;
    }
    return {
      key: 'debt.footer',
      params: { minimums: this.money(debts.debtMinimums, debts.currency) },
    };
  });

  /** One section per currency, the household's own first (ADR-0022). */
  protected readonly groups = computed<readonly GroupView[]>(() => {
    const plan = this.plan();
    const debts = this.debts();
    if (!plan || !debts) {
      return [];
    }
    return plan.currencies.map((group) => this.viewOf(plan, group, debts));
  });

  /** Debts outside every ladder — settled, or at zero. Listed, never silently dropped. */
  protected readonly unranked = computed<readonly DebtRowView[]>(() => {
    const debts = this.debts();
    if (!debts) {
      return [];
    }
    return debts.debts
      .filter((debt) => debt.rung === null)
      .map((debt) => this.rowOf(debt, null, null));
  });

  /** *"Adding $50/mo would make you debt-free in March 2029 — 3 months sooner."* */
  protected readonly whatIfSaid = computed<Said | null>(() => {
    const plan = this.plan();
    const alternative = this.whatIf();
    if (!plan || !alternative) {
      return null;
    }
    const now = this.planGroupOf(plan)?.selected.debtFreeMonth ?? null;
    const then = this.planGroupOf(alternative)?.selected.debtFreeMonth ?? null;
    const sooner = monthsBetween(then, now);
    if (then === null || sooner === null) {
      return null;
    }
    const params = {
      step: this.money(WHAT_IF_STEP, plan.currency),
      month: this.i18n.formatMonth(then),
      months: sooner,
    };
    if (sooner <= 0) {
      // True and worth saying: past a certain point the ladder is bounded by the smallest debt's
      // own arithmetic, and claiming "0 months sooner" as a win would be a sales pitch.
      return { key: 'debt.whatIf.noChange', params };
    }
    return { key: sooner === 1 ? 'debt.whatIf.oneMonth' : 'debt.whatIf.months', params };
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
      await this.read();
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
   * The plan, the debts, and the two lists the dialog offers as links.
   *
   * Both of the first two, not one: the plan carries the order, the allocation and both
   * projections, and the debts list carries the balance's provenance, the progress and the
   * counted-once totals. A page that derived either from the other would eventually disagree with
   * the server about one of them.
   */
  private async read(): Promise<void> {
    const [plan, debts, accounts, bills] = await Promise.all([
      firstValueFrom(this.plans.get()),
      firstValueFrom(this.debtsService.list()),
      firstValueFrom(this.accountsService.list()),
      firstValueFrom(this.billsService.list(currentPeriod(), false)),
    ]);
    this.plan.set(plan);
    this.debts.set(debts);
    this.accounts.set(accounts.content);
    this.bills.set(bills.content);
    await this.readWhatIf(plan);
    // Not awaited: one sentence must never hold the plan page's load up.
    void this.readSummary();
  }

  /**
   * This month's summary, for the one sentence that compares the plan's extra with what is left.
   * Failure-tolerant for the reason {@link readWhatIf} is: a missing sentence is a smaller loss
   * than a plan page that will not load.
   */
  private async readSummary(): Promise<void> {
    try {
      const summary = await firstValueFrom(this.summaries.get(currentPeriod()));
      this.extraExceedsLeft.set(
        summary.debtPlanExtraExceedsLeft && summary.debtPlanExtra !== null
          ? {
              key: 'debt.extraExceedsLeft',
              params: {
                extra: this.i18n.formatMoney(summary.debtPlanExtra, summary.currency),
                left: this.i18n.formatMoney(summary.left, summary.currency),
              },
            }
          : null,
      );
    } catch {
      this.extraExceedsLeft.set(null);
    }
  }

  /**
   * The what-if, as its own request.
   *
   * Separate and failure-tolerant: it is a sentence under the ladder, and a plan nobody can read
   * because the hypothetical failed is a worse page than a plan without the hypothetical.
   */
  private async readWhatIf(plan: Plan): Promise<void> {
    this.whatIf.set(null);
    const group = this.planGroupOf(plan);
    if (!group || plan.debtCount === 0 || group.belowMinimums) {
      return;
    }
    // Exact bigint addition of two decimal strings (core/money.ts). The result is a query
    // parameter on a read-only projection, never a figure sent to be stored as truth.
    const more = addAmounts([plan.monthlyAmount, WHAT_IF_STEP]);
    if (more === null) {
      return;
    }
    try {
      this.whatIf.set(await firstValueFrom(this.plans.projection({ monthlyAmount: more })));
    } catch {
      this.whatIf.set(null);
    }
  }

  private async reload(): Promise<void> {
    try {
      await this.read();
    } catch (thrown) {
      this.loadError.set(asApiError(thrown));
    }
  }

  // ------------------------------------------------------------------------------ the strategy

  /**
   * Choosing a strategy, which is a **write**: it changes every projection the household acts on.
   *
   * The server refuses a `VIEWER` and the screen offers them no control, which is two layers
   * rather than one.
   */
  protected async choose(strategy: PayoffStrategy): Promise<void> {
    const plan = this.plan();
    if (!plan || plan.strategy === strategy || this.savingStrategy()) {
      return;
    }
    if (strategy === 'CUSTOM') {
      // Your own order starts from the order on screen. Without sending it, switching would leave
      // every debt unplaced, the ladder would fall back to the avalanche ordering, and the first
      // move would happen against an order nobody had seen.
      await this.saveOrder(plan.version, this.rankedIds(), () =>
        this.notice.set('debt.strategySaved'),
      );
      return;
    }
    this.savingStrategy.set(true);
    this.actionError.set(null);
    this.notice.set(null);
    try {
      await firstValueFrom(this.plans.update({ version: plan.version, strategy }));
      this.notice.set('debt.strategySaved');
      await this.reload();
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
    } finally {
      this.savingStrategy.set(false);
    }
  }

  // --------------------------------------------------------------------------------- the debts

  /** Opens the amount for editing, filled with what the plan currently says. */
  protected editAmount(): void {
    const plan = this.plan();
    if (!plan) {
      return;
    }
    this.amountForm.reset({ monthlyAmount: trimAmount(plan.monthlyAmount) }, { emitEvent: false });
    this.actionError.set(null);
    this.notice.set(null);
    this.editingAmount.set(true);
  }

  protected cancelAmount(): void {
    this.editingAmount.set(false);
  }

  /**
   * Saves the monthly amount.
   *
   * The figure is a string from the first keystroke to the request body (ADR-0006) — nothing here
   * parses it, and every date that comes back is the server's.
   */
  protected async saveAmount(): Promise<void> {
    const plan = this.plan();
    if (!plan || this.savingAmount()) {
      return;
    }
    if (this.amountForm.invalid) {
      this.amountForm.markAllAsTouched();
      return;
    }
    const monthlyAmount = this.amountForm.getRawValue().monthlyAmount.trim();
    if (sameAmount(monthlyAmount, plan.monthlyAmount)) {
      this.editingAmount.set(false);
      return;
    }
    this.savingAmount.set(true);
    this.actionError.set(null);
    this.notice.set(null);
    try {
      await firstValueFrom(this.plans.update({ version: plan.version, monthlyAmount }));
      this.editingAmount.set(false);
      this.notice.set('debt.amountSaved');
      await this.reload();
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
    } finally {
      this.savingAmount.set(false);
    }
  }

  /**
   * Whether the household is arranging its own order, which is when the moves appear.
   *
   * Only under `CUSTOM`: under avalanche or snowball the order is the strategy's, and offering to
   * move a rung that the next read would put back is a control that does nothing.
   */
  protected readonly arranging = computed(
    () => this.canWrite() && this.plan()?.strategy === 'CUSTOM',
  );

  /** What was just moved and where it landed, for the live region. */
  protected readonly announcement = signal('');

  /**
   * Every ranked debt, rung 1 first, across every currency.
   *
   * **The whole order, not one group's.** `PUT /api/debt-plan` reads `customOrder` as the
   * complete instruction and clears the position of any debt the list leaves out, so sending one
   * currency's ladder would silently unplace the other's.
   */
  private rankedIds(): string[] {
    return this.groups().flatMap((group) => group.rows.map((row) => row.id));
  }

  /**
   * Moves a rung one place, and asks the server for the plan that follows.
   *
   * The new order is applied by the server and read back, never spliced into the plan here: every
   * date, every allocation and the debt-free month depend on the order, and only the server
   * computes them. A local reorder would show the new positions beside the old dates.
   */
  protected async move(currency: string, id: string, by: -1 | 1): Promise<void> {
    const plan = this.plan();
    if (!plan || !this.arranging() || this.savingStrategy()) {
      return;
    }
    const group = this.groups().find((one) => one.currency === currency);
    const within = group?.rows.map((row) => row.id) ?? [];
    const at = within.indexOf(id);
    const to = at + by;
    if (at === -1 || to < 0 || to >= within.length) {
      return;
    }
    const moved = [...within];
    [moved[at], moved[to]] = [moved[to], moved[at]];
    // Rebuilt across every group, with this group's ladder replaced by the moved one.
    const order = this.groups().flatMap((one) =>
      one.currency === currency ? moved : one.rows.map((row) => row.id),
    );

    const name = group?.rows[at]?.name ?? '';
    await this.saveOrder(plan.version, order, () =>
      this.announcement.set(this.i18n.t('debt.movedTo', { name, rung: to + 1, of: within.length })),
    );
  }

  /**
   * One `PUT` carrying the strategy, the whole order and the version.
   *
   * `409 stale-version` is reported and the page re-read, exactly as the strategy buttons do: two
   * members arranging the same ladder collide rather than one silently winning.
   */
  private async saveOrder(version: number, order: string[], said: () => void): Promise<void> {
    this.savingStrategy.set(true);
    this.actionError.set(null);
    this.notice.set(null);
    this.announcement.set('');
    try {
      await firstValueFrom(this.plans.update({ version, strategy: 'CUSTOM', customOrder: order }));
      await this.reload();
      said();
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
      await this.reload();
    } finally {
      this.savingStrategy.set(false);
    }
  }

  protected openAdd(): void {
    this.dialogError.set(null);
    this.notice.set(null);
    this.dialog.set({ debt: null });
  }

  protected openEdit(debt: Debt): void {
    this.dialogError.set(null);
    this.notice.set(null);
    this.dialog.set({ debt });
  }

  protected closeDialog(): void {
    this.dialog.set(null);
    this.dialogBusy.set(false);
    this.dialogError.set(null);
  }

  protected async submit(form: DebtFormValue): Promise<void> {
    const target = this.dialog();
    if (!target) {
      return;
    }
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      if (target.debt === null) {
        await firstValueFrom(this.debtsService.create(buildDebtCreate(form)));
        this.notice.set('debt.created');
      } else {
        const patch = buildDebtPatch(target.debt, form);
        if (patchIsEmpty(patch)) {
          this.closeDialog();
          return;
        }
        await firstValueFrom(this.debtsService.update(target.debt.id, patch));
        this.notice.set(
          // Naming the release is the interaction: "change a solved field and it becomes yours",
          // and its mirror — hand one back and the screen says it is solved from now on.
          patch.apr === null ? 'debt.rateReleased' : 'debt.updated',
        );
      }
      this.dialog.set(null);
      await this.reload();
    } catch (thrown) {
      this.dialogError.set(asApiError(thrown));
    } finally {
      this.dialogBusy.set(false);
    }
  }

  // ------------------------------------------------------------------------------ what to show

  private planGroup(): CurrencyGroup | null {
    const plan = this.plan();
    return plan ? this.planGroupOf(plan) : null;
  }

  private planGroupOf(plan: Plan): CurrencyGroup | null {
    return plan.currencies.find((group) => group.planCurrency) ?? plan.currencies[0] ?? null;
  }

  /**
   * One currency's section.
   *
   * The rows are the **debts** in rung order, each joined to its rung for the allocation figures —
   * not the rungs joined to debts. The debts list is what carries the balance's provenance, the
   * progress and the staleness, and a row assembled the other way round would have to invent those
   * for a debt the plan ranked and the list did not return.
   */
  private viewOf(plan: Plan, group: CurrencyGroup, debts: DebtList): GroupView {
    const rungs = new Map(group.selected.rungs.map((rung) => [rung.debtId, rung]));
    const rows = debts.debts
      .filter((debt) => debt.currency === group.currency && debt.rung !== null)
      .sort((left, right) => (left.rung ?? 0) - (right.rung ?? 0))
      .map((debt, at, all) =>
        this.rowOf(debt, rungs.get(debt.id) ?? null, group.selected.reason, {
          at,
          of: all.length,
        }),
      );
    const series = this.seriesOf(plan, group);
    return {
      currency: group.currency,
      planCurrency: group.planCurrency,
      headline: this.headlineOf(group),
      assumption: this.assumptionOf(group),
      series,
      // A ladder with no projection has no series. Saying why beats an axis around an empty box.
      unavailable: series.some((line) => line.points.length >= 2)
        ? ''
        : this.i18n.t('debt.chart.noLine', {
            reason: this.i18n.t(projectionReasonKey(group.selected.reason ?? '')),
          }),
      // Supplied whenever there is more than one line; the chart uses it only if they turn out to
      // coincide. Under CUSTOM there are three, and choosing your own order from the avalanche
      // order makes all three the same line.
      coincidentLabel:
        series.length > 1
          ? this.i18n.t('debt.chart.coincide', {
              lines: series.map((line) => line.name).join(' · '),
            })
          : '',
      marker: this.markerOf(group.selected),
      cards: this.cardsOf(plan, group),
      selected: this.selectedSaid(plan, group),
      comparison: this.comparisonOf(group),
      belowMinimums: group.belowMinimums
        ? {
            key: 'debt.belowMinimums',
            params: {
              amount: this.money(group.monthlyAmount, group.currency),
              minimums: this.money(group.minimums, group.currency),
            },
          }
        : null,
      rows,
    };
  }

  /** *"$26,480 → $0 · debt-free June 2029 on the current plan"*. */
  private headlineOf(group: CurrencyGroup): Said {
    const owed = this.money(group.owed, group.currency);
    const month = group.selected.debtFreeMonth;
    if (month === null) {
      return {
        key: 'debt.headline.noDate',
        params: {
          owed,
          reason: this.i18n.t(projectionReasonKey(group.selected.reason ?? '')),
        },
      };
    }
    return { key: 'debt.headline', params: { owed, month: this.i18n.formatMonth(month) } };
  }

  /**
   * *"Both lines assume $560/mo"*, plus the stride when the line was thinned.
   *
   * The stride is said out loud rather than assumed away: a client that took one point per month
   * would mislabel the axis of exactly the long plan the server's cap exists for.
   */
  private assumptionOf(group: CurrencyGroup): string {
    const assumes = this.i18n.t('debt.chart.assumes', {
      amount: this.money(group.monthlyAmount, group.currency),
    });
    const stride = Math.max(group.selected.seriesMonthStride, group.avalanche.seriesMonthStride);
    return stride > 1
      ? `${assumes} ${this.i18n.t('debt.chart.stride', { months: stride })}`
      : assumes;
  }

  /**
   * The lines. **Both named strategies, always** — and the household's own order as a third line
   * when it has one, because a `CUSTOM` plan whose chart showed only the two it did not pick would
   * be a chart of somebody else's plan.
   */
  private seriesOf(plan: Plan, group: CurrencyGroup): readonly ChartSeries[] {
    const lines: ChartSeries[] = [];
    if (plan.strategy === 'CUSTOM') {
      lines.push(this.lineOf(group, group.selected, 'selected', 'CUSTOM'));
      lines.push(this.lineOf(group, group.avalanche, 'compared', 'AVALANCHE'));
      lines.push(this.lineOf(group, group.snowball, 'compared', 'SNOWBALL'));
      return lines;
    }
    const chosen = plan.strategy === 'SNOWBALL' ? group.snowball : group.avalanche;
    const other = plan.strategy === 'SNOWBALL' ? group.avalanche : group.snowball;
    lines.push(this.lineOf(group, chosen, 'selected', chosen.strategy));
    lines.push(this.lineOf(group, other, 'compared', other.strategy));
    return lines;
  }

  private lineOf(
    group: CurrencyGroup,
    projection: StrategyProjection,
    role: 'selected' | 'compared',
    strategy: PayoffStrategy,
  ): ChartSeries {
    const name = this.i18n.t(strategyKey(strategy));
    const points = projection.series.map((point) => ({
      month: point.month,
      label: this.i18n.formatMonth(point.month),
      amount: point.balance,
    }));
    const first = points[0];
    const last = points[points.length - 1];
    const said =
      first && last
        ? this.i18n.t('debt.chart.said', {
            name,
            from: this.money(first.amount, group.currency),
            fromMonth: first.label,
            to: this.money(last.amount, group.currency),
            toMonth: last.label,
          })
        : this.i18n.t('debt.chart.saidNothing', {
            name,
            reason: this.i18n.t(projectionReasonKey(projection.reason ?? '')),
          });
    return { id: `${group.currency}-${strategy}`, name, said, points, role };
  }

  private markerOf(projection: StrategyProjection): ChartMarker | null {
    const month = projection.debtFreeMonth;
    return month === null
      ? null
      : {
          month,
          label: this.i18n.t('debt.chart.marker', { month: this.i18n.formatMonth(month) }),
        };
  }

  private cardsOf(plan: Plan, group: CurrencyGroup): readonly StrategyCardView[] {
    return [
      this.cardOf(plan, group.avalanche, 'AVALANCHE', 'debt.rationale.AVALANCHE'),
      this.cardOf(plan, group.snowball, 'SNOWBALL', 'debt.rationale.SNOWBALL'),
    ];
  }

  private cardOf(
    plan: Plan,
    projection: StrategyProjection,
    strategy: PayoffStrategy,
    rationale: MessageKey,
  ): StrategyCardView {
    const month = projection.debtFreeMonth;
    const win = projection.firstWinMonth;
    return {
      strategy,
      name: this.i18n.t(strategyKey(strategy)),
      rationale,
      selected: plan.strategy === strategy,
      debtFree:
        month === null
          ? {
              key: 'debt.card.noDate',
              params: { reason: this.i18n.t(projectionReasonKey(projection.reason ?? '')) },
            }
          : { key: 'debt.card.debtFree', params: { month: this.i18n.formatMonth(month) } },
      firstWin:
        win === null
          ? null
          : { key: 'debt.card.firstWin', params: { month: this.i18n.formatMonth(win) } },
      totalInterest: projection.totalInterest,
    };
  }

  /**
   * What the order in force actually costs, **when it is the household's own**.
   *
   * The two cards are always avalanche and snowball, whichever strategy is selected — that is the
   * product requirement. Under `CUSTOM` neither of them is the plan, so without this the page
   * shows two totals the household is not paying and never names the one they are: rearranging
   * the designs' own ladder moves the interest from $2,880.41 to $4,568.81 and both cards carry
   * on reading as before.
   *
   * Omitted for `AVALANCHE` and `SNOWBALL`, where the matching card already says it.
   */
  private selectedSaid(plan: Plan, group: CurrencyGroup): Said | null {
    if (plan.strategy !== 'CUSTOM') {
      return null;
    }
    const month = group.selected.debtFreeMonth;
    const interest = group.selected.totalInterest;
    if (month === null || interest === null) {
      return {
        key: 'debt.yourOrder.noDate',
        params: { reason: this.i18n.t(projectionReasonKey(group.selected.reason ?? '')) },
      };
    }
    return {
      key: 'debt.yourOrder',
      params: {
        month: this.i18n.formatMonth(month),
        amount: this.money(interest, group.currency),
      },
    };
  }

  /**
   * The sentence that compares them — *"either is a good answer"*.
   *
   * It states which costs less interest and which clears the first debt sooner, and it **invents
   * no difference figure**: computing "$780 less" in the browser would be arithmetic on money, and
   * both totals are already on the cards for anybody who wants to subtract them. The ordering is
   * exact (`core/money.ts` compares `bigint` minor units) rather than a parse.
   */
  private comparisonOf(group: CurrencyGroup): Said | null {
    const avalanche = group.avalanche;
    const snowball = group.snowball;
    if (avalanche.totalInterest === null || snowball.totalInterest === null) {
      return null;
    }
    const cheaper = compareAmounts(avalanche.totalInterest, snowball.totalInterest);
    const sooner = monthsBetween(snowball.firstWinMonth, avalanche.firstWinMonth);
    if (cheaper === null) {
      return null;
    }
    if (cheaper === 0 && (sooner === null || sooner === 0)) {
      return { key: 'debt.comparison.same' };
    }
    const params: MessageParams = {
      cheaper: this.i18n.t(strategyKey(cheaper <= 0 ? 'AVALANCHE' : 'SNOWBALL')),
      sooner: this.i18n.t(strategyKey('SNOWBALL')),
      months: sooner === null ? 0 : Math.abs(sooner),
    };
    if (sooner === null || sooner <= 0) {
      return { key: 'debt.comparison.cheaperOnly', params };
    }
    return { key: 'debt.comparison.both', params };
  }

  /**
   * One debt as the plan sees it.
   *
   * **`rung` is where the dates come from, not `debt`.** `DebtResponse.payoffMonth` is this debt's
   * projection *on its own minimum, alone* — the comparison line, not the plan — and on this
   * household's fixtures it reads 2034 for a student loan the plan clears in 2029. Putting that in
   * a column headed "Clears", on a page headlined "debt-free April 2029", tells somebody their
   * plan does something it does not do. The at-minimum date is a real figure and belongs on the
   * detail page's chart, under a label that says what it is.
   */
  private rowOf(
    debt: Debt,
    rung: Rung | null,
    planReason: string | null,
    place: { readonly at: number; readonly of: number } = { at: 0, of: 1 },
  ): DebtRowView {
    return {
      id: debt.id,
      name: debt.name,
      canMoveUp: place.at > 0,
      canMoveDown: place.at < place.of - 1,
      currency: debt.currency,
      balance: debt.balance,
      naming: debt.servicer
        ? {
            key: 'debt.naming',
            params: { kind: this.i18n.t(debtKindKey(debt.kind)), servicer: debt.servicer },
          }
        : { key: debtKindKey(debt.kind) },
      rung: debt.rung,
      rate: this.rateOf(debt.apr, debt.aprDerivable),
      // Only beside a rate there actually is. "Rate not available — Solved" is a contradiction:
      // the tag means "this figure was worked out", and there is no figure.
      rateSolved: debt.aprSource === 'SOLVED' && debt.apr !== null,
      clears: rung
        ? // The plan's own answer for this debt, with the plan's reason when there is no date.
          this.clearsOf(rung.payoffMonth, planReason ?? debt.projectionReason)
        : { key: 'debt.clears.unknown' },
      paying: rung
        ? this.payingOf(rung.minimum, rung.extra, rung.monthlyAmount, debt.currency, rung.rung)
        : {
            key: 'debt.paying.none',
            params: { amount: this.money(debt.minimumPayment, debt.currency) },
          },
      progress: this.progressOf(debt),
      balanceNote: this.balanceNoteOf(debt),
      balanceEstimated: debt.balanceSource === 'ESTIMATED' || debt.balanceStale,
      debt,
    };
  }

  /** A rate that was neither entered nor derived says so. **It never prints 0%.** */
  private rateOf(apr: string | null, derivable: boolean): Said {
    if (apr !== null) {
      return { key: 'debt.rate.value', params: { rate: this.i18n.formatRate(apr) } };
    }
    return { key: derivable ? 'debt.rate.notEntered' : 'debt.rate.unavailable' };
  }

  /**
   * When a debt clears, or **why it does not** — which is a fact about the terms, not an error.
   *
   * `never-clears` is the common real case: a card's real minimum is a percentage of the balance,
   * so a household entering last month's flat figure hits it legitimately. The cell says so in a
   * few words; the detail page says it in the arithmetic's own terms.
   */
  private clearsOf(payoffMonth: string | null, reason: string | null): Said {
    if (payoffMonth !== null) {
      return { key: 'debt.clears.on', params: { month: this.i18n.formatMonth(payoffMonth) } };
    }
    if (reason === 'never-clears') {
      return { key: 'debt.clears.never' };
    }
    return reason === 'plan-below-minimums'
      ? { key: 'debt.clears.noPlan' }
      : { key: 'debt.clears.unknown' };
  }

  /**
   * What a rung is paying, in the allocation rule's own words.
   *
   * **`extra` is negative** for a debt that owes less than its own minimum and clears in month one
   * (ADR-0036). `minimum + extra` is still exactly what the plan allocates, so the figure is true
   * — but "extra &minus;$65" is not a sentence anybody can act on, so that case gets its own.
   */
  private payingOf(
    minimum: string,
    extra: string,
    allocated: string,
    currency: string,
    rung: number,
  ): Said {
    const sign = compareAmounts(extra, '0');
    if (sign !== null && sign < 0) {
      return {
        key: 'debt.paying.clearsIt',
        params: { amount: this.money(allocated, currency) },
      };
    }
    if (sign === 0) {
      return {
        key: rung === 1 ? 'debt.paying.minimumOnly' : 'debt.paying.minimumUntil',
        params: { amount: this.money(minimum, currency) },
      };
    }
    return {
      key: 'debt.paying.minimumPlusExtra',
      params: {
        minimum: this.money(minimum, currency),
        extra: this.money(extra, currency),
      },
    };
  }

  /** *"$4,470 paid off · 42% of $10,650"* — **absent** when there is no original balance. */
  private progressOf(debt: Debt): Said | null {
    if (
      debt.progressPercent === null ||
      debt.paidOffSoFar === null ||
      debt.originalBalance === null
    ) {
      return null;
    }
    return {
      key: 'debt.progress',
      params: {
        paid: this.money(debt.paidOffSoFar, debt.currency),
        percent: this.i18n.formatRate(debt.progressPercent),
        original: this.money(debt.originalBalance, debt.currency),
      },
    };
  }

  /**
   * What kind of figure the balance is. **No two sources render identically.**
   *
   * A stale estimate is its own answer: past twelve months with no confirmation the screen asks
   * for a statement figure rather than showing a year of extrapolation as though it were known.
   */
  private balanceNoteOf(debt: Debt): Said {
    if (debt.balanceStale) {
      return {
        key: 'debt.balance.stale',
        params: { date: this.i18n.formatDate(debt.anchorDate) },
      };
    }
    if (debt.balanceSource === 'ESTIMATED') {
      return { key: 'debt.balance.estimated', params: { months: debt.estimatedMonths } };
    }
    return {
      key: balanceSourceKey(debt.balanceSource),
      params: { date: this.i18n.formatDate(debt.balanceAsOf) },
    };
  }

  /** A figure formatted for the viewer, from the string the server sent (ADR-0006). */
  private money(amount: string | null, currency: string): string {
    return this.i18n.formatMoney(amount, currency);
  }
}
