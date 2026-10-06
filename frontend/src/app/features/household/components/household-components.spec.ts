import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Member } from '../data/household.models';
import { apiError, buttonNamed, settle, type as typeInto } from '../../../testing/helpers';
import { HouseholdFormComponent } from './household-form.component';
import { InviteDialogComponent } from './invite-dialog.component';
import { MemberListComponent } from './member-list.component';

const providers = [provideHttpClient(), provideHttpClientTesting()];

const ALEX: Member = { id: 'm1', userId: 'u1', email: 'alex@example.test', displayName: 'Alex', role: 'OWNER', joinedAt: '2026-06-01T09:00:00Z', displayCurrency: null, locale: null };
const SAM: Member = { ...ALEX, id: 'm2', userId: 'u2', email: 'sam@example.test', displayName: 'Sam', role: 'MEMBER' };

describe('HouseholdFormComponent', () => {
  async function render(canEdit: boolean) {
    TestBed.configureTestingModule({ providers });
    const fixture = TestBed.createComponent(HouseholdFormComponent);
    fixture.componentRef.setInput('household', { id: 'h', name: 'Rivera Household', baseCurrency: 'USD', role: canEdit ? 'OWNER' : 'MEMBER' });
    fixture.componentRef.setInput('canEdit', canEdit);
    await settle(fixture);
    return fixture;
  }

  it('shows the current values and lets an owner save a trimmed, upper-cased change', async () => {
    const fixture = await render(true);
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    expect((fixture.nativeElement.querySelector('#household-name') as HTMLInputElement).value).toBe('Rivera Household');

    typeInto(fixture.nativeElement, '#household-name', '  The Riveras ');
    buttonNamed(fixture.nativeElement, 'Save').click();

    expect(saved).toHaveBeenCalledWith({ name: 'The Riveras', baseCurrency: 'USD' });
  });

  it('will not save a blank name', async () => {
    const fixture = await render(true);
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);

    typeInto(fixture.nativeElement, '#household-name', '');
    buttonNamed(fixture.nativeElement, 'Save').click();
    await settle(fixture);

    expect(saved).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent).toContain('This field is required.');
  });

  it('is read-only for anyone who is not an owner, with no save button', async () => {
    const fixture = await render(false);

    expect((fixture.nativeElement.querySelector('#household-name') as HTMLInputElement).disabled).toBe(true);
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Only an owner can change these.');
    fixture.componentInstance.form.enable();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
  });

  it('ignores a second submit while saving', async () => {
    const fixture = await render(true);
    fixture.componentRef.setInput('busy', true);
    const saved = vi.fn();
    fixture.componentInstance.saved.subscribe(saved);
    await settle(fixture);

    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));

    expect(saved).not.toHaveBeenCalled();
    expect(buttonNamed(fixture.nativeElement, 'Saving').disabled).toBe(true);
  });
});

