import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { CurrencyNamePipe } from '../../core/i18n/format.pipes';
import { currencyCodes } from '../currencies';
import { FieldErrorComponent } from './field-error.component';

/**
 * A currency chosen from a list, never typed — a three-letter code a person has to remember is a
 * validation error waiting to happen. Names come from `Intl` in the active locale.
 *
 * With `emptyLabel` set, an extra first option stands for "no choice, follow the default", whose
 * form value is the empty string.
 */
@Component({
  selector: 'app-currency-field',
  imports: [ReactiveFormsModule, CurrencyNamePipe, FieldErrorComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="field">
      <label [for]="fieldId()">{{ label() }}</label>
      <select
        class="input"
        [id]="fieldId()"
        [formControl]="control()"
        [attr.aria-describedby]="hint() ? fieldId() + '-hint ' + fieldId() + '-error' : fieldId() + '-error'"
        [attr.aria-invalid]="control().invalid && control().touched ? 'true' : null"
      >
        @if (emptyLabel(); as empty) {
          <option value="">{{ empty }}</option>
        }
        @for (code of codes(); track code) {
          <option [value]="code">{{ code }} — {{ code | currencyName }}</option>
        }
      </select>
      @if (hint()) {
        <p class="field-hint" [id]="fieldId() + '-hint'">{{ hint() }}</p>
      }
      <app-field-error [control]="control()" [errorId]="fieldId() + '-error'" />
    </div>
  `,
})
export class CurrencyFieldComponent {
  readonly control = input.required<FormControl<string>>();
  readonly fieldId = input.required<string>();
  /** Already translated by the page that owns the form. */
  readonly label = input.required<string>();
  readonly hint = input('');
  readonly emptyLabel = input<string | null>(null);

  /** The list, with the current value kept in it even if `Intl` does not know the code. */
  protected readonly codes = computed(() => {
    const known = currencyCodes();
    const current = this.control().value.toUpperCase();
    return current && !known.includes(current) ? [current, ...known] : known;
  });
}
