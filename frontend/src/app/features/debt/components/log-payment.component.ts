import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from '../../../core/api-error';
import { MoneyPipe } from '../../../core/i18n/format.pipes';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { addAmounts, trimAmount } from '../../../core/money';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors } from '../../../shared/forms/validators';
import { Account } from '../../accounts/data/account.models';
import { Debt } from '../data/debt.models';
import { PaymentFormValue } from '../data/debt-patch';

/** Unsigned: a payment is a magnitude. Mirrors `DebtRequests.AMOUNT`. */
const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

/** The one-tap alternative the designs offer beside what is scheduled. */
const EXTRA_STEP = '50';

/**
 * Log a payment by hand — the slice-10 path.
 *
 * Four things here are the feature's rules rather than UI taste:
 *
 * - **Prefilled with what is scheduled**, which is the plan's allocation for this debt, with
 *   `+$50` and `Other` beside it. The prefill is a convenience the household confirms, never an
 *   assertion the app makes on their behalf — the figure they submit is the figure they say they
 *   paid.
 * - **"Paid from" is not a transaction**, and it is not the account the debt is held at. It
 *   records where the money left, which is where a matching charge will be looked for in slice 16.
 *   Nothing is written to that account and no ledger row is created (ADR-0035).
 * - **The split is not computed here.** The interest and the principal are worked out by the
 *   server under the rate in force and come back on the response; a second implementation of
 *   `balance × apr ÷ 1200` in the browser would be the one place the two could disagree, and this
 *   feature's whole argument is that a few cents compounded become a whole month. The sheet says
 *   what will happen, and the page reports the server's own split the moment it is recorded.
 * - **Double-submit is a real bug when money is involved**, so the button goes quiet in flight.
 */
@Component({
  selector: 'app-log-payment',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    MoneyPipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './log-payment.component.html',
})
export class LogPaymentComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly debt = input.required<Debt>();
  readonly accounts = input.required<readonly Account[]>();
  /** Today, `YYYY-MM-DD`, in the viewer's own calendar — a date is a calendar question. */
  readonly today = input.required<string>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  readonly submitted = output<PaymentFormValue>();
  readonly closed = output<void>();

  readonly form = this.fb.group({
    paidDate: ['', [Validators.required]],
    amount: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    paidFromAccountId: [''],
    transactionId: [''],
    note: [''],
  });

  /**
   * What the plan says this debt is paying, which is what the sheet opens with.
   *
   * Trimmed of trailing zeros for the field — the same figure, written the way somebody about to
   * edit it would write it. `445.0000` in a text box reads as a glitch.
   */
  protected readonly scheduled = computed(() =>
    trimAmount(this.debt().payment ?? this.debt().minimumPayment),
  );

  /**
   * The scheduled figure plus £50 — the designs' one-tap alternative.
   *
   * Exact `bigint` addition (`core/money.ts`), and it fills a form field rather than being sent
   * anywhere: what reaches the server is the figure the household submitted as their own.
   */
  protected readonly withExtra = computed(() => addAmounts([this.scheduled(), EXTRA_STEP]));

  protected readonly openAccounts = computed(() =>
    this.accounts().filter((account) => !account.archived),
  );

  constructor() {
    effect(() => {
      this.form.reset(
        {
          paidDate: this.today(),
          amount: this.scheduled(),
          paidFromAccountId: '',
          transactionId: '',
          note: '',
        },
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

  /** One tap: what is scheduled, or that plus £50. Neither is submitted on its own. */
  protected use(amount: string | null): void {
    if (amount !== null) {
      this.form.controls.amount.setValue(amount);
      this.form.controls.amount.markAsDirty();
    }
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
