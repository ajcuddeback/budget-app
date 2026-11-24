import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { amountFraction, compareAmounts, isAmount } from '../../core/money';

/**
 * One point on a line: what was owed at the start of that month.
 *
 * `label` arrives **already formatted** for the viewer's locale ("Apr 2027") because a
 * presentational component has no business holding a locale, and `month` is the raw `YYYY-MM` the
 * server sent, which is what a marker is matched against.
 */
export interface ChartPoint {
  readonly month: string;
  readonly label: string;
  /** A decimal string, as the API sent it. Never parsed into a figure — see the class notes. */
  readonly amount: string;
}

/** One line. */
export interface ChartSeries {
  readonly id: string;
  /** Already translated by the page that owns the chart. */
  readonly name: string;
  /**
   * What this line's two ends say, in the household's own words — *"Avalanche: $26,480 in December
   * 2026 down to $0 in June 2029"*. **This is the chart's text alternative**, so it is required
   * rather than optional: a line a screen reader cannot read is a picture of somebody's money.
   *
   * Composed by the page from the same response fields the points came from, because the sentence
   * needs a translation catalogue and a currency formatter and this component has neither.
   */
  readonly said: string;
  readonly points: readonly ChartPoint[];
  /** Two roles, drawn differently in weight **and** in dash, never in colour alone. */
  readonly role: 'selected' | 'compared';
}

/** An optional vertical mark — a payoff month, a statement date. */
export interface ChartMarker {
  /** `YYYY-MM`, matched against {@link ChartPoint.month}. */
  readonly month: string;
  /** Already translated, and shown. */
  readonly label: string;
}

interface PlottedLine {
  readonly id: string;
  readonly name: string;
  readonly points: string;
  readonly compared: boolean;
}

interface Plot {
  readonly lines: readonly PlottedLine[];
  readonly firstLabel: string;
  readonly lastLabel: string;
  readonly markerX: number | null;
  /** Two lines that turn out to be one. See {@link COINCIDENT_TOLERANCE}. */
  readonly coincident: boolean;
}

/**
 * How close two lines have to be before drawing both of them is a lie.
 *
 * **0.5% of the chart's largest value.** The plot is 11rem — about 176px — tall, so half a percent
 * of it is under one CSS pixel: two lines nearer than that cannot be told apart at any width this
 * component is given, and a legend offering two keys over one visible stroke reads as a line that
 * failed to render rather than as two answers that agree.
 *
 * It is a **display** threshold and nothing else. The figures are unchanged, both lines' sentences
 * stay in the text alternative, and the page's own comparison still reports each strategy's total
 * separately — what is suppressed is a second stroke nobody could see, not a difference.
 *
 * Only two series are ever collapsed. Three lines that happen to agree are left alone: the
 * question "which of these three is missing" is one the viewer can answer from the legend, and
 * the case does not arise from the plan's own two named strategies.
 */
const COINCIDENT_TOLERANCE = 0.005;

/**
 * Two labelled lines over months, as inline SVG.
 *
 * **No charting library and no CDN.** A self-hoster's instance works standing alone
 * (non-negotiable #9), and a chart is a polyline — importing a dependency to draw one would buy
 * features nothing here needs at the cost of the one property the product cannot trade.
 *
 * Three things about it are rules rather than taste:
 *
 * - **It plots what it is given and never resamples.** The server already thins a long line to a
 *   bounded number of points and reports `seriesMonthStride` with it; a component that re-sampled
 *   would draw a different shape from the one the server computed, and the axis labels would stop
 *   describing the data.
 * - **No amount is ever parsed into a number.** An SVG needs coordinates, so each point becomes a
 *   *dimensionless ratio* of the largest value on the chart, computed in exact `bigint` minor
 *   units by `core/money.ts`. The figures the chart *states* are the server's own strings.
 * - **It is readable without seeing it.** The `<svg>` carries `role="img"`, its accessible name,
 *   and a description built from each line's own sentence — the same dates and the same totals the
 *   picture shows. A chart that is only a picture fails `tools/ui-check.sh`'s axe pass, and more
 *   to the point fails the person reading it.
 */
