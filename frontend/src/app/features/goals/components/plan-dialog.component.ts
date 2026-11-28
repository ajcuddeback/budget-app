import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { debounceTime } from 'rxjs/operators';
import { ApiError } from '../../../core/api-error';
import { PeriodPipe } from '../../../core/i18n/format.pipes';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { isAmount, trimAmount } from '../../../core/money';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors } from '../../../shared/forms/validators';
import { Goal } from '../data/goal.models';
import { Said, nextMonth } from '../data/goal-patch';

const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

const PREVIEW_SETTLES_AFTER_MS = 350;

/** What the plan dialog hands the page: an amount, and the month it starts from. */
export interface PlanFormValue {
  monthlyAmount: string;
  fromPeriod: string;
}

/**
 * Change the monthly plan — *"from September, $165"* (decision 4).
 *
 * **From this month or from next month, never from a past one.** Raising the plan in September
 * must not rewrite what August's left-over figure said; a correction to a past month is a
 * contribution, not a plan. So the choice is offered as two months, and the server refuses
 * anything earlier.
 *
 * The preview is the server's, as the amount settles, and only for a plan from this month — which
 * is the plan the preview endpoint projects. For next month the dialog says plainly that this
 * month is unchanged.
 */
@Component({
  selector: 'app-plan-dialog',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    PeriodPipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './plan-dialog.component.html',
  styles: `
    .preview {
      margin: 0;
    }

    .choices {
      border: 0;
      margin: 0;
      padding: 0;
    }
  `,
})
export class PlanDialogComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly goal = input.required<Goal>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);
  readonly previewSaid = input<readonly Said[]>([]);
  readonly previewUnavailable = input(false);
  /** Prefills the amount: "Use this £110 for another goal" opens here with a figure in it. */
  readonly suggestedAmount = input<string | null>(null);

  readonly submitted = output<PlanFormValue>();
  /** An amount worth asking about from this month, or `''` for nothing to ask. */
  readonly previewRequested = output<string>();
  readonly closed = output<void>();

  readonly form = this.fb.group({
    monthlyAmount: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    from: ['this' as 'this' | 'next'],
  });

  /** "This month" is the server's month, which the goal reports, never the browser's guess. */
  protected readonly thisMonth = computed(() => this.goal().thisPeriod.period);
  protected readonly followingMonth = computed(() => nextMonth(this.thisMonth()));

  protected readonly submitLabel = computed<MessageKey>(() =>
    this.busy() ? 'common.saving' : 'goals.plan.save',
  );

  constructor() {
    this.form.valueChanges
      .pipe(debounceTime(PREVIEW_SETTLES_AFTER_MS), takeUntilDestroyed())
      .subscribe(() => this.ask());

    effect(() => {
      const goal = this.goal();
      const suggested = untracked(this.suggestedAmount);
      this.form.reset(
        { monthlyAmount: trimAmount(suggested ?? goal.monthlyContribution), from: 'this' },
        { emitEvent: false },
      );
      this.ask();
    });

    effect(() => {
      const failure = this.error();
      if (failure) {
        applyServerFieldErrors(this.form, failure);
      }
    });
  }

  protected fromNextMonth(): boolean {
    return this.form.controls.from.value === 'next';
  }

  /** A half-typed amount, or a plan from next month, asks nothing. */
  private ask(): void {
    const amount = this.form.controls.monthlyAmount.value.trim();
    this.previewRequested.emit(
      this.form.controls.from.value === 'this' && isAmount(amount) ? amount : '',
    );
  }

  protected submit(): void {
    if (this.busy()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitted.emit({
      monthlyAmount: this.form.controls.monthlyAmount.value.trim(),
      fromPeriod: this.fromNextMonth() ? this.followingMonth() : this.thisMonth(),
    });
  }
}
