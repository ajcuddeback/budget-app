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
import { CurrencyFieldComponent } from '../../../shared/components/currency-field.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import {
  amount as amountValidator,
  applyServerFieldErrors,
  currencyCode,
  lastFourDigits,
} from '../../../shared/forms/validators';
import { AccountFormValue } from '../data/account-patch';
import { ACCOUNT_TYPES, Account, AccountType, accountTypeKey } from '../data/account.models';

/** The server's `CHECK (length(btrim(name)) BETWEEN 1 AND 80)`, mirrored for early feedback. */
const NAME_MAX_LENGTH = 80;

/** One field somebody else changed while this form was open, ready to be read out. */
export interface ConflictingField {
  labelKey: MessageKey;
  /** Already formatted by the page — money through the i18n formatters, never by hand. */
  value: string;
}

/**
 * Add an account, or correct one.
 *
 * Structure follows the invitation dialog: a native `<dialog>` opened modally, so focus is moved
 * in and trapped, the page behind is inert, `Escape` closes it and focus returns to the button
 * that opened it. The heading is the dialog's `aria-labelledby`.
 *
 * Presentational. It validates for the person's benefit — the server validates for everyone's —
 * and emits what they typed. Three things in here are load-bearing rather than cosmetic:
 *
 * - **The opening balance is a text field, never `type="number"`.** A number input hands you a
 *   `number`, and an amount that has been through a double has already lost whatever it lost
 *   (ADR-0006). It leaves here as the string that was typed.
 * - **An empty digits box is not the same as no digits.** This emits `''` either way;
 *   `buildAccountPatch` is what turns that into *cleared* or *absent*, because only it knows what
 *   the account had (ADR-0028).
 * - **A locked currency is disabled with the reason beside it**, not hidden. Someone who cannot
 *   change it should learn why rather than wonder where it went.
 */
@Component({
  selector: 'app-account-dialog',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
    CurrencyFieldComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './account-dialog.component.html',
})
export class AccountDialogComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  /** The account being corrected, or `null` to add one. */
  readonly account = input<Account | null>(null);
  /** The household's base currency: the default for a new account, never a conversion. */
  readonly defaultCurrency = input.required<string>();
  /** The account already has transactions, so its currency can no longer be changed. */
  readonly currencyLocked = input(false);
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);
  /**
   * Somebody else saved first (`409 stale-version`). The page has re-read the account and says
   * what changed; nothing is written until the person chooses which copy wins.
   */
  readonly conflict = input<readonly ConflictingField[] | null>(null);

  readonly submitted = output<AccountFormValue>();
  readonly latestWanted = output<void>();
  readonly overwriteWanted = output<void>();
  readonly closed = output<void>();

  protected readonly types = ACCOUNT_TYPES;
  protected readonly typeKey = accountTypeKey;
  protected readonly editing = computed(() => this.account() !== null);
  protected readonly titleKey = computed<MessageKey>(() =>
    this.editing() ? 'accounts.editTitle' : 'accounts.addTitle',
  );

  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(NAME_MAX_LENGTH)]],
    lastFour: ['', [lastFourDigits]],
    type: ['CHECKING' as AccountType, [Validators.required]],
    currency: ['', [Validators.required, currencyCode]],
    openingBalance: ['', [Validators.required, amountValidator]],
  });

  constructor() {
    effect(() => {
      const account = this.account();
      this.form.reset(
        account
          ? {
              name: account.name,
              lastFour: account.lastFour ?? '',
              type: account.type,
              currency: account.currency,
              // The string the server sent, unparsed. It is corrected by editing the digits.
              openingBalance: account.openingBalance,
            }
          : {
              name: '',
              lastFour: '',
              type: 'CHECKING',
              currency: this.defaultCurrency().toUpperCase(),
              openingBalance: '',
            },
      );
    });

    effect(() => {
      if (this.currencyLocked()) {
        this.form.controls.currency.disable({ emitEvent: false });
      } else {
        this.form.controls.currency.enable({ emitEvent: false });
      }
    });

    effect(() => {
      const failure = this.error();
      if (failure) {
        // The server can refuse something this form allowed — an unknown currency code, a type it
        // does not have. Mark the field it named instead of only showing a sentence.
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
    // Raw, because a locked currency control is disabled and a disabled control is left out of
    // `value` — which would silently submit an account with no currency at all.
    this.submitted.emit(this.form.getRawValue());
  }
}
