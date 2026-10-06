import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { ApiError } from '../../core/api-error';
import { ErrorTextPipe } from '../../core/i18n/error-text';
import { TranslatePipe } from '../../core/i18n/t.pipe';

/**
 * A failed request, in words. The sentence is chosen by the error's stable code, never taken from
 * the response body (ADR-0023). `role="alert"` so it is announced the moment it appears.
 *
 * The correlation id is the one thread back to the server's log for this failure, and is shown
 * only for the failures the user cannot act on.
 */
@Component({
  selector: 'app-error-alert',
  imports: [ErrorTextPipe, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (error(); as failure) {
      <div class="alert" role="alert">
        <p>{{ failure | errorText }}</p>
        @if (failure.correlationId && failure.status >= 500) {
          <p class="small muted">{{ 'error.reference' | t: { id: failure.correlationId } }}</p>
        }
      </div>
    }
  `,
})
export class ErrorAlertComponent {
  readonly error = input<ApiError | null>(null);
}
