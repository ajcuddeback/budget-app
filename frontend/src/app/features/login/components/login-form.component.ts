import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { LoginRequest } from '../../../core/auth.models';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';

/**
 * Email and password. Deliberately no password-length check: a login is not the place to enforce a
 * policy, and a short password refused differently from a wrong one would tell an attacker their
 * guess was too short to be anybody's.
 */
@Component({
  selector: 'app-login-form',
  imports: [ReactiveFormsModule, TranslatePipe, FieldErrorComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './login-form.component.html',
})
export class LoginFormComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly busy = input(false);
  readonly submitted = output<LoginRequest>();

  readonly form = this.fb.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    password: ['', [Validators.required]],
  });

  /** Called by the page after a failed attempt: the password is never left sitting in the field. */
  clearPassword(): void {
    this.form.controls.password.reset('');
  }

  protected submit(): void {
    if (this.busy()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { email, password } = this.form.getRawValue();
    this.submitted.emit({ email: email.trim(), password });
  }
}
