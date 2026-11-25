import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  NonNullableFormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { ApiError } from '../../../core/api-error';
import { DateOnlyPipe, RatePipe } from '../../../core/i18n/format.pipes';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { applyServerFieldErrors, positiveAmount } from '../../../shared/forms/validators';
import { Account } from '../../accounts/data/account.models';
import { TransferFormValue, isCrossCurrency, transferFormValue } from '../data/transfer-patch';
import { Transfer } from '../data/transfer.models';

/** The server's `CHECK (length(btrim(note)) BETWEEN 1 AND 500)`, mirrored for early feedback. */
const NOTE_MAX_LENGTH = 500;

/**
 * Move money between two of your own accounts, or correct a transfer you already made.
 *
 * Presentational: it takes the accounts and the transfer, and emits what somebody typed.
 *
 * Six things here are the feature's rules rather than UI taste:
 *
 * - **There is no category picker and no payee field, anywhere.** A transfer is not spending and
 *   the other side is not a third party; the API refuses both rather than ignoring them (ADR-0033),
 *   so offering either would be offering to be told no.
 * - **Both amounts are `type="text"` with `inputmode="decimal"`.** A number input hands back a
 *   `number`, and an amount that has been through a double has already lost whatever it lost
 *   (ADR-0006). The keypad still opens on a phone.
 * - **The second amount appears only when the two accounts are in different currencies**, because
 *   only then is what arrived a different number from what left — and only the household knows it.
 *   Within one currency the field is not on screen and is never sent: the server refuses both
 *   mistakes with a `400`, so the form cannot be casual about which one it is making.
 * - **The same account on both sides is caught as it is chosen**, not on submit. It is two rows
 *   that cancel out and mean nothing.
 * - **An edit has no account pickers at all.** Moving a transfer between accounts would change up
 *   to four balances and, across currencies, what its figures mean; the sheet names the two
 *   accounts and says to delete and re-create instead, rather than leaving somebody hunting for a
 *   picker that is not there.
 * - **The implied rate is labelled as derived.** It is `received / sent`, shown for information,
 *   and it is not the rate the bank gave — that had a fee inside it (ADR-0022).
 */
@Component({
  selector: 'app-transfer-dialog',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    RatePipe,
    DateOnlyPipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './transfer-dialog.component.html',
})
export class TransferDialogComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  /** The transfer being corrected, or `null` to make one. */
  readonly transfer = input<Transfer | null>(null);
  /**
   * The accounts money may move between. **Archived ones are excluded by the page**: either side
   * being archived is a `409 account-archived`, so they are not offered rather than refused.
   */
  readonly accounts = input.required<readonly Account[]>();
  /** Today, as `YYYY-MM-DD`, supplied by the page so this component has no clock of its own. */
  readonly today = input.required<string>();
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);

  readonly submitted = output<TransferFormValue>();
  readonly closed = output<void>();

  protected readonly editing = computed(() => this.transfer() !== null);

  /**
   * The two legs' dates and notes, when they differ — a pair made from two statement rows. The
   * sheet edits one date and one note for both, so it says what each leg holds now rather than
   * showing only the outgoing one and letting a save silently align them.
   */
  protected readonly legsDiffer = computed(() => {
    const existing = this.transfer();
    if (!existing) {
      return null;
    }
    const dates = existing.fromDate !== existing.toDate;
    const notes = (existing.fromNote ?? '') !== (existing.toNote ?? '');
    return dates || notes ? { dates, notes, transfer: existing } : null;
  });
  protected readonly titleKey = computed<MessageKey>(() =>
    this.editing() ? 'transfers.editTitle' : 'transfers.addTitle',
  );

  readonly form = this.fb.group({
    fromAccountId: ['', [Validators.required]],
    toAccountId: ['', [Validators.required, differentFromSource]],
    date: ['', [Validators.required]],
    amount: ['', [Validators.required, positiveAmount]],
    /** Required only across currencies — kept in step by {@link applyCrossCurrency}. */
    amountReceived: [''],
    note: ['', [Validators.maxLength(NOTE_MAX_LENGTH)]],
  });

  private readonly formValue = toSignal(this.form.valueChanges, {
    initialValue: this.form.getRawValue() as Partial<TransferFormValue>,
  });

  /** The currency the first figure is in: the account the money leaves. */
  protected readonly fromCurrency = computed(
    () => this.transfer()?.sentCurrency ?? this.currencyOf(this.formValue().fromAccountId ?? ''),
  );

  /** The currency the second figure is in: the account the money arrives in. */
  protected readonly toCurrency = computed(
    () => this.transfer()?.receivedCurrency ?? this.currencyOf(this.formValue().toAccountId ?? ''),
  );

  /**
   * Whether the household has to supply both figures.
   *
   * On an edit this is the server's own answer, not a re-derivation: the pair's accounts are fixed,
   * so `crossCurrency` on the transfer is the fact, and an account list that happened not to
   * include an archived account could not contradict it.
   */
  protected readonly crossCurrency = computed(() => {
    const existing = this.transfer();
    if (existing) {
      return existing.crossCurrency;
    }
    const value = this.formValue();
    return isCrossCurrency(this.accounts(), value.fromAccountId ?? '', value.toAccountId ?? '');
  });

  constructor() {
    // The source account decides whether the destination is still a different account, and
    // whether a second figure is needed. Neither re-checks itself when the *other* picker moves,
    // so the one that moved says so.
    this.form.controls.fromAccountId.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.form.controls.toAccountId.updateValueAndValidity());

    effect(() => this.applyCrossCurrency(this.crossCurrency()));

    effect(() => {
      const transfer = this.transfer();
      this.form.reset(
        transfer
          ? transferFormValue(transfer)
          : {
              fromAccountId: '',
              toAccountId: '',
              date: this.today(),
              amount: '',
              amountReceived: '',
              note: '',
            },
        { emitEvent: false },
      );
      // An existing transfer's accounts are fixed, and the PATCH body has no room for them.
      // Disabled as well as unrendered, so a stray value cannot reach the server or the
      // validators.
      const accountControls = [this.form.controls.fromAccountId, this.form.controls.toAccountId];
      for (const control of accountControls) {
        if (transfer) {
          control.disable({ emitEvent: false });
        } else {
          control.enable({ emitEvent: false });
        }
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
    // Raw, because the disabled account controls are left out of `value` — and an edit still wants
    // to know which accounts it was about.
    this.submitted.emit(this.form.getRawValue());
  }

  /** The second figure is required exactly across currencies, and cleared when it is not needed. */
  private applyCrossCurrency(crossCurrency: boolean): void {
    const received = this.form.controls.amountReceived;
    if (crossCurrency) {
      received.setValidators([Validators.required, positiveAmount]);
    } else {
      received.setValidators([]);
      // Cleared, not merely hidden: within one currency the server refuses a second figure, and a
      // value left behind by a changed picker would turn a valid transfer into a 400.
      received.setValue('', { emitEvent: false });
    }
    received.updateValueAndValidity({ emitEvent: false });
  }

  private currencyOf(accountId: string): string {
    return this.accounts().find((account) => account.id === accountId)?.currency ?? '';
  }
}

/**
 * The two accounts must differ — caught here rather than on submit.
 *
 * Reads its sibling through `control.parent`, which is why {@link TransferDialogComponent} asks it
 * to re-run when the source account changes.
 */
function differentFromSource(control: AbstractControl): ValidationErrors | null {
  const source: unknown = control.parent?.get('fromAccountId')?.value;
  if (typeof source !== 'string' || source === '' || control.value !== source) {
    return null;
  }
  return { sameAccount: true };
}
