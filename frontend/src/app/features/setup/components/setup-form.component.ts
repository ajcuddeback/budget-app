import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { CurrencyFieldComponent } from '../../../shared/components/currency-field.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { currencyCode, maxBytes } from '../../../shared/forms/validators';
import { FirstUserRequest } from '../../../core/setup.models';

const MINIMUM_PASSWORD_LENGTH = 12;
const MAXIMUM_PASSWORD_BYTES = 72;

/** The create-first-account form. Presentational: values out, nothing fetched. */
@Component({
  selector: 'app-setup-form',
  imports: [ReactiveFormsModule, TranslatePipe, FieldErrorComponent, CurrencyFieldComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './setup-form.component.html',
})
export class SetupFormComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly busy = input(false);
  readonly submitted = output<FirstUserRequest>();

  readonly minimumLength = MINIMUM_PASSWORD_LENGTH;
  readonly form = this.fb.group({
    displayName: ['', [Validators.required, Validators.maxLength(100)]],
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    password: [
      '',
      [
        Validators.required,
        Validators.minLength(MINIMUM_PASSWORD_LENGTH),
        maxBytes(MAXIMUM_PASSWORD_BYTES),
      ],
    ],
    householdName: ['', [Validators.required, Validators.maxLength(100)]],
    baseCurrency: ['USD', [Validators.required, currencyCode]],
  });

  protected submit(): void {
    if (this.busy()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    this.submitted.emit({
      ...value,
      email: value.email.trim(),
      displayName: value.displayName.trim(),
      householdName: value.householdName.trim(),
    });
  }
}
