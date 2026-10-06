import { ChangeDetectionStrategy, Component, effect, inject, input, output } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { CurrencyFieldComponent } from '../../../shared/components/currency-field.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { currencyCode } from '../../../shared/forms/validators';
import { Household, UpdateHouseholdRequest } from '../data/household.models';

/**
 * Rename the household and set its base currency. Only an owner may save; everyone else sees the
 * values, read-only. Hiding the button is a courtesy — the server refuses a non-owner regardless.
 */
@Component({
  selector: 'app-household-form',
  imports: [ReactiveFormsModule, TranslatePipe, FieldErrorComponent, CurrencyFieldComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './household-form.component.html',
})
export class HouseholdFormComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly household = input.required<Household>();
  readonly canEdit = input(false);
  readonly busy = input(false);
  readonly saved = output<UpdateHouseholdRequest>();

  readonly form = this.fb.group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    baseCurrency: ['USD', [Validators.required, currencyCode]],
  });

  constructor() {
    effect(() => {
      const { name, baseCurrency } = this.household();
      this.form.reset({ name, baseCurrency });
    });
    effect(() => {
      if (this.canEdit()) {
        this.form.enable({ emitEvent: false });
      } else {
        this.form.disable({ emitEvent: false });
      }
    });
  }

  protected submit(): void {
    if (this.busy() || !this.canEdit()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { name, baseCurrency } = this.form.getRawValue();
    this.saved.emit({ name: name.trim(), baseCurrency: baseCurrency.toUpperCase() });
  }
}