@Component({
  selector: 'app-line-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <figure class="chart">
      @if (plot(); as drawn) {
        <!-- viewBox units are percentages of the plot area, stretched to whatever width the card
             gives it. preserveAspectRatio="none" is what allows that, and non-scaling-stroke is
             what stops the stretch turning the line into a wedge. -->
        <svg
          class="chart-plot"
          viewBox="0 0 100 100"
          preserveAspectRatio="none"
          role="img"
          [attr.aria-label]="label()"
          [attr.aria-describedby]="chartId() + '-description'"
        >
          <line
            class="chart-baseline"
            x1="0"
            y1="100"
            x2="100"
            y2="100"
            vector-effect="non-scaling-stroke"
          />
          @if (drawn.markerX !== null) {
            <line
              class="chart-mark"
              [attr.x1]="drawn.markerX"
              y1="0"
              [attr.x2]="drawn.markerX"
              y2="100"
              vector-effect="non-scaling-stroke"
            />
          }
          @for (line of drawn.lines; track line.id) {
            <polyline
              class="chart-line"
              [class.is-compared]="line.compared"
              [attr.points]="line.points"
              fill="none"
              vector-effect="non-scaling-stroke"
            />
          }
        </svg>

        <!-- Two labels, not an axis of sixty: the ends are what a person reads off a line. -->
        <p class="chart-axis" aria-hidden="true">
          <span>{{ drawn.firstLabel }}</span>
          <span>{{ drawn.lastLabel }}</span>
        </p>
      }

      <!-- The text alternative. Hidden visually because the lines, the legend and the page's own
           headline already say it on screen; present always, because the picture does not. -->
      <p class="visually-hidden" [id]="chartId() + '-description'">
        @for (line of series(); track line.id) {
          <span>{{ line.said }} </span>
        }
        @if (coincident() && coincidentLabel()) {
          <span>{{ coincidentLabel() }} </span>
        }
        @if (marker(); as mark) {
          <span>{{ mark.label }}</span>
        }
      </p>

      <ul class="chart-legend">
        @if (coincident() && coincidentLabel()) {
          <!-- One key for one stroke, naming both series. Two keys over a single visible line is
               the bug this avoids; dropping one of the names would be the same bug inverted. -->
          <li>
            <span class="chart-swatch" aria-hidden="true"></span>
            <span>{{ series()[0].name }} · {{ series()[1].name }}</span>
          </li>
        } @else {
          @for (line of series(); track line.id) {
            <li>
              <span
                class="chart-swatch"
                [class.is-compared]="line.role === 'compared'"
                aria-hidden="true"
              ></span>
              <span>{{ line.name }}</span>
            </li>
          }
        }
      </ul>

      @if (coincident() && coincidentLabel()) {
        <!-- Prose, not a legend key: a sentence beside a 1.25rem swatch read as a caption that
             had lost its label. -->
        <p class="chart-mark-label" aria-hidden="true">{{ coincidentLabel() }}</p>
      }
      @if (marker(); as mark) {
        <p class="chart-mark-label" aria-hidden="true">{{ mark.label }}</p>
      }
      @if (assumption(); as said) {
        <figcaption class="chart-assumption">{{ said }}</figcaption>
      }
    </figure>
  `,
  styles: `
    .chart {
      margin: 0;
    }

    .chart-plot {
      inline-size: 100%;
      /* 11rem, not a token: there is no size scale in the token sheet (design/README.md records
         the gap), and a CSS variable that does not exist fails silently to the inherited value —
         it lints clean and renders wrong. Tall enough that a 60-point line is legible, short
         enough to sit above the fold with the headline on a 390px screen. */
      block-size: 11rem;
      display: block;
      overflow: visible;
    }

    .chart-baseline {
      stroke: var(--color-divider);
      stroke-width: 1;
    }

    .chart-line {
      stroke: var(--color-accent-text);
      /* 2 and 2.5 are stroke widths in device pixels, which no token carries. */
      stroke-width: 2.5;
      stroke-linejoin: round;
      stroke-linecap: round;
    }

    /* Weight AND dash, never colour alone: the two lines must be distinguishable in greyscale and
       by somebody who cannot tell the two accents apart (WCAG 1.4.1). */
    .chart-line.is-compared {
      stroke: var(--color-muted);
      stroke-width: 2;
      stroke-dasharray: 5 4;
    }

    .chart-mark {
      stroke: var(--color-accent-2);
      stroke-width: 2;
      stroke-dasharray: 2 3;
    }

    .chart-axis {
      display: flex;
      justify-content: space-between;
      margin: var(--space-1) 0 0;
      font-size: 0.8125rem;
      color: var(--color-muted);
    }

    .chart-legend {
      display: flex;
      flex-wrap: wrap;
      gap: var(--space-2) var(--space-4);
      list-style: none;
      margin: var(--space-2) 0 0;
      padding: 0;
      font-size: 0.875rem;
    }

    .chart-legend li {
      display: flex;
      align-items: center;
      gap: var(--space-2);
    }

    .chart-swatch {
      /* A legend key: 1.25rem by 3px of line, matching the stroke it stands for. */
      inline-size: 1.25rem;
      block-size: 0;
      border-block-start: 3px solid var(--color-accent-text);
    }

    .chart-swatch.is-compared {
      border-block-start-style: dashed;
      border-color: var(--color-muted);
    }

    .chart-mark-label,
    .chart-assumption {
      margin: var(--space-2) 0 0;
      font-size: 0.875rem;
      color: var(--color-muted);
    }
  `,
})
export class LineChartComponent {
  /** One or two lines. Anything with fewer than two plottable points is left undrawn. */
  readonly series = input.required<readonly ChartSeries[]>();

  /** The `<svg>`'s accessible name, already translated — *"Balance to zero"*. */
  readonly label = input.required<string>();

  /** Unique on the page: the description's id is built from it. */
  readonly chartId = input.required<string>();

  readonly marker = input<ChartMarker | null>(null);

  /** The assumption the lines were drawn under — *"Both lines assume $560/mo"*. */
  readonly assumption = input<string>('');

  /**
   * What to say when the two lines turn out to coincide, already translated.
   *
   * Supplied unconditionally and used only if they do. The component cannot compose the sentence
   * — it holds no catalogue and no locale — but it is the only thing that knows the geometry, so
   * the decision is here and the words are the page's.
   */
  readonly coincidentLabel = input<string>('');

  /** True when two lines were drawn as one. Drives the legend, which must not claim two. */
  protected readonly coincident = computed(() => this.plot()?.coincident ?? false);

  /**
   * The geometry, and the only place a figure becomes a number.
   *
   * `null` when nothing can be drawn: no line with two plottable points, or every balance zero. A
   * chart of nothing is not rendered at all — the page says why instead, because an empty axis
   * reads as a crash.
   */
  protected readonly plot = computed<Plot | null>(() => {
    const lines = this.series();
    const largest = this.largestAmount(lines);
    if (largest === null) {
      return null;
    }

    // Two series within a hair of each other are drawn once and said once. Overlapping strokes
    // are indistinguishable from one line that is missing, which is the worse of the two readings.
    const coincident = lines.length === 2 && this.coincide(lines[0], lines[1], largest);

    const plotted: PlottedLine[] = [];
    for (const line of coincident ? [lines[0]] : lines) {
      const points = this.polyline(line.points, largest);
      if (points !== null) {
        plotted.push({
          id: line.id,
          name: line.name,
          points,
          compared: line.role === 'compared',
        });
      }
    }
    if (plotted.length === 0) {
      return null;
    }

    // The longest line owns the axis: the two strategies cover different spans of months, and
    // labelling from the shorter one would say a plan ends before its own line does.
    const longest = lines.reduce(
      (widest, line) => (line.points.length > widest.points.length ? line : widest),
      lines[0],
    );
    return {
      lines: plotted,
      firstLabel: longest.points[0]?.label ?? '',
      lastLabel: longest.points[longest.points.length - 1]?.label ?? '',
      markerX: this.markerPosition(longest.points),
      coincident,
    };
  });

  /**
   * Whether two lines are the same line at this chart's scale.
   *
   * Compared as **fractions of the largest value**, not as amounts: the question is whether the
   * two strokes land on the same pixels, and a $48 gap means one thing on a $300 chart and another
   * on a $26,000 one. A line of a different length is never collapsed — a plan that runs longer is
   * a visible difference whatever its balances do.
   */
  private coincide(left: ChartSeries, right: ChartSeries, largest: string): boolean {
    if (left.points.length !== right.points.length || left.points.length < 2) {
      return false;
    }
    for (let index = 0; index < left.points.length; index += 1) {
      const here = amountFraction(left.points[index].amount, largest);
      const there = amountFraction(right.points[index].amount, largest);
      if (here === null || there === null || Math.abs(here - there) > COINCIDENT_TOLERANCE) {
        return false;
      }
    }
    return true;
  }

  /** The largest amount anywhere on the chart, as a string. `null` when there is nothing to scale by. */
  private largestAmount(lines: readonly ChartSeries[]): string | null {
    let largest: string | null = null;
    for (const line of lines) {
      for (const point of line.points) {
        if (!isAmount(point.amount)) {
          continue;
        }
        if (largest === null || (compareAmounts(point.amount, largest) ?? 0) > 0) {
          largest = point.amount;
        }
      }
    }
    // A chart whose largest value is zero has no shape: every point would sit on the baseline.
    return largest !== null && (compareAmounts(largest, '0') ?? 0) > 0 ? largest : null;
  }

  /** `"0,12 3.4,18 …"` — the `points` attribute, or `null` for a line that cannot be drawn. */
  private polyline(points: readonly ChartPoint[], largest: string): string | null {
    if (points.length < 2) {
      return null;
    }
    const drawn: string[] = [];
    points.forEach((point, index) => {
      const fraction = amountFraction(point.amount, largest);
      if (fraction === null) {
        return;
      }
      const x = (index / (points.length - 1)) * 100;
      // Clamped at the baseline: an overpaid debt goes below zero, and the chart shows it reaching
      // zero rather than drawing outside its own box. The figure itself is reported in words.
      const y = 100 - Math.max(0, Math.min(1, fraction)) * 100;
      drawn.push(`${round(x)},${round(y)}`);
    });
    return drawn.length < 2 ? null : drawn.join(' ');
  }

  /** Where the marker's month falls along the axis, or `null` when it is not on this chart. */
  private markerPosition(points: readonly ChartPoint[]): number | null {
    const mark = this.marker();
    if (mark === null || points.length < 2) {
      return null;
    }
    const index = points.findIndex((point) => point.month === mark.month);
    return index === -1 ? null : round((index / (points.length - 1)) * 100);
  }
}

/** Two decimal places is a hundredth of the plot's width — well under a pixel. */
function round(value: number): number {
  return Math.round(value * 100) / 100;
}
