import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { TranslatePipe } from '../../core/i18n/t.pipe';
import { ModalDirective } from './modal.directive';

/**
 * "Are you sure?" for an action that cannot be taken back. Presentational: it asks and reports.
 * The safe choice (cancel) is first in focus order and the dangerous one is outlined, not filled.
 */
@Component({
  selector: 'app-confirm-dialog',
  imports: [TranslatePipe, ModalDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog class="dialog" appModal aria-labelledby="confirm-title" (dismissed)="cancelled.emit()">
      <h2 class="dialog-title" id="confirm-title">{{ heading() }}</h2>
      <p>{{ message() }}</p>
      <div class="dialog-actions">
        <button type="button" class="btn btn-secondary" (click)="cancelled.emit()">
          {{ 'common.cancel' | t }}
        </button>
        <button type="button" class="btn btn-danger" [disabled]="busy()" (click)="confirmed.emit()">
          {{ confirmLabel() }}
        </button>
      </div>
    </dialog>
  `,
})
export class ConfirmDialogComponent {
  readonly heading = input.required<string>();
  readonly message = input.required<string>();
  readonly confirmLabel = input.required<string>();
  readonly busy = input(false);

  /**
   * Whether confirming does something that cannot be undone.
   *
   * Archiving is reversible — the dialog itself says so — and it used to wear the same
   * rust-outlined danger treatment as a permanent delete. Spending the warning colour on a
   * reversible action is how a person learns to click through it, and then it is not there when
   * it matters. Defaults to the safe reading: ask for `destructive` explicitly.
   */
  readonly tone = input<'destructive' | 'ordinary'>('ordinary');

  readonly confirmed = output<void>();
  readonly cancelled = output<void>();
}
