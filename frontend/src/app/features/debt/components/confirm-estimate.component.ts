import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from '../../../core/api-error';
import { trimAmount } from '../../../core/money';
import { DateOnlyPipe, MoneyPipe } from '../../../core/i18n/format.pipes';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors } from '../../../shared/forms/validators';
import { BalanceFormValue } from '../data/debt-patch';
import { Debt } from '../data/debt.models';

const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

/**
 * Confirm the estimate, or correct it.
 *
 * `ESTIMATED` is the one place this app puts a number it invented in the headline position, so
 * this sheet exists to get a real one. Four things it does on purpose:
 *
 * - **It says what the estimate assumed** — how many months, and that interest was applied in
 *   between, not just the payments. The designs' own figure forgot the interest and the feature
 *   doc records that; the copy here says the arithmetic out loud rather than repeating it.
 * - **Confirming writes an anchor at today's date.** It is the household asserting a figure, which
 *   is why the estimate stops being an estimate rather than being quietly adjusted.
 * - **"Use the last confirmed figure" is offered as its own answer**, for somebody who cannot get
 *   a statement right now: it re-anchors on the figure they themselves last asserted and stops the
 *   extrapolation, instead of carrying a year of guesses forward.
 * - **Nothing here computes a balance.** Every figure shown is one the server sent.
 */
@Component({
  selector: 'app-confirm-estimate',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    MoneyPipe,
    DateOnlyPipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './confirm-estimate.component.html',
  styles: `
    .headline {
      margin: 0;
    }

    .headline-amount {
      /* 2rem / 700: there is no type scale and no weight token in the sheet (design/README.md),
         and a CSS variable that does not exist fails silently to the inherited value. */
      font-size: 2rem;
      font-weight: 700;
      font-variant-numeric: tabular-nums;
      line-height: 1.1;
    }

    .headline-label {
      display: block;
      color: var(--color-muted);
    }

    /* The two quieter answers the designs put beneath the main pair, kept visibly secondary. */
    .secondary-actions {
      margin: 0;
      gap: var(--space-3);
    }
  `,
})
export class ConfirmEstimateComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly debt = input.required<Debt>();
  readonly today = input.required<string>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  readonly submitted = output<BalanceFormValue>();
  /** The household would rather record a payment than assert a balance. */
  readonly logInstead = output<void>();
  readonly closed = output<void>();

  private readonly correcting = signal(false);
  /** Whether the amount is editable, or the sheet is simply offering the figure it has. */
  protected readonly editing = this.correcting.asReadonly();

  /** Past the twelve-month cap the figure on screen is the last confirmed one, not an estimate. */
  protected readonly stale = computed(() => this.debt().balanceStale);

  readonly form = this.fb.group({
    balance: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    balanceDate: ['', [Validators.required]],
    note: [''],
  });

  constructor() {
    effect(() => {
      this.correcting.set(false);
      this.form.reset(
        { balance: trimAmount(this.debt().balance), balanceDate: this.today(), note: '' },
        { emitEvent: false },
      );
    });

    effect(() => {
      const failure = this.error();
      if (failure) {
        applyServerFieldErrors(this.form, failure);
      }
    });
  }

  /** "Correct it" — the same sheet with the figure open to editing, never a second dialog. */
  protected correct(): void {
    this.correcting.set(true);
  }

  /**
   * "Use the last confirmed figure instead."
   *
   * Re-anchors on {@link Debt.anchorBalance} — what the household last asserted — at today's
   * date, which is what stops the extrapolation. It is still a confirmation, so it still goes
   * through the one endpoint that records provenance.
   */
  protected useLastConfirmed(): void {
    if (this.busy()) {
      return;
    }
    this.submitted.emit({
      balance: this.debt().anchorBalance,
      balanceDate: this.today(),
      note: '',
    });
  }

  protected submit(): void {
    if (this.busy()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitted.emit(this.form.getRawValue());
  }
}
