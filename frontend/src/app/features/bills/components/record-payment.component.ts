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
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors } from '../../../shared/forms/validators';
import { OccurrenceFormValue } from '../data/bill-patch';
import { Bill, BillOccurrence } from '../data/bills.models';

const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

/**
 * Record a payment that was not simply "what was budgeted, today".
 *
 * **Planned and actual are both kept.** A bill carries what it was expected to cost; a payment
 * carries what really left. The difference is the useful number, and a bill whose amount varies
 * is unrepresentable without it — so this asks for the real figure and the date it went.
 *
 * **The due date is a field only for an `IRREGULAR` bill.** Those project nothing by definition,
 * so recording against a date is what brings the occurrence into being (ADR-0032). For every
 * other bill the date is the row that was tapped, and is shown rather than asked for: a date the
 * schedule does not imply is a `404`.
 */
@Component({
  selector: 'app-record-payment',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './record-payment.component.html',
})
export class RecordPaymentComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly bill = input.required<Bill>();
  /** The occurrence being recorded against, or `null` for an irregular bill's new one. */
  readonly occurrence = input<BillOccurrence | null>(null);
  /** Today as `YYYY-MM-DD`, supplied by the page so this component has no clock of its own. */
  readonly today = input.required<string>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  readonly submitted = output<OccurrenceFormValue>();
  readonly closed = output<void>();

  /** An irregular bill has no projected date, so the person chooses one. */
  protected readonly choosesDate = computed(() => this.occurrence() === null);
  protected readonly titleKey = computed<MessageKey>(() =>
    this.choosesDate() ? 'bills.recordTitle' : 'bills.paidTitle',
  );

  /**
   * Whether there is a budgeted figure worth filling in.
   *
   * An irregular bill has no projected occurrence and often no meaningful expected amount, so the
   * field opens empty — and the hint must not then promise that "the budgeted figure is filled in
   * for you", which is a screen telling the reader something it has not done.
   */
  protected readonly amountHintKey = computed<MessageKey>(() => {
    const prefill = this.occurrence()?.actualAmount ?? this.occurrence()?.expectedAmount;
    return prefill && Number(prefill) !== 0
      ? 'bills.actualAmountHint'
      : 'bills.actualAmountHintBlank';
  });

  readonly form = this.fb.group({
    dueDate: ['', [Validators.required]],
    actualAmount: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    paidDate: [''],
  });

  constructor() {
    effect(() => {
      const occurrence = this.occurrence();
      this.form.reset({
        dueDate: occurrence?.dueDate ?? this.today(),
        // Defaulted to what was budgeted, which is the figure most people are confirming.
        actualAmount: occurrence?.actualAmount ?? occurrence?.expectedAmount ?? '',
        paidDate: occurrence?.paidDate ?? this.today(),
      });
    });

    effect(() => {
      const failure = this.error();
      if (failure) {
        applyServerFieldErrors(this.form, failure);
      }
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
