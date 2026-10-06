import { FormControl, FormGroup } from '@angular/forms';
import { ApiError } from '../../core/api-error';
import { applyServerFieldErrors, currencyCode, maxBytes } from './validators';

describe('validators', () => {
  it('counts bytes, not characters, against the password ceiling', () => {
    const validator = maxBytes(8);

    expect(validator(new FormControl('12345678'))).toBeNull();
    expect(validator(new FormControl('123456789'))).toEqual({ maxBytes: { max: 8 } });
    // Three characters, nine bytes.
    expect(validator(new FormControl('€€€'))).toEqual({ maxBytes: { max: 8 } });
    expect(validator(new FormControl(null))).toBeNull();
  });

  it('accepts a three-letter currency code and nothing else', () => {
    expect(currencyCode(new FormControl('USD'))).toBeNull();
    expect(currencyCode(new FormControl('usd'))).toBeNull();
    expect(currencyCode(new FormControl('US'))).toEqual({ currency: true });
    expect(currencyCode(new FormControl(''))).toEqual({ currency: true });
    expect(currencyCode(new FormControl(7))).toEqual({ currency: true });
  });

  it('marks the controls the server rejected, and ignores names it does not know', () => {
    const form = new FormGroup({ email: new FormControl('a'), password: new FormControl('b') });

    applyServerFieldErrors(form, new ApiError(400, 'validation-failed', {}, ['email', '__proto__', 'nope']));

    expect(form.controls.email.errors).toEqual({ server: true });
    expect(form.controls.email.touched).toBe(true);
    expect(form.controls.password.errors).toBeNull();
  });
});
