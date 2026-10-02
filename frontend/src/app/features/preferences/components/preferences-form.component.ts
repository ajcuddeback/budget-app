import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Membership } from '../../../core/auth.models';
import { LocaleNamePipe } from '../../../core/i18n/format.pipes';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { CurrencyFieldComponent } from '../../../shared/components/currency-field.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { OwnPreferences } from '../../household/data/household.models';
import { LOCALE_CHOICES } from '../data/locales';

/**
 * A member's own display currency and language. These change what *they* see and never what is
 * recorded (ADR-0022, ADR-0023): two people in one household can read the same data in two
 * currencies and two languages.
 *
 * An empty choice means "follow the default" and is sent as `null`.
 */
@Component({
  selector: 'app-preferences-form',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    LocaleNamePipe,
    CurrencyFieldComponent,
    FieldErrorComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './preferences-form.component.html',
})
export class PreferencesFormComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly membership = input.required<Membership>();
  readonly busy = input(false);
  readonly saved = output<OwnPreferences>();

  readonly form = this.fb.group({
    displayCurrency: ['', [Validators.pattern(/^([A-Za-z]{3})?$/)]],
    locale: ['', [Validators.maxLength(35)]],
  });

  protected readonly locales = computed(() => {
    const current = this.membership().locale;
    return current && !LOCALE_CHOICES.includes(current) ? [current, ...LOCALE_CHOICES] : LOCALE_CHOICES;
  });

  constructor() {
    effect(() => {
      const { displayCurrency, locale } = this.membership();
      this.form.reset({ displayCurrency: displayCurrency ?? '', locale: locale ?? '' });
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
    const { displayCurrency, locale } = this.form.getRawValue();
    this.saved.emit({
      displayCurrency: displayCurrency ? displayCurrency.toUpperCase() : null,
      locale: locale || null,
    });
  }
}
