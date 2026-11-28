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
import { MoneyPipe, PeriodPipe } from '../../../core/i18n/format.pipes';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { compareAmounts, trimAmount } from '../../../core/money';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors } from '../../../shared/forms/validators';
import { Goal } from '../data/goal.models';
import { ContributionFormValue, Direction } from '../data/goal-patch';

/** Unsigned: the direction carries the sign, not a minus typed into the field. */
const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

/**
 * *Set money aside*, or *Take money out* — one dialog, two directions.
 *
 * - **Setting aside opens with this month's `stillToCome`**: *"Set aside $110 for November"*. One
 *   tap records the plan's amount; change the figure for a top-up.
 * - **Taking money out is the same dialog for a withdrawal**, not a minus sign typed into the
 *   first: the field is unsigned and the page sends it negative.
 * - **Recording one writes no transaction and moves no account** (ADR-0035). The dialog says so,
 *   because a household that expects its savings balance to move will think the app is broken.
 * - **The button goes quiet in flight.** Double-tapping would record two contributions; that is a
 *   courtesy, not a control, and the feature doc says so.
 */
@Component({
  selector: 'app-contribution-dialog',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    MoneyPipe,
    PeriodPipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './contribution-dialog.component.html',
})
export class ContributionDialogComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly goal = input.required<Goal>();
  readonly direction = input.required<Direction>();
  /** Today, `YYYY-MM-DD`, in the viewer's own calendar. */
  readonly today = input.required<string>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  readonly submitted = output<ContributionFormValue>();
  readonly closed = output<void>();

  readonly form = this.fb.group({
    amount: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    date: ['', [Validators.required]],
    note: [''],
  });

  protected readonly title = computed<MessageKey>(() =>
    this.direction() === 'in' ? 'goals.contribute.inTitle' : 'goals.contribute.outTitle',
  );

  protected readonly submitLabel = computed<MessageKey>(() =>
    this.busy()
      ? 'common.saving'
      : this.direction() === 'in'
        ? 'goals.contribute.inSave'
        : 'goals.contribute.outSave',
  );

  /** What is still to come this month, when there is any — the one-tap figure. */
  protected readonly stillToCome = computed(() => {
    const goal = this.goal();
    return this.direction() === 'in' && compareAmounts(goal.thisPeriod.stillToCome, '0') === 1
      ? goal.thisPeriod.stillToCome
      : null;
  });

  constructor() {
    effect(() => {
      const prefill = this.stillToCome();
      this.form.reset(
        { amount: prefill === null ? '' : trimAmount(prefill), date: this.today(), note: '' },
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
