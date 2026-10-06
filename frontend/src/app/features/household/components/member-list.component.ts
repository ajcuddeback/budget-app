import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { HOUSEHOLD_ROLES, HouseholdRole } from '../../../core/auth.models';
import { DateOnlyPipe } from '../../../core/i18n/format.pipes';
import { ROLE_KEY } from '../../../core/i18n/role-keys';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { Member } from '../data/household.models';

export interface RoleChange {
  member: Member;
  role: HouseholdRole;
}

/**
 * Who is in the household, and what each of them may do.
 *
 * Presentational: it renders what it is given and reports what the user chose. The controls follow
 * the rules the server enforces — an owner changes anyone's role but their own, an owner removes
 * others, anyone may leave, and the last owner may do neither — but they are a courtesy, and the
 * server refuses what the UI should not have offered.
 */
@Component({
  selector: 'app-member-list',
  imports: [TranslatePipe, DateOnlyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './member-list.component.html',
})
export class MemberListComponent {
  readonly members = input.required<readonly Member[]>();
  /** The caller's membership id. */
  readonly myMembershipId = input.required<string>();
  readonly isOwner = input(false);
  readonly busy = input(false);

  readonly roleChanged = output<RoleChange>();
  readonly removeRequested = output<Member>();
  readonly leaveRequested = output<Member>();

  protected readonly roles = HOUSEHOLD_ROLES;
  protected readonly roleKey = ROLE_KEY;

  private readonly ownerCount = computed(
    () => this.members().filter((member) => member.role === 'OWNER').length,
  );

  /** The only owner has no way out until they hand ownership to someone else. */
  protected readonly isSoleOwner = (member: Member): boolean =>
    member.role === 'OWNER' && this.ownerCount() === 1;

  protected changeRole(member: Member, event: Event): void {
    const role = (event.target as HTMLSelectElement).value;
    if (HOUSEHOLD_ROLES.includes(role as HouseholdRole) && role !== member.role) {
      this.roleChanged.emit({ member, role: role as HouseholdRole });
    }
    // The list is the truth. Put the control back; a successful change re-renders it with the
    // new role, and a refused one leaves it showing what is still the case.
    (event.target as HTMLSelectElement).value = member.role;
  }
}
