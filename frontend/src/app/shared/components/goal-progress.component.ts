import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { MoneyPipe, PeriodPipe } from '../../core/i18n/format.pipes';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { amountFraction, compareAmounts } from '../../core/money';

/**
 * The goal row from the home screen's Goals card: a name, an optional month, *saved of target*,
 * and a bar under it (`docs/features/goals.md`, UI).
 *
 * **Shared, and presentational only** — inputs in, nothing injected — so that when the home screen
 * is built its Goals card reuses this rather than redrawing it.
 *
 * - **"$890.00 of $3,500.00"**, a sentence rather than the canvas's slash, which reads as a ratio.
 * - **The name and the month are separate inputs** composed by an i18n message, so a right-to-left
 *   locale can reorder *"Japan, November 2027"* (ADR-0023).
 * - **The bar is the one place an amount becomes a `number`**, through `amountFraction`: a
 *   dimensionless ratio computed in exact `bigint` minor units. It is `aria-hidden`, and the
 *   sentence is its accessible equivalent. Deliberately not a `<meter>`, whose `value` would need
 *   the amount itself as a JavaScript number.
 * - **Over 100% fills the bar** and the sentence says the real figure. **A negative figure shows
 *   an empty bar** and the negative amount, in the colour a negative "left" uses.
 */
@Component({
  selector: 'app-goal-progress',
  imports: [TranslatePipe, MoneyPipe, PeriodPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="goal-progress" [class.is-large]="large()">
      <p class="goal-line">
        <span class="goal-name">
          @if (targetMonth(); as month) {
            {{ 'goals.row.nameAndMonth' | t: { name: name(), month: month | period } }}
          } @else {
            {{ name() }}
          }
        </span>
        <span class="goal-figure" [class.is-negative]="negative()">
          {{
            'goals.row.savedOf'
              | t: { saved: saved() | money: currency(), target: target() | money: currency() }
          }}
        </span>
      </p>
      <div class="goal-bar" aria-hidden="true">
        <div class="goal-bar-fill" [style.inline-size.%]="filled()"></div>
      </div>
    </div>
  `,
  styles: `
    .goal-progress {
      display: grid;
      gap: var(--space-2);
    }

    .goal-line {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: var(--space-1) var(--space-3);
      margin: 0;
    }

    .goal-name {
      font-weight: 600;
      overflow-wrap: anywhere;
    }

    .goal-figure {
      font-variant-numeric: tabular-nums;
    }

    /* The colour a negative "left" uses on Bills & Income: the olive accent reads as success. */
    .goal-figure.is-negative {
      color: var(--color-accent-text);
    }

    .goal-bar {
      block-size: var(--space-2);
      border-radius: var(--radius-sm);
      background: var(--color-goal-track);
      overflow: hidden;
    }

    .is-large .goal-bar {
      block-size: var(--space-3);
    }

    .goal-bar-fill {
      block-size: 100%;
      max-inline-size: 100%;
      border-radius: inherit;
      background: var(--color-accent-2);
    }
  `,
})
export class GoalProgressComponent {
  readonly name = input.required<string>();
  /** `YYYY-MM`, or `null` for a goal with no month — then no month is in the label at all. */
  readonly targetMonth = input<string | null>(null);
  readonly saved = input.required<string>();
  readonly target = input.required<string>();
  readonly currency = input.required<string>();
  /** The detail page's bigger version. Same row, same rules. */
  readonly large = input(false);

  protected readonly negative = computed(() => compareAmounts(this.saved(), '0') === -1);

  /**
   * How much of the bar is filled, as a percentage — geometry, never money. Clamped to the bar:
   * over 100% fills it and a negative figure empties it. The sentence beside it carries the real
   * figure in both cases.
   */
  protected readonly filled = computed(() => {
    const fraction = amountFraction(this.saved(), this.target());
    if (fraction === null || fraction <= 0) {
      return 0;
    }
    return Math.min(fraction, 1) * 100;
  });
}