describe('MemberListComponent', () => {
  async function render(options: { isOwner: boolean; me: string; members?: Member[] }) {
    TestBed.configureTestingModule({ providers });
    const fixture = TestBed.createComponent(MemberListComponent);
    fixture.componentRef.setInput('members', options.members ?? [ALEX, SAM]);
    fixture.componentRef.setInput('myMembershipId', options.me);
    fixture.componentRef.setInput('isOwner', options.isOwner);
    await settle(fixture);
    return fixture;
  }

  it('lists everyone with their role and marks the viewer', async () => {
    const fixture = await render({ isOwner: false, me: 'm2' });
    const rows = fixture.nativeElement.querySelectorAll('li');

    expect(rows).toHaveLength(2);
    expect(rows[1].textContent).toContain('You');
    expect(rows[0].textContent).toContain('alex@example.test');
    expect(rows[0].textContent).toContain('Owner');
  });

  it('gives an owner a labelled role control for others, none for themselves, and a remove button', async () => {
    const fixture = await render({ isOwner: true, me: 'm1' });
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('select#role-m1')).toBeNull();
    expect(root.querySelector('label[for=role-m2]')?.textContent).toContain('Role for Sam');
    expect(buttonNamed(root, 'Remove').textContent).toContain('Sam');
  });

  it('reports a role change, then puts the control back to the truth', async () => {
    const fixture = await render({ isOwner: true, me: 'm1' });
    const changed = vi.fn();
    fixture.componentInstance.roleChanged.subscribe(changed);
    const select = fixture.nativeElement.querySelector('#role-m2') as HTMLSelectElement;

    select.value = 'VIEWER';
    select.dispatchEvent(new Event('change'));

    expect(changed).toHaveBeenCalledWith({ member: SAM, role: 'VIEWER' });
    expect(select.value).toBe('MEMBER');
  });

  it('does not report a change to the same role, or to something that is not a role', async () => {
    const fixture = await render({ isOwner: true, me: 'm1' });
    const changed = vi.fn();
    fixture.componentInstance.roleChanged.subscribe(changed);
    const select = fixture.nativeElement.querySelector('#role-m2') as HTMLSelectElement;

    select.dispatchEvent(new Event('change'));
    select.add(new Option('Root', 'ROOT'));
    select.value = 'ROOT';
    select.dispatchEvent(new Event('change'));

    expect(changed).not.toHaveBeenCalled();
  });

  it('reports removal of another member', async () => {
    const fixture = await render({ isOwner: true, me: 'm1' });
    const removed = vi.fn();
    fixture.componentInstance.removeRequested.subscribe(removed);

    buttonNamed(fixture.nativeElement, 'Remove').click();

    expect(removed).toHaveBeenCalledWith(SAM);
  });

  it('lets a non-owner leave, and shows no controls over anyone else', async () => {
    const fixture = await render({ isOwner: false, me: 'm2' });
    const left = vi.fn();
    fixture.componentInstance.leaveRequested.subscribe(left);

    expect(fixture.nativeElement.querySelector('select')).toBeNull();
    expect(Array.from(fixture.nativeElement.querySelectorAll('button')).map((b) => (b as HTMLElement).textContent?.trim())).toEqual(['Leave']);
    buttonNamed(fixture.nativeElement, 'Leave').click();

    expect(left).toHaveBeenCalledWith(SAM);
  });

  it('tells the only owner why they cannot leave, and offers no button', async () => {
    const fixture = await render({ isOwner: true, me: 'm1' });

    expect(fixture.nativeElement.textContent).toContain('You are the only owner.');
    expect(fixture.nativeElement.textContent).not.toContain('Leave');
  });

  it('lets one of two owners leave', async () => {
    const fixture = await render({ isOwner: true, me: 'm1', members: [ALEX, { ...SAM, role: 'OWNER' }] });

    expect(buttonNamed(fixture.nativeElement, 'Leave')).toBeTruthy();
  });

  it('disables every control while a change is in flight', async () => {
    const fixture = await render({ isOwner: true, me: 'm1' });
    fixture.componentRef.setInput('busy', true);
    await settle(fixture);

    expect((fixture.nativeElement.querySelector('select') as HTMLSelectElement).disabled).toBe(true);
    expect(buttonNamed(fixture.nativeElement, 'Remove').disabled).toBe(true);
  });
});

