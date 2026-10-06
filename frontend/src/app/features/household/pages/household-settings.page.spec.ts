import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { Subject, of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth.service';
import { Membership } from '../../../core/auth.models';
import { OWNER, OWNER_MEMBERSHIP, apiError, buttonNamed, fakeAuth, FakeAuth, settle, type as typeInto } from '../../../testing/helpers';
import { Household, Invitation, Member } from '../data/household.models';
import { HouseholdService } from '../data/household.service';
import { HouseholdSettingsPage } from './household-settings.page';

const HOUSEHOLD: Household = { id: 'h', name: 'Rivera Household', baseCurrency: 'USD', role: 'OWNER' };
const ALEX: Member = { id: 'm1', userId: 'u1', email: 'alex@example.test', displayName: 'Alex', role: 'OWNER', joinedAt: '2026-06-01T09:00:00Z', displayCurrency: null, locale: null };
const SAM: Member = { ...ALEX, id: 'm2', userId: 'u2', email: 'sam@example.test', displayName: 'Sam', role: 'MEMBER' };
const INVITATION: Invitation = { id: 'i1', email: 'riley@example.test', role: 'MEMBER', token: 'T', acceptPath: '/join/T', expiresAt: '2026-10-09T09:00:00Z' };

describe('HouseholdSettingsPage', () => {
  let auth: FakeAuth;
  let router: Router;
  const service = {
    current: vi.fn(),
    members: vi.fn(),
    update: vi.fn(),
    changeRole: vi.fn(),
    removeMember: vi.fn(),
    createInvitation: vi.fn(),
    revokeInvitation: vi.fn(),
  };

  async function render(options: { membership?: Membership | null; role?: Membership['role']; me?: string } = {}) {
    const membership = options.membership === undefined ? { ...OWNER_MEMBERSHIP, role: options.role ?? 'OWNER', membershipId: options.me ?? 'm1' } : options.membership;
    auth = fakeAuth({ ...OWNER, household: membership });
    for (const fn of Object.values(service)) fn.mockReset();
    service.current.mockReturnValue(of({ ...HOUSEHOLD, role: membership?.role ?? 'OWNER' }));
    service.members.mockReturnValue(of([ALEX, SAM]));
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: HouseholdService, useValue: service },
      ],
    });
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(HouseholdSettingsPage);
    await settle(fixture);
    return fixture;
  }

  const text = (f: { nativeElement: HTMLElement }) => f.nativeElement.textContent ?? '';

  it('shows the household and its members', async () => {
    const fixture = await render();

    expect((fixture.nativeElement.querySelector('#household-name') as HTMLInputElement).value).toBe('Rivera Household');
    expect(fixture.nativeElement.querySelectorAll('.rows > li')).toHaveLength(2);
    expect(buttonNamed(fixture.nativeElement, 'Invite someone')).toBeTruthy();
  });

  it('shows an invitation prompt, and asks the server nothing, for a user with no membership', async () => {
    const fixture = await render({ membership: null });

    expect(text(fixture)).toContain('You are not part of a household yet');
    expect(service.current).not.toHaveBeenCalled();
  });

  it('shows the same prompt when the server says 403, rather than an error', async () => {
    auth = fakeAuth();
    service.current.mockReturnValue(throwError(() => apiError(403, 'not-a-member')));
    service.members.mockReturnValue(throwError(() => apiError(403, 'not-a-member')));
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: AuthService, useValue: auth }, { provide: HouseholdService, useValue: service }],
    });
    const fixture = TestBed.createComponent(HouseholdSettingsPage);
    await settle(fixture);

    expect(text(fixture)).toContain('You are not part of a household yet');
    expect(fixture.nativeElement.querySelector('[role=alert]')).toBeNull();
  });

  it('shows a failure to load, with a way to retry', async () => {
    auth = fakeAuth();
    service.current.mockReturnValueOnce(throwError(() => apiError(500, 'internal-error')));
    service.members.mockReturnValue(of([ALEX]));
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: AuthService, useValue: auth }, { provide: HouseholdService, useValue: service }],
    });
    const fixture = TestBed.createComponent(HouseholdSettingsPage);
    await settle(fixture);
    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('went wrong');

    service.current.mockReturnValue(of(HOUSEHOLD));
    buttonNamed(fixture.nativeElement, 'Try again').click();
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('#household-name')).not.toBeNull();
  });

  it('offers a member no invitations and no way to change the household', async () => {
    const fixture = await render({ role: 'MEMBER' });

    expect(() => buttonNamed(fixture.nativeElement, 'Invite someone')).toThrow();
    expect((fixture.nativeElement.querySelector('#household-name') as HTMLInputElement).disabled).toBe(true);
  });

  it('saves a rename, refreshes the profile, and confirms', async () => {
    const fixture = await render();
    service.update.mockReturnValue(of({ ...HOUSEHOLD, name: 'The Riveras' }));
    typeInto(fixture.nativeElement, '#household-name', 'The Riveras');

    buttonNamed(fixture.nativeElement, 'Save').click();
    await settle(fixture);

    expect(service.update).toHaveBeenCalledWith({ name: 'The Riveras', baseCurrency: 'USD' });
    expect(auth.refresh).toHaveBeenCalled();
    expect(text(fixture)).toContain('Household saved.');
  });

  it('explains a refused rename and keeps what was typed', async () => {
    const fixture = await render();
    service.update.mockReturnValue(throwError(() => apiError(403, 'owner-only')));
    typeInto(fixture.nativeElement, '#household-name', 'The Riveras');

    buttonNamed(fixture.nativeElement, 'Save').click();
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('Only an owner');
    expect(text(fixture)).not.toContain('Household saved.');
  });

  it('changes a role and shows the new one', async () => {
    const fixture = await render();
    service.changeRole.mockReturnValue(of({ ...SAM, role: 'VIEWER' }));
    const select = fixture.nativeElement.querySelector('#role-m2') as HTMLSelectElement;

    select.value = 'VIEWER';
    select.dispatchEvent(new Event('change'));
    await settle(fixture);

    expect(service.changeRole).toHaveBeenCalledWith('m2', 'VIEWER');
    expect((fixture.nativeElement.querySelector('#role-m2') as HTMLSelectElement).value).toBe('VIEWER');
  });

  it('explains a refused role change and leaves the list alone', async () => {
    const fixture = await render();
    service.changeRole.mockReturnValue(throwError(() => apiError(409, 'last-owner')));
    const select = fixture.nativeElement.querySelector('#role-m2') as HTMLSelectElement;

    select.value = 'VIEWER';
    select.dispatchEvent(new Event('change'));
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('at least one owner');
    expect((fixture.nativeElement.querySelector('#role-m2') as HTMLSelectElement).value).toBe('MEMBER');
  });

  it('asks before removing, and removes only on confirmation', async () => {
    const fixture = await render();
    service.removeMember.mockReturnValue(of(undefined));

    buttonNamed(fixture.nativeElement, 'Remove').click();
    await settle(fixture);
    expect(fixture.nativeElement.querySelector('dialog').textContent).toContain('Remove Sam?');
    expect(service.removeMember).not.toHaveBeenCalled();

    fixture.nativeElement.querySelector('dialog .btn-danger').click();
    await settle(fixture);

    expect(service.removeMember).toHaveBeenCalledWith('m2');
    expect(fixture.nativeElement.querySelectorAll('.rows > li')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
  });

  it('cancelling a removal changes nothing', async () => {
    const fixture = await render();
    buttonNamed(fixture.nativeElement, 'Remove').click();
    await settle(fixture);

    buttonNamed(fixture.nativeElement.querySelector('dialog'), 'Cancel').click();
    await settle(fixture);

    expect(service.removeMember).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
  });

  it('explains a refused removal', async () => {
    const fixture = await render();
    service.removeMember.mockReturnValue(throwError(() => apiError(409, 'last-owner')));
    buttonNamed(fixture.nativeElement, 'Remove').click();
    await settle(fixture);

    fixture.nativeElement.querySelector('dialog .btn-danger').click();
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('at least one owner');
    expect(fixture.nativeElement.querySelectorAll('.rows > li')).toHaveLength(2);
  });

  describe('leaving', () => {
    it('lets a member leave and then shows the invitation prompt', async () => {
      const fixture = await render({ role: 'MEMBER', me: 'm2' });
      service.removeMember.mockReturnValue(of(undefined));
      auth.refresh.mockImplementation(async () => auth.user.set({ ...OWNER, household: null }));
      buttonNamed(fixture.nativeElement, 'Leave').click();
      await settle(fixture);

      expect(fixture.nativeElement.querySelector('dialog').textContent).toContain('Leave this household?');
      fixture.nativeElement.querySelector('dialog .btn-danger').click();
      await settle(fixture);

      expect(service.removeMember).toHaveBeenCalledWith('m2');
      expect(text(fixture)).toContain('You are not part of a household yet');
    });

    it('goes to sign-in if leaving ended the session', async () => {
      const fixture = await render({ role: 'MEMBER', me: 'm2' });
      service.removeMember.mockReturnValue(of(undefined));
      auth.refresh.mockImplementation(async () => auth.user.set(null));
      buttonNamed(fixture.nativeElement, 'Leave').click();
      await settle(fixture);

      fixture.nativeElement.querySelector('dialog .btn-danger').click();
      await settle(fixture);

      expect(router.navigate).toHaveBeenCalledWith(['/login'], { queryParams: { reason: 'expired' } });
    });
  });

  describe('inviting', () => {
    async function openAndCreate(fixture: Awaited<ReturnType<typeof render>>) {
      buttonNamed(fixture.nativeElement, 'Invite someone').click();
      await settle(fixture);
      typeInto(fixture.nativeElement, '#invite-email', 'riley@example.test');
      buttonNamed(fixture.nativeElement.querySelector('dialog'), 'Create link').click();
      await settle(fixture);
    }

    it('builds the link from this browser\'s origin and shows it', async () => {
      const fixture = await render();
      service.createInvitation.mockReturnValue(of(INVITATION));

      await openAndCreate(fixture);

      expect(service.createInvitation).toHaveBeenCalledWith({ email: 'riley@example.test', role: 'MEMBER' });
      expect((fixture.nativeElement.querySelector('#invite-link') as HTMLTextAreaElement).value).toBe(`${window.location.origin}/join/T`);
    });

    it('revokes by the id from the creation response', async () => {
      const fixture = await render();
      service.createInvitation.mockReturnValue(of(INVITATION));
      service.revokeInvitation.mockReturnValue(of(undefined));
      await openAndCreate(fixture);

      buttonNamed(fixture.nativeElement.querySelector('dialog'), 'Revoke link').click();
      await settle(fixture);

      expect(service.revokeInvitation).toHaveBeenCalledWith('i1');
      expect(fixture.nativeElement.querySelector('dialog').textContent).toContain('has been revoked');
    });

    it('shows a failed revocation and a failed creation inside the dialog', async () => {
      const fixture = await render();
      service.createInvitation.mockReturnValueOnce(throwError(() => apiError(409, 'invitation-refused')));
      await openAndCreate(fixture);
      expect(fixture.nativeElement.querySelector('dialog [role=alert]').textContent).toContain('could not be created');

      service.createInvitation.mockReturnValue(of(INVITATION));
      service.revokeInvitation.mockReturnValue(throwError(() => apiError(404, 'not-found')));
      buttonNamed(fixture.nativeElement.querySelector('dialog'), 'Create link').click();
      await settle(fixture);
      buttonNamed(fixture.nativeElement.querySelector('dialog'), 'Revoke link').click();
      await settle(fixture);

      expect(fixture.nativeElement.querySelector('dialog [role=alert]').textContent).toContain('could not be found');
    });

    it('forgets the invitation when the dialog closes: the token cannot be shown twice', async () => {
      const fixture = await render();
      service.createInvitation.mockReturnValue(of(INVITATION));
      await openAndCreate(fixture);

      buttonNamed(fixture.nativeElement.querySelector('dialog'), 'Done').click();
      await settle(fixture);
      expect(fixture.nativeElement.querySelector('dialog')).toBeNull();
      expect(fixture.nativeElement.innerHTML).not.toContain('/join/T');

      buttonNamed(fixture.nativeElement, 'Invite someone').click();
      await settle(fixture);
      expect(fixture.nativeElement.querySelector('#invite-link')).toBeNull();
      expect(fixture.nativeElement.querySelector('#invite-email')).not.toBeNull();
    });

    it('goes back to the form for another invitation', async () => {
      const fixture = await render();
      service.createInvitation.mockReturnValue(of(INVITATION));
      await openAndCreate(fixture);

      buttonNamed(fixture.nativeElement.querySelector('dialog'), 'Invite another person').click();
      await settle(fixture);

      expect(fixture.nativeElement.querySelector('#invite-email')).not.toBeNull();
    });

    it('does not let the dialog be submitted twice while waiting', async () => {
      const fixture = await render();
      service.createInvitation.mockReturnValue(new Subject());
      await openAndCreate(fixture);

      expect(buttonNamed(fixture.nativeElement.querySelector('dialog'), 'Creating link').disabled).toBe(true);
    });
  });
});
