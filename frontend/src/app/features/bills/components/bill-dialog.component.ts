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
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from '../../../core/api-error';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors } from '../../../shared/forms/validators';
import { pickableCategories } from '../../categories/data/category-tree';
import { Category } from '../../categories/data/category.models';
import { BillFormValue } from '../data/bill-patch';
import { Bill } from '../data/bills.models';
import {
  applyScheduleKind,
  buildScheduleGroup,
  resetScheduleGroup,
  scheduleValueOf,
} from '../data/schedule-form';
import { ScheduleFieldsComponent } from './schedule-fields.component';

const NAME_MAX_LENGTH = 80;

/** Unsigned: a bill is money leaving, and the direction is the table, not the sign. */
const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

/**
 * Set a bill up, or change your mind about one.
 *
 * Three things here are the feature's rules rather than UI taste:
 *
 * - **The amount is `type="text"` with `inputmode="decimal"`.** A number input hands back a
 *   `number`, and an amount that has been through a double has already lost whatever it lost
 *   (ADR-0006). The keypad still opens on a phone.
 * - **Changing the schedule's kind clears the parameters that stopped applying**, because the
 *   server refuses a parameter a kind has no use for rather than ignoring it.
 * - **A tolerance appears only when the amount varies**, and the two are sent together or not at
 *   all: "about £120, give or take £22" is one fact, and half of it means nothing.
 */
@Component({
  selector: 'app-bill-dialog',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
    ScheduleFieldsComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './bill-dialog.component.html',
})
export class BillDialogComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly bill = input<Bill | null>(null);
  readonly categories = input.required<readonly Category[]>();
  readonly currency = input.required<string>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  readonly submitted = output<BillFormValue>();
  readonly closed = output<void>();

  protected readonly editing = computed(() => this.bill() !== null);
  protected readonly titleKey = computed<MessageKey>(() =>
    this.editing() ? 'bills.editTitle' : 'bills.addTitle',
  );
  protected readonly choosableCategories = computed(() => pickableCategories(this.categories()));

  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(NAME_MAX_LENGTH)]],
    expectedAmount: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    categoryId: [''],
    amountVaries: [false],
    tolerance: [''],
    schedule: buildScheduleGroup(this.fb),
  });

  /** Whether the tolerance field is on screen. Kept in step by {@link applyVaries}. */
  private readonly variesNow = signal(false);
  protected readonly varies = this.variesNow.asReadonly();

  constructor() {
    // The kind is the one control that decides which other controls exist. Subscribed before the
    // form is first filled, so an edit of a weekly bill arrives with its weekday required.
    this.form.controls.schedule.controls.kind.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((kind) => applyScheduleKind(this.form.controls.schedule, kind));

    this.form.controls.amountVaries.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((varies) => this.applyVaries(varies));

    effect(() => {
      const bill = this.bill();
      this.form.reset(
        {
          name: bill?.name ?? '',
          expectedAmount: bill?.expectedAmount ?? '',
          categoryId: bill?.categoryId ?? '',
          amountVaries: bill?.amountVaries ?? false,
          tolerance: bill?.tolerance ?? '',
        },
        { emitEvent: false },
      );
      resetScheduleGroup(this.form.controls.schedule, scheduleValueOf(bill?.schedule ?? null));
      this.applyVaries(bill?.amountVaries ?? false);
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

  /** A tolerance is required exactly when the amount varies, and cleared when it does not. */
  private applyVaries(varies: boolean): void {
    this.variesNow.set(varies);
    const tolerance = this.form.controls.tolerance;
    if (varies) {
      tolerance.setValidators([Validators.required, Validators.pattern(AMOUNT)]);
    } else {
      tolerance.setValidators([]);
      tolerance.setValue('', { emitEvent: false });
    }
    tolerance.updateValueAndValidity({ emitEvent: false });
  }
}
