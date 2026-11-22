import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { DateOnlyPipe } from '../../../core/i18n/format.pipes';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { AdminAccount, accountStatusKey } from '../data/admin.models';

/**
 * Who can sign in to this instance, their status, and when they joined. Presentational and
 * read-only: suspending or deleting an account is out of scope for this slice (admin-console.md),
 * and what happens to a person's financial records when their account goes is a real decision
 * nobody has made yet.
 *
 * The administrator is marked, because exactly one account is and it is useful to see which
 * (ADR-0029). It is not a household role — `OWNER` governs the money, this governs the instance.
 */
@Component({
  selector: 'app-admin-account-list',
  imports: [TranslatePipe, DateOnlyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './account-list.component.html',
})
export class AdminAccountListComponent {
  readonly accounts = input.required<readonly AdminAccount[]>();
  /** The signed-in administrator's own user id, so their own row says so. */
  readonly myUserId = input('');

  protected readonly statusKey = accountStatusKey;
}
