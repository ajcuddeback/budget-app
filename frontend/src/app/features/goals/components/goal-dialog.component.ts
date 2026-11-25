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
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { trimAmount } from '../../../core/money';
import { CurrencyFieldComponent } from '../../../shared/components/currency-field.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors } from '../../../shared/forms/validators';
import { Account } from '../../accounts/data/account.models';
import { Goal } from '../data/goal.models';
import { GoalFormValue, Said } from '../data/goal-patch';

/** Unsigned, at most four places. Mirrors `GoalRequests.AMOUNT`; the server checks the scale. */
const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

/** At least one character that is not whitespace, so `"   "` is caught before the server. */
const NOT_BLANK = /\S/u;

/** How long typing settles before the server is asked. Long enough that "3500" is one question. */
const PREVIEW_SETTLES_AFTER_MS = 350;

/**
 * Add a goal, or edit one.
 *
 * - **The server's preview, as the amounts settle.** A late answer is dropped by the page, a
 *   half-typed amount asks nothing, and a failed preview never blocks saving — the three rules the
 *   debt payment sheet keeps. The browser computes no projection.
 * - **"Already saved"** is the starting amount, asked for by name: a goal part-funded on day one is
 *   entered here, never as a contribution dated today, which would take the whole sum off this
 *   month's left-over figure.
 * - **A linked account owns the currency**, so the currency field is offered only when no account
 *   is chosen, and only when adding: a goal's currency is fixed at creation.
 */
@Component({
  selector: 'app-goal-dialog',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
    CurrencyFieldComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './goal-dialog.component.html',
  styles: `
    .preview {
      margin: 0;
    }
  `,
})
export class GoalDialogComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  /** The goal being edited, or `null` to add one. */
  readonly goal = input<Goal | null>(null);
  readonly accounts = input.required<readonly Account[]>();
  /** The household's base currency: what a goal with no account and no choice is in. */
  readonly baseCurrency = input.required<string>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);
  /** The server's projection in words, built by the page from the preview answer. */
  readonly previewSaid = input<readonly Said[]>([]);
  readonly previewUnavailable = input(false);
  /** Bumped by the page to refill the form — never on a re-read (docs/memory/gotchas.md). */
  readonly resetKey = input(0);

  readonly submitted = output<GoalFormValue>();
  readonly previewRequested = output<GoalFormValue>();
  readonly closed = output<void>();

  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(80), Validators.pattern(NOT_BLANK)]],
    targetAmount: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    targetMonth: [''],
    startingAmount: ['', [Validators.pattern(AMOUNT)]],
    monthlyContribution: ['', [Validators.pattern(AMOUNT)]],
    currency: [''],
    accountId: [''],
  });

  protected readonly adding = computed(() => this.goal() === null);

  protected readonly submitLabel = computed<MessageKey>(() =>
    this.busy() ? 'common.saving' : this.adding() ? 'goals.add' : 'common.save',
  );

  /** Editing can only link an account in the goal's own currency; anything else is refused. */
  protected readonly choosableAccounts = computed(() => {
    const goal = this.goal();
    return this.accounts().filter(
      (account) =>
        (!account.archived || account.id === goal?.accountId) &&
        (goal === null || account.currency === goal.currency),
    );
  });

  constructor() {
    this.form.valueChanges
      .pipe(debounceTime(PREVIEW_SETTLES_AFTER_MS), takeUntilDestroyed())
      .subscribe(() => this.previewRequested.emit(this.form.getRawValue()));

    effect(() => {
      this.resetKey();
      const goal = untracked(this.goal);
      this.form.reset(
        goal === null
          ? {
              name: '',
              targetAmount: '',
              targetMonth: '',
              startingAmount: '',
              monthlyContribution: '',
              currency: '',
              accountId: '',
            }
          : {
              name: goal.name,
              targetAmount: trimAmount(goal.targetAmount),
              targetMonth: goal.targetMonth ?? '',
              startingAmount: trimAmount(goal.startingAmount),
              monthlyContribution: '',
              currency: goal.currency,
              accountId: goal.accountId ?? '',
            },
        { emitEvent: false },
      );
      if (goal !== null) {
        this.previewRequested.emit(this.form.getRawValue());
      }
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
