import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { debounceTime } from 'rxjs/operators';
import { ApiError } from '../../../core/api-error';
import { MoneyPipe } from '../../../core/i18n/format.pipes';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { addAmounts, isAmount, trimAmount } from '../../../core/money';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors } from '../../../shared/forms/validators';
import { Account } from '../../accounts/data/account.models';
import { Debt, PaymentPreview, PreviewPaymentRequest } from '../data/debt.models';
import { PaymentFormValue } from '../data/debt-patch';

/** Unsigned: a payment is a magnitude. Mirrors `DebtRequests.AMOUNT`. */
const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

/** What a date input holds once a whole date is in it. */
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The one-tap alternative the designs offer beside what is scheduled. */
const EXTRA_STEP = '50';

/**
 * How long typing settles before the split is asked for.
 *
 * Long enough that "445" does not fire four requests, short enough that the figure appears while
 * somebody is still looking at the field. The one-tap amounts skip it entirely: nobody is typing,
 * so there is nothing to wait for.
 */
const PREVIEW_SETTLES_AFTER_MS = 350;

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
  styles: `
    /* Kept in the flow even while empty: a live region has to exist before its text changes, or
       the first answer is announced to nobody. */
    .preview:empty {
      margin: 0;
    }

    .preview {
      margin: 0;
      font-variant-numeric: tabular-nums;
      /* 600, not a var: there is no weight token (design/README.md), and a CSS variable that does
         not exist fails silently to the inherited value. */
      font-weight: 600;
    }
  `,
})
export class LogPaymentComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly debt = input.required<Debt>();
  readonly accounts = input.required<readonly Account[]>();
  /** Today, `YYYY-MM-DD`, in the viewer's own calendar — a date is a calendar question. */
  readonly today = input.required<string>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  /**
   * What the server says this amount would record — the split and the resulting balance.
   *
   * `null` while nothing has been asked, or when the answer did not arrive. **Never computed
   * here**: a second implementation of `balance × apr ÷ 1200` in the browser is the one place it
   * could drift from the figure that actually gets stored.
   */
  readonly preview = input<PaymentPreview | null>(null);

  /** The preview was refused or failed. The sheet still records payments — it is a convenience. */
  readonly previewUnavailable = input(false);

  readonly submitted = output<PaymentFormValue>();

  /**
   * A payment worth asking the server about — the amount and the date it would be logged with — or
   * an `amount` of `''` for one it cannot answer yet.
   *
   * The date is part of the question because it changes the answer: a second payment in a month
   * that has already been charged its interest is all principal (ADR-0040).
   */
  readonly previewAsked = output<PreviewPaymentRequest>();

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
   * Exact `bigint` addition (`core/money.ts`), trimmed for the field the way the prefill is: the
   * button offers £495.00 and the field should then hold `495`, not `495.0000`. It fills a form
   * field rather than being sent anywhere — what reaches the server is the figure the household
   * submitted as their own.
   */
  protected readonly withExtra = computed(() => {
    const more = addAmounts([this.scheduled(), EXTRA_STEP]);
    return more === null ? null : trimAmount(more);
  });

  protected readonly openAccounts = computed(() =>
    this.accounts().filter((account) => !account.archived),
  );

  /** The last question asked, so setting a field does not ask the same question twice. */
  private asked = '';

  constructor() {
    this.form.controls.amount.valueChanges
      .pipe(debounceTime(PREVIEW_SETTLES_AFTER_MS), takeUntilDestroyed())
      .subscribe((amount) => this.ask(amount));
    // A date is picked, not typed, so there is nothing to wait for.
    this.form.controls.paidDate.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.ask(this.form.controls.amount.value));

    effect(() => {
      const scheduled = this.scheduled();
      this.form.reset(
        {
          paidDate: this.today(),
          amount: scheduled,
          paidFromAccountId: '',
          transactionId: '',
          note: '',
        },
        { emitEvent: false },
      );
      // The sheet opens with a figure in it, so it opens with a question about that figure.
      this.asked = '';
      this.ask(scheduled);
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
      this.form.controls.amount.setValue(amount, { emitEvent: false });
      this.form.controls.amount.markAsDirty();
      // Immediately: nobody is typing, so there is nothing to wait to settle.
      this.ask(amount);
    }
  }

  /**
   * Asks what this amount, on the date in the form, would record.
   *
   * A figure the field cannot yet carry — empty, or a half-typed `4.` — asks nothing and shows
   * nothing, rather than sending a request the server would refuse and the sheet would report as
   * a failure. A date that is not yet a whole date is left out, and the server takes today.
   */
  private ask(amount: string): void {
    const figure = isAmount(amount.trim()) ? amount.trim() : '';
    const date = this.form.controls.paidDate.value;
    const question: PreviewPaymentRequest =
      figure !== '' && ISO_DATE.test(date)
        ? { amount: figure, paidDate: date }
        : { amount: figure };
    const key = `${question.amount}|${question.paidDate ?? ''}`;
    if (key === this.asked) {
      return;
    }
    this.asked = key;
    this.previewAsked.emit(question);
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
