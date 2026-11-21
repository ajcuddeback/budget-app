import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { DateOnlyPipe, MoneyPipe } from '../../../core/i18n/format.pipes';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { Transaction, transactionStatusKey, transferGroupIdOf } from '../data/transaction.models';

/**
 * The ledger.
 *
 * Presentational: it renders what it is given and reports what the person asked for.
 *
 * Three rules are visible in the template and are not incidental:
 *
 * - **An outflow is marked by more than colour.** Negative amounts get a class *and* the sign
 *   stays in the formatted figure, because colour alone fails for anybody who cannot distinguish
 *   it and for a printed page.
 * - **Amounts are never summed here.** The page total comes from the server and is `null` when a
 *   page mixes currencies (ADR-0022); this component has no addition in it at all.
 * - **"Needs a category" is a state, not a blank.** An uncategorised row says so, because it is
 *   the thing a person is meant to act on rather than an absence they should overlook.
 * - **A transfer leg is acted on as a pair, never on its own.** Editing or deleting one half is
 *   refused (`409 transfer-leg-not-separate`), so a transfer row's buttons report
 *   {@link transferEditRequested} and {@link transferDeleteRequested}, which the page takes to the
 *   transfer's own endpoints. It also reads as a transfer rather than as a payee-less spend: "No
 *   payee" on money moved between your own accounts looks like missing data, and it is not.
 */
@Component({
  selector: 'app-transaction-list',
  imports: [TranslatePipe, MoneyPipe, DateOnlyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './transaction-list.component.html',
  styleUrl: './transaction-list.component.css',
})
export class TransactionListComponent {
  readonly transactions = input.required<readonly Transaction[]>();
  readonly canWrite = input(false);
  /** The transaction a request is in flight for, so only its own buttons go quiet. */
  readonly busyId = input<string | null>(null);
  /** With one account in view the account column is noise, so the page can turn it off. */
  readonly showAccount = input(true);

  readonly editRequested = output<Transaction>();
  readonly deleteRequested = output<Transaction>();
  readonly categoriseRequested = output<Transaction>();
  /** Both carry the leg; the page resolves the pair it belongs to. */
  readonly transferEditRequested = output<Transaction>();
  readonly transferDeleteRequested = output<Transaction>();

  protected readonly statusKey = transactionStatusKey;

  /**
   * The transfer this leg belongs to, or `null` when the ledger has not said which.
   *
   * `TransactionResponse` does not carry `transferGroupId` yet, and a transfer cannot be addressed
   * without it — so the row offers no write buttons rather than buttons that would be refused.
   * See `transferGroupIdOf`.
   */
  protected groupId(transaction: Transaction): string | null {
    return transferGroupIdOf(transaction);
  }

  /** Which way this leg went, in words: the sign is the only record of it. */
  protected transferDirectionKey(transaction: Transaction): MessageKey {
    return this.outgoing(transaction) ? 'transactions.transferOut' : 'transactions.transferIn';
  }

  /** An outflow. Read from the sign, which is the only place the direction is recorded. */
  protected outgoing(transaction: Transaction): boolean {
    return transaction.amount.trimStart().startsWith('-');
  }
}
