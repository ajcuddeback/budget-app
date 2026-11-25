import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';
import { ApiError } from '../../../core/api-error';
import { DateOnlyPipe, MoneyPipe } from '../../../core/i18n/format.pipes';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { Account } from '../../accounts/data/account.models';
import { Transaction } from '../data/transaction.models';

/** What the person narrowed the list to, using the ledger's own account and date filters. */
export interface PairFilter {
  accountId: string;
  from: string;
  to: string;
}

/**
 * "This is a transfer…": pick the row in another account that this one is the other half of.
 *
 * Presentational: the page fetches the rows and does the pairing. Three rules are visible here
 * (file-import.md rule 12):
 *
 * - **Nothing is chosen for you.** The rows are in ledger order, as the server sent them, and no
 *   radio is checked until the person checks one. There is no ranking, no "best match" and no
 *   preselection: a guess that was wrong would hide a real expense inside a transfer.
 * - **The loss is said before the button.** Both rows lose their category and payee and stop
 *   counting as income or spending; the sentence sits above the confirm button, always.
 * - **Only rows that could be the other half are listed**: not this row, not one in the same
 *   account, not one that is already a transfer leg. That is display; the server checks every rule
 *   again and refuses with a code the alert renders.
 */
@Component({
  selector: 'app-pair-dialog',
  imports: [TranslatePipe, MoneyPipe, DateOnlyPipe, ModalDirective, ErrorAlertComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './pair-dialog.component.html',
  styleUrl: './pair-dialog.component.css',
})
export class PairDialogComponent {
  /** The row the person started from. */
  readonly row = input.required<Transaction>();
  /** The household's accounts, for the account filter. The row's own is left out. */
  readonly accounts = input.required<readonly Account[]>();
  /** One page of the ledger under the current filter, as the server sent it. */
  readonly candidates = input.required<readonly Transaction[]>();
  readonly filter = input.required<PairFilter>();
  readonly loading = input(false);
  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);
  readonly hasPrevious = input(false);
  readonly hasNext = input(false);

  readonly filterChanged = output<PairFilter>();
  readonly pageRequested = output<1 | -1>();
  readonly paired = output<Transaction>();
  readonly closed = output<void>();

  /** Nothing until the person picks. Never set from code. */
  protected readonly chosenId = signal<string | null>(null);

  protected readonly otherAccounts = computed(() =>
    this.accounts().filter((account) => account.id !== this.row().accountId),
  );

  protected readonly choices = computed(() => {
    const row = this.row();
    return this.candidates().filter(
      (candidate) =>
        candidate.id !== row.id && candidate.accountId !== row.accountId && !candidate.transfer,
    );
  });

  protected readonly chosen = computed(
    () => this.choices().find((candidate) => candidate.id === this.chosenId()) ?? null,
  );

  protected change(patch: Partial<PairFilter>): void {
    this.chosenId.set(null);
    this.filterChanged.emit({ ...this.filter(), ...patch });
  }

  protected page(step: 1 | -1): void {
    this.chosenId.set(null);
    this.pageRequested.emit(step);
  }

  protected confirm(): void {
    const chosen = this.chosen();
    if (chosen) {
      this.paired.emit(chosen);
    }
  }
}
