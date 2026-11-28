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
import { ApiError } from '../../../core/api-error';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors } from '../../../shared/forms/validators';
import { Account } from '../../accounts/data/account.models';
import { IncomeFormValue } from '../data/bill-patch';
import { IncomeSource } from '../data/bills.models';
import {
  applyScheduleKind,
  buildScheduleGroup,
  resetScheduleGroup,
  scheduleValueOf,
} from '../data/schedule-form';
import { ScheduleFieldsComponent } from './schedule-fields.component';

const NAME_MAX_LENGTH = 80;

/** Unsigned: income arriving is money in, and the direction is the table, not the sign. */
const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

/**
 * Describe money arriving on a schedule.
 *
 * **The amount is what arrives each time, not the month's total.** A fortnightly wage lands two
 * or three times in a month and the server multiplies; a client that asked for the month's figure
 * would be asking somebody to do that arithmetic in their head, differently every month.
 *
 * There is no `sourceOfTruth` control: it says where a figure came from, and a client asserting
 * that its own typed number was derived from history would make the field a lie.
 */
@Component({
  selector: 'app-income-dialog',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
    ScheduleFieldsComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './income-dialog.component.html',
})
export class IncomeDialogComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly source = input<IncomeSource | null>(null);
  readonly accounts = input.required<readonly Account[]>();
  readonly currency = input.required<string>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  readonly submitted = output<IncomeFormValue>();
  readonly closed = output<void>();

  protected readonly editing = computed(() => this.source() !== null);
  protected readonly titleKey = computed<MessageKey>(() =>
    this.editing() ? 'bills.income.editTitle' : 'bills.income.addTitle',
  );
  /** Archived accounts are not offered: they exist so history reads, not for new plans. */
  protected readonly choosableAccounts = computed(() =>
    this.accounts().filter((account) => !account.archived),
  );

  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(NAME_MAX_LENGTH)]],
    amount: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    accountId: [''],
    schedule: buildScheduleGroup(this.fb),
  });

  constructor() {
    this.form.controls.schedule.controls.kind.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((kind) => applyScheduleKind(this.form.controls.schedule, kind));

    effect(() => {
      const source = this.source();
      this.form.reset(
        {
          name: source?.name ?? '',
          amount: source?.amount ?? '',
          accountId: source?.accountId ?? '',
        },
        { emitEvent: false },
      );
      resetScheduleGroup(this.form.controls.schedule, scheduleValueOf(source?.schedule ?? null));
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
