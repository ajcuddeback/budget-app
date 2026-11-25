import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { CurrencyNamePipe, MoneyPipe } from '../../../core/i18n/format.pipes';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { CurrencyGroup } from '../data/account-grouping';
import { Account, accountTypeKey, isKnownToHaveHistory } from '../data/account.models';

/**
 * The accounts, grouped by currency.
 *
 * Presentational: it renders what it is given and reports what the person asked for. Every
 * decision about what is *allowed* — and every request — belongs to the page above it, and to the
 * server above that.
 *
 * Two rules are visible in the template and are not incidental. **Balances are never summed across
 * groups** (ADR-0022): each group carries its own subtotal, computed in its own currency, and
 * there is no total row. And the name and the digits are composed through a translated template
 * rather than concatenated, so a right-to-left locale can put them in the other order (ADR-0023).
 */
@Component({
  selector: 'app-account-list',
  imports: [RouterLink, TranslatePipe, MoneyPipe, CurrencyNamePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './account-list.component.html',
  styleUrl: './account-list.component.css',
})
export class AccountListComponent {
  readonly groups = input.required<readonly CurrencyGroup[]>();
  /** Only with more than one currency present: with one group a subtotal would be a household
   * total, and whether this screen shows one is still an open question in the feature doc. */
  readonly showSubtotals = input(false);
  /** Archived rows: muted, labelled, and offered un-archiving rather than archiving. */
  readonly archived = input(false);
  readonly canWrite = input(false);
  /** The account a request is in flight for, so only its own buttons go quiet. */
  readonly busyId = input<string | null>(null);

  readonly editRequested = output<Account>();
  readonly archiveRequested = output<Account>();
  readonly unarchiveRequested = output<Account>();
  readonly deleteRequested = output<Account>();

  protected readonly typeKey = accountTypeKey;

  /**
   * Delete is offered only for an account with nothing to lose. The server decides again, and
   * answers `409 account-not-empty` naming archiving as the alternative if the UI offered it
   * wrongly — see {@link isKnownToHaveHistory} for why absent information is not a yes.
   */
  protected deletable(account: Account): boolean {
    return !isKnownToHaveHistory(account);
  }
}
