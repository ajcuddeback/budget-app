import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import { ApiError } from '../../core/api-error';
import { isAmount } from '../../core/money';

/**
 * The server's password ceiling is BCrypt's 72 *bytes*, not characters — an emoji is four. The
 * client mirrors it only so the person finds out before submitting; the server still decides.
 */
export function maxBytes(max: number): ValidatorFn {
  const encoder = new TextEncoder();
  return (control: AbstractControl): ValidationErrors | null => {
    const value: unknown = control.value;
    return typeof value === 'string' && encoder.encode(value).length > max
      ? { maxBytes: { max } }
      : null;
  };
}

/** Matches the server's `^[A-Za-z]{3}$` for an ISO 4217 code. */
export function currencyCode(control: AbstractControl): ValidationErrors | null {
  const value: unknown = control.value;
  return typeof value === 'string' && /^[A-Za-z]{3}$/.test(value) ? null : { currency: true };
}

/**
 * Exactly four ASCII digits, or empty — the server's `[0-9]{4}` on an optional field (ADR-0028).
 * `[0-9]` and not `\d`, which in some engines widens to Arabic-Indic digits the server refuses.
 */
export function lastFourDigits(control: AbstractControl): ValidationErrors | null {
  const value: unknown = control.value;
  if (typeof value !== 'string' || value === '') {
    return null;
  }
  return /^[0-9]{4}$/.test(value) ? null : { lastFour: true };
}

/**
 * A signed decimal that fits `NUMERIC(19,4)`, mirroring `AccountRequests.AMOUNT` so the person
 * finds out before submitting. **It never parses the value** (ADR-0006): an amount is a string
 * from the first keystroke to the database, and a validator that read it as a `number` would be
 * the one place the rule was broken.
 */
export function amount(control: AbstractControl): ValidationErrors | null {
  const value: unknown = control.value;
  if (typeof value !== 'string' || value === '') {
    return null;
  }
  return isAmount(value.trim()) ? null : { amount: true };
}

/**
 * An **unsigned** decimal that fits `NUMERIC(19,4)`, mirroring `TransferRequests.AMOUNT`.
 *
 * Where {@link amount} allows a sign because a transaction's direction *is* its sign, the amounts
 * on a transfer are magnitudes: the direction is which account each one belongs to, so a minus here
 * is a mistake worth catching rather than a figure to fold in. It never parses the value (ADR-0006).
 */
export function positiveAmount(control: AbstractControl): ValidationErrors | null {
  const value: unknown = control.value;
  if (typeof value !== 'string' || value === '') {
    return null;
  }
  return /^[0-9]{1,15}(\.[0-9]{1,4})?$/.test(value.trim()) ? null : { positiveAmount: true };
}

/**
 * Marks the controls the server rejected. Client validation mirrors the server's rules for quick
 * feedback and never replaces them, so the server can still say no to something the form let
 * through — and when it does, the field it named should say so.
 */
export function applyServerFieldErrors(
  group: { controls: Record<string, AbstractControl> },
  error: ApiError,
): void {
  for (const name of error.fields) {
    const control = Object.hasOwn(group.controls, name) ? group.controls[name] : undefined;
    if (control) {
      control.setErrors({ ...control.errors, server: true });
      control.markAsTouched();
    }
  }
}
