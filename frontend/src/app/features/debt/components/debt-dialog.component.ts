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
import { CurrencyFieldComponent } from '../../../shared/components/currency-field.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors } from '../../../shared/forms/validators';
import { Account } from '../../accounts/data/account.models';
import { Bill } from '../../bills/data/bills.models';
import { DEBT_KINDS, Debt, debtKindKey } from '../data/debt.models';
import { DebtFormValue, debtFormFrom, newDebtForm } from '../data/debt-patch';

const NAME_MAX_LENGTH = 60;
const SERVICER_MAX_LENGTH = 80;

/** Unsigned: a balance and a payment are magnitudes. Mirrors `DebtRequests.AMOUNT`. */
const AMOUNT = /^\d{1,15}(\.\d{1,4})?$/;

/** A percentage as a statement prints it, to three places. Mirrors `DebtRequests.RATE`. */
const RATE = /^\d{1,3}(\.\d{1,3})?$/;

/**
 * Add a debt, or change its terms.
 *
 * Five things here are the feature's rules rather than UI taste:
 *
 * - **Every amount and the rate are `type="text"` with `inputmode="decimal"`.** A number input
 *   hands back a `number`, and `22.9` through a double has already lost what it lost (ADR-0006).
 *   The keypad still opens on a phone.
 * - **The balance comes with the date it was true.** That pair is the anchor, and a balance
 *   without its date is a figure with no provenance — which is the one thing `balanceSource`
 *   exists to prevent.
 * - **The rate can be handed back to the solver**, and only on an edit: *"change a solved field
 *   and it becomes yours — I'll re-solve around it"*, and its mirror. Releasing it sends an
 *   explicit `null`; on a debt whose rate is already solved it sends nothing at all.
 * - **A new debt must name its rate.** Solving for one needs a payment *and* a horizon, which this
 *   form does not collect; the terms editor on the detail page is where that case lives.
 * - **An account-linked debt does not choose its own currency.** The account owns that fact, as it
 *   does for a transaction, so the field goes away when an account is named.
 */
@Component({
  selector: 'app-debt-dialog',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
    CurrencyFieldComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './debt-dialog.component.html',
})
export class DebtDialogComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly debt = input<Debt | null>(null);
  readonly accounts = input.required<readonly Account[]>();
  /** Bills the household could name as carrying this debt's minimum. Active ones only. */
  readonly bills = input.required<readonly Bill[]>();
  /** The household's own currency, for the amount hints. */
  readonly currency = input.required<string>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  readonly submitted = output<DebtFormValue>();
  readonly closed = output<void>();

  protected readonly kinds = DEBT_KINDS;
  protected readonly kindKey = debtKindKey;

  protected readonly editing = computed(() => this.debt() !== null);
  protected readonly titleKey = computed<MessageKey>(() =>
    this.editing() ? 'debt.editTitle' : 'debt.addTitle',
  );

  /** Whether the rate was already worked out by the solver, which changes what releasing means. */
  protected readonly rateAlreadySolved = computed(() => this.debt()?.aprSource === 'SOLVED');

  protected readonly openAccounts = computed(() =>
    this.accounts().filter((account) => !account.archived),
  );

  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(NAME_MAX_LENGTH)]],
    kind: ['CREDIT_CARD' as (typeof DEBT_KINDS)[number], [Validators.required]],
    servicer: ['', [Validators.maxLength(SERVICER_MAX_LENGTH)]],
    currency: [''],
    balance: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    balanceDate: ['', [Validators.required]],
    apr: ['', [Validators.required, Validators.pattern(RATE)]],
    aprSolved: [false],
    minimumPayment: ['', [Validators.required, Validators.pattern(AMOUNT)]],
    originalBalance: ['', [Validators.pattern(AMOUNT)]],
    accountId: [''],
    billId: [''],
  });

  private readonly releasing = signal(false);
  /** Whether the rate field is in the "let the solver have it" state. */
  protected readonly rateReleased = this.releasing.asReadonly();

  private readonly linked = signal(false);
  /** Whether an account has been named, which takes the currency choice away. */
  protected readonly accountLinked = this.linked.asReadonly();

  constructor() {
    this.form.controls.aprSolved.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((solved) => this.applyRelease(solved));

    this.form.controls.accountId.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((accountId) => this.applyAccount(accountId));

    effect(() => {
      const debt = this.debt();
      const value = debt === null ? newDebtForm() : debtFormFrom(debt);
      this.form.reset(value, { emitEvent: false });
      this.applyRelease(value.aprSolved);
      this.applyAccount(value.accountId);
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
    // Trimmed before it is validated, so a name of nothing but spaces fails here and says which
    // field it was — the server refuses a blank name with `400 validation-failed`, and finding
    // that out from a round trip is a worse way to learn it.
    const name = this.form.controls.name;
    name.setValue(name.value.trim(), { emitEvent: false });
    name.updateValueAndValidity({ emitEvent: false });

    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitted.emit(this.form.getRawValue());
  }

  /**
   * Releasing the rate.
   *
   * On an edit, ticking the box empties the field and stops requiring it — the solver is about to
   * own it. Creating a debt cannot release it at all, so the box is not offered there and this
   * never runs with an empty field required.
   */
  private applyRelease(solved: boolean): void {
    this.releasing.set(solved);
    const apr = this.form.controls.apr;
    if (solved) {
      apr.setValidators([]);
      apr.setValue('', { emitEvent: false });
    } else {
      apr.setValidators([Validators.required, Validators.pattern(RATE)]);
    }
    apr.updateValueAndValidity({ emitEvent: false });
  }

  /** Naming an account hands it the currency: the account owns that fact, not the debt. */
  private applyAccount(accountId: string): void {
    const named = accountId !== '';
    this.linked.set(named);
    if (named) {
      this.form.controls.currency.setValue('', { emitEvent: false });
    }
  }
}
