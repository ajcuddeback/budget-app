import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { MoneyPipe } from '../../../core/i18n/format.pipes';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MessageKey, MessageParams } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { compareAmounts, sameAmount } from '../../../core/money';
import { currentPeriod, shiftPeriod } from '../../../core/period';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { ChartSeries, LineChartComponent } from '../../../shared/components/line-chart.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import {
  Direction,
  SegmentTone,
  VISIBLE_CATEGORIES,
  csvFileName,
  directionOf,
  isUnderOnePercent,
  lastCompleteMonth,
  lastMonths,
  segmentTone,
} from '../data/reports-data';
import {
  CategorySpending,
  CurrencyIncomeAndSpending,
  CurrencySpending,
  IncomeAndSpending,
  MonthFigures,
  RANGES,
  RangeMonths,
  ReportName,
  SpendingByCategory,
} from '../data/reports.models';
import { ReportsService } from '../data/reports.service';

/** A translated sentence waiting for its parameters — the template does no logic (style guide). */
interface Said {
  readonly key: MessageKey;
  readonly params?: MessageParams;
}

/** One row of "Where {month} went", with everything the template shows already decided. */
interface CategoryRowView {
  readonly id: string;
  readonly name: string;
  readonly archived: boolean;
  readonly spending: string;
  readonly change: Said | null;
  readonly direction: Direction;
  readonly tone: SegmentTone;
  /** `null` for a non-positive row: it has no slice of the bar. */
  readonly share: string | null;
  readonly children: readonly CategoryRowView[];
}

/** One row of the "Show the figures" table — exactly one CSV record. */
interface MonthRowView {
  readonly period: string;
  readonly label: string;
  readonly figures: MonthFigures;
}

/**
 * Insights: what actually happened in the ledger (docs/features/reporting.md).
 *
 * Two cards — income against spending by month, and where one month's spending went — each read
 * from its own report, each computed by the server on request. **This page does no money
 * arithmetic**: every figure, every average, every change and every share is the server's string,
 * formatted with `Intl`. What the page decides is only *which* month to ask about, from the
 * device's own date, and how to say what came back.
 *
 * A `VIEWER` sees exactly what an `OWNER` sees. There is no write control on this page to hide.
 */
