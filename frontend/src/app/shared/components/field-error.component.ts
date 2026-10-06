import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl } from '@angular/forms';
import { merge, of, switchMap } from 'rxjs';
import { MessageKey } from '../../core/i18n/messages';
import { TranslatePipe } from '../../core/i18n/t.pipe';

/**
 * The validation message for one control, once it has been touched or edited.
 *
 * Presentational: it takes the control and renders. `aria-live="polite"` on the container means a
 * screen reader hears the message appear without focus moving.
 */
@Component({
  selector: 'app-field-error',
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div [id]="errorId()" aria-live="polite">
      @if (found(); as shown) {
        <p class="field-error">{{ shown.key | t: shown.params }}</p>
      }
    </div>
  `,
})
export class FieldErrorComponent {
  readonly control = input.required<AbstractControl>();
  /** The id the field's `aria-describedby` points at. */
  readonly errorId = input.required<string>();

  // Every value, status, touched and dirty change on the control, as a signal.
  private readonly changes = toSignal(
    toObservable(this.control).pipe(switchMap((control) => merge(of(null), control.events))),
  );

  protected readonly found = computed<FieldMessage | null>(() => {
    this.changes();
    const control = this.control();
    if (!control.invalid || !(control.touched || control.dirty)) {
      return null;
    }
    const errors = control.errors ?? {};
    if (errors['required']) {
      return message('validation.required');
    }
    if (errors['email']) {
      return message('validation.email');
    }
    if (errors['currency']) {
      return message('validation.currency');
    }
    if (errors['minlength']) {
      return message('validation.minLength', { min: Number(errors['minlength'].requiredLength) });
    }
    if (errors['maxlength']) {
      return message('validation.maxLength', { max: Number(errors['maxlength'].requiredLength) });
    }
    if (errors['maxBytes']) {
      return message('validation.maxBytes', { max: Number(errors['maxBytes'].max) });
    }
    return message('validation.server');
  });
}

interface FieldMessage {
  key: MessageKey;
  params: Record<string, number>;
}

function message(key: MessageKey, params: Record<string, number> = {}): FieldMessage {
  return { key, params };
}
