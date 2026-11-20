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
import { toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from '../../../core/api-error';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import {
  amount as amountValidator,
  applyServerFieldErrors,
} from '../../../shared/forms/validators';
import { Account } from '../../accounts/data/account.models';
import { Category } from '../../categories/data/category.models';
import { pickableCategories } from '../../categories/data/category-tree';
import { TransactionFormValue, directionOf, magnitudeOf } from '../data/transaction-patch';
import {
  TRANSACTION_STATUSES,
  Transaction,
  TransactionStatus,
  transactionStatusKey,
} from '../data/transaction.models';

/** The server's `CHECK (length(btrim(note)) BETWEEN 1 AND 500)`, mirrored for early feedback. */
const NOTE_MAX_LENGTH = 500;

/**
 * Record a transaction, or correct one.
 *
 * Four things here are the feature's rules rather than UI taste:
 *
 * - **The amount is typed unsigned and the direction is a separate choice.** Nobody writing down a
 *   £42 shop types a minus sign, and asking them to is how a month of income ends up recorded as
 *   spending. {@link signedAmount} applies the sign once, on the way out.
 * - **The amount is a text field, never `type="number"`.** A number input hands you a `number`,
 *   and an amount that has been through a double has already lost whatever it lost (ADR-0006).
 * - **The account cannot be changed on an edit.** Moving a transaction between accounts changes two
 *   balances and, when the currencies differ, the meaning of the amount — the server does not
 *   accept it, so the control is disabled with the reason beside it rather than hidden.
 * - **A category is optional and says so.** "Needs a category" is the first option, not a blank:
 *   recording what happened must never be gated on tidying up first.
 *
 * Payee suggestions come from a native `<datalist>`. It gives keyboard and screen-reader support
 * for free, which a hand-built dropdown would have to earn — and if suggestions fail to load, the
 * field is still an ordinary text input rather than a broken combobox.
 */
@Component({
  selector: 'app-transaction-dialog',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './transaction-dialog.component.html',
})
export class TransactionDialogComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  /** The transaction being corrected, or `null` to record one. */
  readonly transaction = input<Transaction | null>(null);
  /** The accounts it may be recorded against. Archived ones are excluded by the page. */
  readonly accounts = input.required<readonly Account[]>();
  readonly categories = input.required<readonly Category[]>();
  /** Pre-selects an account when the ledger is already filtered to one. */
  readonly defaultAccountId = input<string | null>(null);
  /** Today, as `YYYY-MM-DD`, supplied by the page so this component has no clock of its own. */
  readonly today = input.required<string>();
  readonly payeeSuggestions = input<readonly string[]>([]);
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  readonly submitted = output<TransactionFormValue>();
  readonly payeeTyped = output<string>();
  readonly closed = output<void>();

  protected readonly statuses = TRANSACTION_STATUSES;
  protected readonly statusKey = transactionStatusKey;
  protected readonly editing = computed(() => this.transaction() !== null);
  protected readonly titleKey = computed<MessageKey>(() =>
    this.editing() ? 'transactions.editTitle' : 'transactions.addTitle',
  );

  /** Flattened, children after their parent, archived excluded — see {@link pickableCategories}. */
  protected readonly choosableCategories = computed(() => pickableCategories(this.categories()));

  readonly form = this.fb.group({
    accountId: ['', [Validators.required]],
    date: ['', [Validators.required]],
    direction: ['out' as 'out' | 'in', [Validators.required]],
    amount: ['', [Validators.required, amountValidator]],
    payeeName: [''],
    /** `''` is "Needs a category", which is a real choice and not an empty one. */
    categoryId: [''],
    note: ['', [Validators.maxLength(NOTE_MAX_LENGTH)]],
    status: ['CLEARED' as TransactionStatus, [Validators.required]],
  });

  private readonly formValue = toSignal(this.form.valueChanges, {
    initialValue: this.form.getRawValue() as Partial<TransactionFormValue>,
  });

  /**
   * The currency the typed amount will be denominated in — the chosen account's.
   *
   * Shown beside the amount field because an amount means nothing without it, and because this is
   * the only place a person can notice they are about to record £40 against their euro account.
   */
  protected readonly currency = computed(() => {
    const accountId = this.formValue().accountId ?? '';
    return this.accounts().find((account) => account.id === accountId)?.currency ?? '';
  });

  /** What the person last typed into the payee box, debounced into a suggestion request. */
  private readonly typedPayee = signal('');

  constructor() {
    effect(() => {
      const transaction = this.transaction();
      this.form.reset(
        transaction
          ? {
              accountId: transaction.accountId,
              date: transaction.date,
              direction: directionOf(transaction),
              // The unsigned magnitude: the sign lives in the direction control.
              amount: magnitudeOf(transaction),
              payeeName: transaction.payeeName ?? '',
              categoryId: transaction.categoryId ?? '',
              note: transaction.note ?? '',
              status: transaction.status,
            }
          : {
              accountId: this.defaultAccountId() ?? this.accounts()[0]?.id ?? '',
              date: this.today(),
              // Most entries are money leaving. Defaulting to the common case saves a click
              // without hiding the other one.
              direction: 'out',
              amount: '',
              payeeName: '',
              categoryId: '',
              note: '',
              status: 'CLEARED',
            },
      );
    });

    effect(() => {
      // An existing transaction's account is fixed: the server does not accept a change, so the
      // control is disabled rather than offering something that would be refused.
      if (this.editing()) {
        this.form.controls.accountId.disable({ emitEvent: false });
      } else {
        this.form.controls.accountId.enable({ emitEvent: false });
      }
    });

    effect(() => {
      const failure = this.error();
      if (failure) {
        applyServerFieldErrors(this.form, failure);
      }
    });

    effect(() => {
      const typed = this.typedPayee();
      if (typed.length >= 2) {
        // Two characters before asking: one would match most of the household's payees and tell
        // the person nothing they could use.
        this.payeeTyped.emit(typed);
      }
    });
  }

  protected onPayeeInput(value: string): void {
    this.typedPayee.set(value.trim());
  }

  protected submit(): void {
    if (this.busy()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    // Raw, because a disabled account control is left out of `value` — which on an edit would
    // submit a transaction with no account at all.
    this.submitted.emit(this.form.getRawValue());
  }
}