@Component({
  selector: 'app-insights-page',
  imports: [
    ErrorAlertComponent,
    LineChartComponent,
    MoneyPipe,
    NoHouseholdComponent,
    RouterLink,
    TranslatePipe,
  ],
  templateUrl: './insights.page.html',
  styleUrl: './insights.page.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class InsightsPage {
  private readonly reports = inject(ReportsService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  protected readonly ranges = RANGES;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);
  /** A failure after the page has loaded: a range, a month or a download that did not come back. */
  protected readonly actionError = signal<ApiError | null>(null);

  protected readonly range = signal<RangeMonths>(12);
  protected readonly month = signal(lastCompleteMonth());
  protected readonly selectedCurrency = signal<string | null>(null);
  protected readonly showAll = signal(false);
  protected readonly expanded = signal<ReadonlySet<string>>(new Set());
  protected readonly downloading = signal<ReportName | null>(null);
  protected readonly announcement = signal('');

  private readonly income = signal<IncomeAndSpending | null>(null);
  private readonly spending = signal<SpendingByCategory | null>(null);

  /** The currencies to choose between: report 1's, base first, in the server's order. */
  protected readonly currencies = computed(
    () => this.income()?.currencies.map((c) => c.currency) ?? [],
  );

  protected readonly currency = computed(
    () => this.selectedCurrency() ?? this.income()?.baseCurrency ?? '',
  );

  private readonly incomeFigures = computed<CurrencyIncomeAndSpending | null>(
    () => this.income()?.currencies.find((c) => c.currency === this.currency()) ?? null,
  );

  private readonly spendingFigures = computed<CurrencySpending | null>(
    () => this.spending()?.currencies.find((c) => c.currency === this.currency()) ?? null,
  );

  protected readonly rangeLabel = computed(() => {
    const report = this.income();
    return report ? this.i18n.formatMonthRange(report.from, report.to) : '';
  });

  /** Nothing recorded in the range: a sentence, not a flat line and a zero average. */
  protected readonly empty = computed(() => (this.incomeFigures()?.transactionCount ?? 0) === 0);

  protected readonly emptySaid = computed<Said>(() => {
    const report = this.income();
    return {
      key: 'insights.empty',
      params: {
        from: this.i18n.formatMonth(report?.from),
        to: this.i18n.formatMonth(report?.to),
      },
    };
  });

  /** "Avg kept £906/mo" — the phone's short insight. `null` when no month is complete. */
  protected readonly keptShort = computed<Said | null>(() => {
    const figures = this.incomeFigures();
    if (!figures?.averageKept || this.empty()) {
      return null;
    }
    return {
      key: 'insights.keptShort',
      params: { amount: this.i18n.formatMoney(figures.averageKept, figures.currency) },
    };
  });

  /**
   * The desktop's long insight. It says how many months it covers whenever one month in the
   * range is incomplete, because "an average over 11 months" and "over 12" otherwise look alike.
   */
  protected readonly keptLong = computed((): Said | null => {
    const figures = this.incomeFigures();
    if (!figures?.averageKept || !figures.bestMonth) {
      return null;
    }
    const money = (amount: string) => this.i18n.formatMoney(amount, figures.currency);
    const month = this.i18n.formatMonth(figures.bestMonth.period);
    if (figures.completeMonths === 1) {
      return {
        key: 'insights.keptLongPartialOne',
        params: { amount: money(figures.averageKept), month },
      };
    }
    const partial = figures.completeMonths < figures.months.length;
    return {
      key: partial ? 'insights.keptLongPartial' : 'insights.keptLong',
      params: {
        amount: money(figures.averageKept),
        month,
        best: money(figures.bestMonth.kept),
        count: figures.completeMonths,
      },
    };
  });

  /** Income and spending as two peer lines: different tones, and different weight and dash. */
  protected readonly series = computed<readonly ChartSeries[]>(() => {
    const figures = this.incomeFigures();
    if (!figures) {
      return [];
    }
    const line = (
      id: 'income' | 'spending',
      name: MessageKey,
      said: MessageKey,
      role: ChartSeries['role'],
      tone: ChartSeries['tone'],
    ): ChartSeries => {
      const points = figures.months.map((month) => ({
        month: month.period,
        label: this.i18n.formatMonthShort(month.period),
        amount: month[id],
      }));
      const first = figures.months[0];
      const last = figures.months[figures.months.length - 1];
      return {
        id,
        name: this.i18n.t(name),
        role,
        tone,
        points,
        said: this.i18n.t(said, {
          first: this.i18n.formatMoney(first[id], figures.currency),
          firstMonth: this.i18n.formatMonth(first.period),
          last: this.i18n.formatMoney(last[id], figures.currency),
          lastMonth: this.i18n.formatMonth(last.period),
        }),
      };
    };
    return [
      line('income', 'insights.legend.income', 'insights.said.income', 'selected', 'accent-2'),
      line('spending', 'insights.legend.spending', 'insights.said.spending', 'compared', 'accent'),
    ];
  });

  /** True when a month's spending is negative: the chart holds it at zero, so the page says so. */
  protected readonly hasNegativeSpending = computed(() =>
    (this.incomeFigures()?.months ?? []).some(
      (month) => (compareAmounts(month.spending, '0') ?? 0) < 0,
    ),
  );

  protected readonly monthRows = computed<readonly MonthRowView[]>(() =>
    (this.incomeFigures()?.months ?? []).map((figures) => {
      const name = this.i18n.formatMonth(figures.period);
      return {
        period: figures.period,
        label: figures.complete ? name : this.i18n.t('insights.soFar', { month: name }),
        figures,
      };
    }),
  );

  protected readonly tableCaption = computed<Said>(() => ({
    key: 'insights.figures.caption',
    params: { range: this.rangeLabel(), currency: this.currency() },
  }));

  /** The "Needs a category" line under the chart, from the server's own totals. */
  protected readonly uncategorised = computed((): Said | null => {
    const figures = this.incomeFigures();
    if (!figures || figures.uncategorisedCount === 0) {
      return null;
    }
    const money = (amount: string) => this.i18n.formatMoney(amount, figures.currency);
    const out = figures.totals.uncategorisedOut;
    const into = figures.totals.uncategorisedIn;
    const plural = figures.uncategorisedCount === 1 ? 'one' : 'many';
    const hasOut = !sameAmount(out, '0');
    const hasIn = !sameAmount(into, '0');
    if (hasOut && hasIn) {
      return {
        key: `insights.uncategorised.${plural}.both` as MessageKey,
        params: { count: figures.uncategorisedCount, out: money(out), in: money(into) },
      };
    }
    return {
      key: `insights.uncategorised.${plural}.single` as MessageKey,
      params: { count: figures.uncategorisedCount, amount: money(hasIn ? into : out) },
    };
  });

  // --------------------------------------------------------------- where {month} went

  protected readonly whereHeading = computed<Said>(() => ({
    key:
      this.spending()?.complete === false
        ? 'insights.where.headingSoFar'
        : 'insights.where.heading',
    params: { month: this.i18n.formatMonth(this.month()) },
  }));

  /** The next month exists only up to the current one: the future has nothing to show yet. */
  protected readonly canGoForward = computed(() => this.month() < currentPeriod());

  protected readonly whereTotal = computed<Said | null>(() => {
    const figures = this.spendingFigures();
    const report = this.spending();
    if (!figures || !report || figures.categories.length === 0) {
      return null;
    }
    return {
      key: 'insights.where.total',
      params: {
        amount: this.i18n.formatMoney(figures.spending, figures.currency),
        compared: this.i18n.formatMoney(figures.comparedSpending, figures.currency),
        comparedMonth: this.i18n.formatMonth(report.comparedFrom),
      },
    };
  });

  protected readonly whereNothing = computed<Said>(() => ({
    key: 'insights.where.nothing',
    params: { month: this.i18n.formatMonth(this.month()) },
  }));

  protected readonly whereListLabel = computed(() =>
    this.i18n.t('insights.where.list', { month: this.i18n.formatMonth(this.month()) }),
  );

  protected readonly categoryRows = computed<readonly CategoryRowView[]>(() =>
    (this.spendingFigures()?.categories ?? []).map((row, index) =>
      this.rowView(row, segmentTone(index)),
    ),
  );

  protected readonly visibleRows = computed(() =>
    this.showAll() ? this.categoryRows() : this.categoryRows().slice(0, VISIBLE_CATEGORIES),
  );

  protected readonly hiddenCount = computed(() =>
    Math.max(0, this.categoryRows().length - VISIBLE_CATEGORIES),
  );

  /** The proportion bar: every row with a share, in the rows' order. Decorative. */
  protected readonly segments = computed(() =>
    this.categoryRows().filter(
      (row): row is CategoryRowView & { share: string } => row.share !== null,
    ),
  );

  protected readonly whereUncategorised = computed<Said | null>(() => {
    const figures = this.spendingFigures();
    if (!figures || figures.uncategorised.count === 0) {
      return null;
    }
    return {
      key:
        figures.uncategorised.count === 1
          ? 'insights.where.uncategorised.one'
          : 'insights.where.uncategorised.many',
      params: {
        amount: this.i18n.formatMoney(figures.uncategorised.spending, figures.currency),
        count: figures.uncategorised.count,
      },
    };
  });

  protected readonly whereEmpty = computed(() => {
    const figures = this.spendingFigures();
    return !figures || (figures.categories.length === 0 && figures.uncategorised.count === 0);
  });

  protected readonly incomeDownloadKey = computed<MessageKey>(() =>
    this.downloading() === 'income-and-spending' ? 'insights.downloading' : 'insights.download',
  );

  protected readonly whereDownloadKey = computed<MessageKey>(() =>
    this.downloading() === 'spending-by-category' ? 'insights.downloading' : 'insights.download',
  );

  protected readonly showAllSaid = computed<Said>(() =>
    this.showAll()
      ? { key: 'insights.where.showFewer' }
      : { key: 'insights.where.showAll', params: { count: this.categoryRows().length } },
  );

  protected readonly downloadWhereLabel = computed(() =>
    this.i18n.t('insights.downloadWhere', { month: this.i18n.formatMonth(this.month()) }),
  );

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
      const { from, to } = lastMonths(this.range());
      const [income, spending] = await Promise.all([
        firstValueFrom(this.reports.incomeAndSpending(from, to)),
        firstValueFrom(this.reports.spendingByCategory(this.month(), this.month())),
      ]);
      this.income.set(income);
      this.spending.set(spending);
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

  /** A chip saves nothing: it asks report 1 again with a different `from`. */
  protected async chooseRange(months: RangeMonths): Promise<void> {
    if (months === this.range()) {
      return;
    }
    this.range.set(months);
    this.actionError.set(null);
    const { from, to } = lastMonths(months);
    try {
      this.income.set(await firstValueFrom(this.reports.incomeAndSpending(from, to)));
      this.announcement.set(this.i18n.t('insights.status.range', { range: this.rangeLabel() }));
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
    }
  }

  protected chooseCurrency(currency: string): void {
    this.selectedCurrency.set(currency);
  }

  protected async step(by: number): Promise<void> {
    const month = shiftPeriod(this.month(), by);
    if (by > 0 && month > currentPeriod()) {
      return;
    }
    this.actionError.set(null);
    try {
      const report = await firstValueFrom(this.reports.spendingByCategory(month, month));
      this.month.set(month);
      this.spending.set(report);
      this.showAll.set(false);
      this.expanded.set(new Set());
      this.announcement.set(
        this.i18n.t('insights.status.month', { month: this.i18n.formatMonth(month) }),
      );
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
    }
  }

  protected toggleShowAll(): void {
    this.showAll.update((shown) => !shown);
  }

  protected toggleChildren(id: string): void {
    this.expanded.update((open) => {
      const next = new Set(open);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  protected isExpanded(id: string): boolean {
    return this.expanded().has(id);
  }

  protected childrenLabel(row: CategoryRowView): string {
    return this.i18n.t(
      this.isExpanded(row.id) ? 'insights.where.hideChildren' : 'insights.where.showChildren',
      { name: row.name },
    );
  }

  protected rangeName(months: RangeMonths): string {
    return this.i18n.t(`insights.range.${months}.name` as MessageKey);
  }

  protected rangeShort(months: RangeMonths): string {
    return this.i18n.t(`insights.range.${months}` as MessageKey);
  }

  /**
   * The file, asked for with `Accept: text/csv` and handed to the browser as a download. Built
   * from the server's bytes untouched: the page neither reads nor rewrites them.
   */
  protected async download(report: ReportName): Promise<void> {
    const period =
      report === 'income-and-spending'
        ? { from: this.income()?.from ?? '', to: this.income()?.to ?? '' }
        : { from: this.month(), to: this.month() };
    if (!period.from || !period.to || this.downloading() !== null) {
      return;
    }
    this.downloading.set(report);
    this.actionError.set(null);
    try {
      const file = await firstValueFrom(this.reports.csv(report, period.from, period.to));
      const url = URL.createObjectURL(file);
      const link = document.createElement('a');
      link.href = url;
      link.download = csvFileName(report, period.from, period.to);
      link.click();
      URL.revokeObjectURL(url);
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
    } finally {
      this.downloading.set(null);
    }
  }

  private rowView(row: CategorySpending, tone: SegmentTone): CategoryRowView {
    const direction = directionOf(row.change, row.spending);
    return {
      id: row.categoryId,
      name: row.name,
      archived: row.archived,
      spending: row.spending,
      direction,
      change: this.changeSaid(direction, row.change),
      tone,
      share: row.share,
      children: row.children.map((child) => this.rowView(child, tone)),
    };
  }

  /** "up 16%", "down 22%", "flat", "new" — the direction in words as well as colour. */
  private changeSaid(direction: Direction, change: string | null): Said | null {
    switch (direction) {
      case 'flat':
        return { key: 'insights.change.flat' };
      case 'new':
        return { key: 'insights.change.new' };
      case 'none':
        return null;
      case 'up':
      case 'down':
        if (change !== null && isUnderOnePercent(change)) {
          return {
            key: direction === 'up' ? 'insights.change.upSmall' : 'insights.change.downSmall',
          };
        }
        return {
          key: direction === 'up' ? 'insights.change.up' : 'insights.change.down',
          params: { percent: this.i18n.formatPercent(change) },
        };
    }
  }
}
