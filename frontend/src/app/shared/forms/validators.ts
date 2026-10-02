import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';
import { ApiError } from '../../core/api-error';

/**
 * The server's password ceiling is BCrypt's 72 *bytes*, not characters — an emoji is four. The
 * client mirrors it only so the person finds out before submitting; the server still decides.
 */
export function maxBytes(max: number): ValidatorFn {
  const encoder = new TextEncoder();
  return (control: AbstractControl): ValidationErrors | null => {
    const value: unknown = control.value;
    return typeof value === 'string' && encoder.encode(value).length > max ? { maxBytes: { max } } : null;
  };
}

/** Matches the server's `^[A-Za-z]{3}$` for an ISO 4217 code. */
export function currencyCode(control: AbstractControl): ValidationErrors | null {
  const value: unknown = control.value;
  return typeof value === 'string' && /^[A-Za-z]{3}$/.test(value) ? null : { currency: true };
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
