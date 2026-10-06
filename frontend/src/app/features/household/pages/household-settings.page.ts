import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom, forkJoin } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ConfirmDialogComponent } from '../../../shared/components/confirm-dialog.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import { HouseholdFormComponent } from '../components/household-form.component';
import { InviteDialogComponent } from '../components/invite-dialog.component';
import { MemberListComponent, RoleChange } from '../components/member-list.component';
import {
  CreateInvitationRequest,
  Household,
  Invitation,
  Member,
  UpdateHouseholdRequest,
} from '../data/household.models';
import { buildInviteLink } from '../data/invite-link';
import { HouseholdService } from '../data/household.service';

interface PendingRemoval {
  kind: 'remove' | 'leave';
  member: Member;
}

/**
 * The household: its name and currency, who is in it, and how to add someone. Smart — it fetches,
 * coordinates, and holds the state; the components it renders only take inputs and emit outputs.
 *
 * Errors from a write are shown beside the thing that failed and leave the screen as it was: a
 * refused role change does not clear the member list.
 */
@Component({
  selector: 'app-household-settings-page',
  imports: [
    TranslatePipe,
    ErrorAlertComponent,
    NoHouseholdComponent,
    HouseholdFormComponent,
    MemberListComponent,
    InviteDialogComponent,
    ConfirmDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './household-settings.page.html',
  styleUrl: './household-settings.page.css',
})
export class HouseholdSettingsPage {
  private readonly service = inject(HouseholdService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18nService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);
  protected readonly household = signal<Household | null>(null);
  protected readonly members = signal<readonly Member[]>([]);

  protected readonly isOwner = computed(() => this.household()?.role === 'OWNER');
  protected readonly myMembershipId = computed(() => this.auth.membership()?.membershipId ?? '');

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveError = signal<ApiError | null>(null);

  protected readonly membersBusy = signal(false);
  protected readonly membersError = signal<ApiError | null>(null);
  protected readonly pending = signal<PendingRemoval | null>(null);

  protected readonly inviting = signal(false);
  protected readonly invitation = signal<Invitation | null>(null);
  protected readonly invitationRevoked = signal(false);
  protected readonly inviteBusy = signal(false);
  protected readonly inviteError = signal<ApiError | null>(null);
  protected readonly inviteLink = computed(() => {
    const made = this.invitation();
    return made ? buildInviteLink(made.acceptPath, window.location.origin) : null;
  });

  protected readonly confirmText = computed(() => {
    const request = this.pending();
    if (!request) {
      return null;
    }
    return request.kind === 'leave'
      ? {
          heading: this.i18n.t('members.leaveHeading'),
          message: this.i18n.t('members.leaveMessage'),
          action: this.i18n.t('members.leave'),
        }
      : {
          heading: this.i18n.t('members.removeHeading', { name: request.member.displayName }),
          message: this.i18n.t('members.removeMessage', { name: request.member.displayName }),
          action: this.i18n.t('members.remove'),
        };
  });

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    await this.auth.ensureLoaded();
    if (!this.auth.membership()) {
      this.noHousehold.set(true);
      this.loading.set(false);
      return;
    }
    try {
      const [household, members] = await firstValueFrom(
        forkJoin([this.service.current(), this.service.members()]),
      );
      this.household.set(household);
      this.members.set(members);
      this.noHousehold.set(false);
    } catch (thrown) {
      const error = asApiError(thrown);
      // 403 from a household endpoint is a user with no membership — an empty state, not a fault.
      if (error.status === 403) {
        this.noHousehold.set(true);
      } else {
        this.loadError.set(error);
      }
    } finally {
      this.loading.set(false);
    }
  }

  protected async save(request: UpdateHouseholdRequest): Promise<void> {
    this.saving.set(true);
    this.saved.set(false);
    this.saveError.set(null);
    try {
      this.household.set(await firstValueFrom(this.service.update(request)));
      await this.auth.refresh();
      this.saved.set(true);
    } catch (thrown) {
      this.saveError.set(asApiError(thrown));
    } finally {
      this.saving.set(false);
    }
  }

  protected async changeRole(change: RoleChange): Promise<void> {
    this.membersBusy.set(true);
    this.membersError.set(null);
    try {
      const updated = await firstValueFrom(this.service.changeRole(change.member.id, change.role));
      this.members.update((all) => all.map((m) => (m.id === updated.id ? updated : m)));
    } catch (thrown) {
      this.membersError.set(asApiError(thrown));
    } finally {
      this.membersBusy.set(false);
    }
  }

  protected askToRemove(member: Member): void {
    this.pending.set({ kind: 'remove', member });
  }

  protected askToLeave(member: Member): void {
    this.pending.set({ kind: 'leave', member });
  }

  protected cancelPending(): void {
    this.pending.set(null);
  }

  protected async confirmPending(): Promise<void> {
    const request = this.pending();
    if (!request) {
      return;
    }
    this.membersBusy.set(true);
    this.membersError.set(null);
    try {
      await firstValueFrom(this.service.removeMember(request.member.id));
      this.pending.set(null);
      if (request.kind === 'leave') {
        await this.afterLeaving();
      } else {
        this.members.update((all) => all.filter((m) => m.id !== request.member.id));
      }
    } catch (thrown) {
      this.pending.set(null);
      this.membersError.set(asApiError(thrown));
    } finally {
      this.membersBusy.set(false);
    }
  }

  private async afterLeaving(): Promise<void> {
    await this.auth.refresh();
    if (!this.auth.isAuthenticated()) {
      await this.router.navigate(['/login'], { queryParams: { reason: 'expired' } });
      return;
    }
    this.household.set(null);
    this.members.set([]);
    this.noHousehold.set(true);
  }

  protected openInvite(): void {
    this.invitation.set(null);
    this.invitationRevoked.set(false);
    this.inviteError.set(null);
    this.inviting.set(true);
  }

  protected async createInvitation(request: CreateInvitationRequest): Promise<void> {
    this.inviteBusy.set(true);
    this.inviteError.set(null);
    try {
      this.invitation.set(await firstValueFrom(this.service.createInvitation(request)));
    } catch (thrown) {
      this.inviteError.set(asApiError(thrown));
    } finally {
      this.inviteBusy.set(false);
    }
  }

  protected async revokeInvitation(): Promise<void> {
    const made = this.invitation();
    if (!made) {
      return;
    }
    this.inviteBusy.set(true);
    this.inviteError.set(null);
    try {
      await firstValueFrom(this.service.revokeInvitation(made.id));
      this.invitationRevoked.set(true);
    } catch (thrown) {
      this.inviteError.set(asApiError(thrown));
    } finally {
      this.inviteBusy.set(false);
    }
  }

  protected inviteAnother(): void {
    this.invitation.set(null);
    this.invitationRevoked.set(false);
    this.inviteError.set(null);
  }

  /** Closing drops the invitation from memory: the token was shown once and cannot be fetched again. */
  protected closeInvite(): void {
    this.inviting.set(false);
    this.invitation.set(null);
    this.invitationRevoked.set(false);
    this.inviteError.set(null);
  }
}
