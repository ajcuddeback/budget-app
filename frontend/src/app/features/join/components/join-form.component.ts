import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from '../../../core/api-error';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { maxBytes } from '../../../shared/forms/validators';
import { AcceptInvitationRequest } from '../data/join.models';

const MINIMUM_PASSWORD_LENGTH = 12;
const MAXIMUM_PASSWORD_BYTES = 72;

/**
 * The invitation acceptance form, **with the disclosure that goes with it**.
 *
 * The disclosure is in this template, ahead of the fields and the button, and is body text in the
 * flow — not a link, not collapsed, not small print. That is the control (ADR-0026): somebody
 * sharing their finances with a server somebody else runs must be told so, in plain words, before
 * their account exists. It is a security requirement and not copy; `join.page.spec.ts` asserts it
 * is present, visible, and above the button, so that removing or burying it fails the build.
 *
 * Presentational: values out, nothing fetched.
 */
@Component({
  selector: 'app-join-form',
  imports: [ReactiveFormsModule, TranslatePipe, FieldErrorComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './join-form.component.html',
})
export class JoinFormComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly busy = input(false);
  readonly submitted = output<AcceptInvitationRequest>();

  readonly minimumLength = MINIMUM_PASSWORD_LENGTH;

  readonly form = this.fb.group({
    displayName: ['', [Validators.maxLength(100)]],
    password: [
      '',
      [Validators.minLength(MINIMUM_PASSWORD_LENGTH), maxBytes(MAXIMUM_PASSWORD_BYTES)],
    ],
  });

  /**
   * The server said this invitation is for a brand-new account and a field was empty. Say which
   * fields, rather than a generic failure.
   */
  markMissing(error: ApiError): void {
    for (const name of error.fields) {
      if (name === 'displayName' || name === 'password') {
        const control = this.form.controls[name];
        if (control.value.trim() === '') {
          control.setErrors({ required: true });
          control.markAsTouched();
        }
      }
    }
  }

  protected submit(): void {
    if (this.busy()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { displayName, password } = this.form.getRawValue();
    const name = displayName.trim();
    // Name and password go together or not at all. Neither means "I already have an account
    // here", which the server accepts for an existing user; one without the other is a mistake.
    if (Boolean(name) !== Boolean(password)) {
      const missing = name ? this.form.controls.password : this.form.controls.displayName;
      missing.setErrors({ required: true });
      missing.markAsTouched();
      return;
    }
    this.submitted.emit(name ? { displayName: name, password } : {});
  }
}