describe('InviteDialogComponent', () => {
  const INVITATION = { id: 'i1', email: 'riley@example.test', role: 'VIEWER' as const, token: 'T', acceptPath: '/join/T', expiresAt: '2026-10-09T09:00:00Z' };

  async function render(inputs: Record<string, unknown> = {}) {
    TestBed.configureTestingModule({ providers });
    const fixture = TestBed.createComponent(InviteDialogComponent);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    await settle(fixture);
    return fixture;
  }

  it('asks for an email and a role, with every role described', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;

    expect(root.querySelector('dialog')?.getAttribute('aria-labelledby')).toBe('invite-title');
    expect(root.querySelectorAll('input[type=radio]')).toHaveLength(3);
    expect(root.textContent).toContain('can see the household\'s money, but not change it');
  });

  it('refuses a bad email, and otherwise emits a trimmed request with the chosen role', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;
    const created = vi.fn();
    fixture.componentInstance.created.subscribe(created);

    typeInto(root, '#invite-email', 'not an email');
    buttonNamed(root, 'Create link').click();
    await settle(fixture);
    expect(created).not.toHaveBeenCalled();
    expect(root.textContent).toContain('Enter a valid email address.');

    typeInto(root, '#invite-email', ' riley@example.test ');
    (root.querySelectorAll('input[type=radio]')[2] as HTMLInputElement).click();
    buttonNamed(root, 'Create link').click();

    expect(created).toHaveBeenCalledWith({ email: 'riley@example.test', role: 'VIEWER' });
  });

  it('shows the link once made, with a credential warning, and lets it be revoked', async () => {
    const fixture = await render({ invitation: INVITATION, link: 'https://budget.home.test/join/T' });
    const root = fixture.nativeElement as HTMLElement;
    const revoke = vi.fn();
    fixture.componentInstance.revokeRequested.subscribe(revoke);

    expect((root.querySelector('#invite-link') as HTMLTextAreaElement).value).toBe('https://budget.home.test/join/T');
    expect(root.textContent).toContain('Treat this link like a password');
    expect(root.textContent).toContain('Viewer');
    buttonNamed(root, 'Revoke link').click();

    expect(revoke).toHaveBeenCalled();
  });

  it('copies the link with the clipboard API and says so', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    try {
      const fixture = await render({ invitation: INVITATION, link: 'https://budget.home.test/join/T' });

      buttonNamed(fixture.nativeElement, 'Copy link').click();
      await settle(fixture);

      expect(writeText).toHaveBeenCalledWith('https://budget.home.test/join/T');
      expect(fixture.nativeElement.textContent).toContain('Link copied.');
    } finally {
      Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
    }
  });

  it('falls back to selecting the text when the page is not a secure context', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
    document.execCommand = vi.fn().mockReturnValue(false);
    const fixture = await render({ invitation: INVITATION, link: 'https://budget.home.test/join/T' });

    buttonNamed(fixture.nativeElement, 'Copy link').click();
    await settle(fixture);

    expect(fixture.nativeElement.textContent).toContain('The link is selected. Copy it with your keyboard.');
  });

  it('uses the legacy copy command when it works, and survives a clipboard that refuses', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) }, configurable: true });
    document.execCommand = vi.fn().mockReturnValue(true);
    try {
      const fixture = await render({ invitation: INVITATION, link: 'https://budget.home.test/join/T' });

      buttonNamed(fixture.nativeElement, 'Copy link').click();
      await settle(fixture);

      expect(fixture.nativeElement.textContent).toContain('Link copied.');
    } finally {
      Object.defineProperty(navigator, 'clipboard', { value: undefined, configurable: true });
    }
  });

  it('says so, rather than showing an empty box, when a safe link could not be built', async () => {
    const fixture = await render({ invitation: INVITATION, link: null });

    expect(fixture.nativeElement.querySelector('#invite-link')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('The link could not be built.');
  });

  it('confirms a revocation and offers no copy button after it', async () => {
    const fixture = await render({ invitation: INVITATION, link: 'https://x/join/T', revoked: true });

    expect(fixture.nativeElement.textContent).toContain('This link has been revoked');
    expect(fixture.nativeElement.querySelector('#invite-link')).toBeNull();
    expect(() => buttonNamed(fixture.nativeElement, 'Revoke link')).toThrow();
  });

  it('resets for another invitation, and reports closing', async () => {
    const fixture = await render({ invitation: INVITATION, link: 'https://x/join/T' });
    const another = vi.fn();
    const closed = vi.fn();
    fixture.componentInstance.another.subscribe(another);
    fixture.componentInstance.closed.subscribe(closed);

    buttonNamed(fixture.nativeElement, 'Invite another person').click();
    buttonNamed(fixture.nativeElement, 'Done').click();

    expect(another).toHaveBeenCalled();
    expect(closed).toHaveBeenCalled();
  });

  it('shows a failure inside the dialog, and cancel closes it', async () => {
    const fixture = await render({ error: apiError(409, 'invitation-refused') });
    const closed = vi.fn();
    fixture.componentInstance.closed.subscribe(closed);

    expect(fixture.nativeElement.querySelector('[role=alert]').textContent).toContain('could not be created');
    buttonNamed(fixture.nativeElement, 'Cancel').click();
    expect(closed).toHaveBeenCalled();
  });

  it('selects the whole link when it takes focus', async () => {
    const fixture = await render({ invitation: INVITATION, link: 'https://x/join/T' });
    const box = fixture.nativeElement.querySelector('#invite-link') as HTMLTextAreaElement;
    const select = vi.spyOn(box, 'select');

    box.dispatchEvent(new Event('focus'));

    expect(select).toHaveBeenCalled();
  });

  it('ignores a second submit while the request is in flight', async () => {
    const fixture = await render({ busy: true });
    const created = vi.fn();
    fixture.componentInstance.created.subscribe(created);
    typeInto(fixture.nativeElement, '#invite-email', 'riley@example.test');

    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));

    expect(created).not.toHaveBeenCalled();
  });
});
