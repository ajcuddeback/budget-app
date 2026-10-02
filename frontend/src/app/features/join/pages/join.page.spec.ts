import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth.service';
import { PASSPHRASE, apiError, buttonNamed, fakeAuth, FakeAuth, OWNER, settle, type as typeInto } from '../../../testing/helpers';
import { InvitationService } from '../data/invitation.service';
import { JoinPage } from './join.page';

describe('JoinPage', () => {
  let auth: FakeAuth;
  const accept = vi.fn();

  async function render(signedIn = false) {
    auth = fakeAuth(signedIn ? OWNER : null);
    accept.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: InvitationService, useValue: { accept } },
      ],
    });
    const fixture = TestBed.createComponent(JoinPage);
    fixture.componentRef.setInput('token', 'tok-123');
    await settle(fixture);
    return fixture;
  }

  /**
   * ---------------------------------------------------------------------------------------------
   * THE DISCLOSURE (ADR-0026). These tests guard a SECURITY CONTROL, not copy.
   *
   * Before an invited person's account exists, the screen must say — plainly, in the flow, above
   * the button — that the person running this instance can see everything they record. Somebody
   * who shares their finances with a housemate's server believing otherwise has been misled by
   * us. A change that removes, hides, shortens or buries this wording is a security change and
   * has to be reviewed as one: it should not be possible to make it by accident, and it should
   * not be possible to make it by fixing a test.
   * ---------------------------------------------------------------------------------------------
   */
  describe('the join-time disclosure (ADR-0026)', () => {
    const REQUIRED_WORDS = [
      'The person who runs this instance can see everything you record',
      'every transaction',
      'every balance',
      'every note',
    ];

    function disclosure(root: HTMLElement): HTMLElement {
      const found = root.querySelector<HTMLElement>('#join-disclosure');
      expect(found, 'the disclosure must be on the page').not.toBeNull();
      return found as HTMLElement;
    }

    it('states, in plain words, that the person running the instance can see everything', async () => {
      const fixture = await render();
      const text = disclosure(fixture.nativeElement).textContent ?? '';

      for (const words of REQUIRED_WORDS) {
        expect(text, `the disclosure must say "${words}"`).toContain(words);
      }
    });

    it('is visible: not hidden, not collapsed, not removed from the accessibility tree', async () => {
      const fixture = await render();
      const section = disclosure(fixture.nativeElement);

      for (let node: HTMLElement | null = section; node; node = node.parentElement) {
        expect(node.hasAttribute('hidden'), `${node.tagName} is hidden`).toBe(false);
        expect(node.getAttribute('aria-hidden'), `${node.tagName} is aria-hidden`).not.toBe('true');
        expect(node.style.display, `${node.tagName} is display:none`).not.toBe('none');
        expect(node.style.visibility, `${node.tagName} is invisible`).not.toBe('hidden');
        expect(node.tagName, 'the disclosure must not sit in a collapsed <details>').not.toBe('DETAILS');
        expect(node.tagName).not.toBe('DIALOG');
      }
      expect(section.getAttribute('aria-labelledby')).toBe('join-disclosure-heading');
      expect(section.querySelector('#join-disclosure-heading')?.textContent).toContain('who can see your finances');
    });

    it('is in the flow, not a link to a policy', async () => {
      const fixture = await render();
      const section = disclosure(fixture.nativeElement);

      expect(section.querySelector('a')).toBeNull();
      expect(section.querySelectorAll('p').length).toBeGreaterThanOrEqual(3);
    });

    it('comes before the fields and above the button', async () => {
      const fixture = await render();
      const root = fixture.nativeElement as HTMLElement;
      const section = disclosure(root);
      const button = buttonNamed(root, 'Accept and join');
      const firstField = root.querySelector('input') as HTMLElement;

      expect(section.compareDocumentPosition(button) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(section.compareDocumentPosition(firstField) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('is read out when a screen-reader user reaches the button', async () => {
      const fixture = await render();
      const root = fixture.nativeElement as HTMLElement;
      const button = buttonNamed(root, 'Accept and join');
      const target = root.querySelector(`#${button.getAttribute('aria-describedby')}`);

      expect(target).toBe(disclosure(root));
    });

    it('is still there after a failed attempt, while a request is in flight, and with an error showing', async () => {
      const fixture = await render();
      const root = fixture.nativeElement as HTMLElement;
      accept.mockReturnValue(throwError(() => apiError(429, 'rate-limited', { retryAfterSeconds: 60 })));

      buttonNamed(root, 'Accept and join').click();
      await settle(fixture);

      expect(root.querySelector('[role=alert]')?.textContent).toContain('Too many attempts');
      expect(disclosure(root).textContent).toContain('every transaction');
    });

    it('is not shown on the screens where nothing is being joined yet', async () => {
      // The signed-in screen cannot accept anything, so it must not present a disclosure that
      // implies a join is about to happen — and it must not offer the form behind it.
      const fixture = await render(true);

      expect(fixture.nativeElement.querySelector('#join-disclosure')).toBeNull();
      expect(fixture.nativeElement.querySelector('input')).toBeNull();
    });
  });

  it('asks the server to accept with the token from the link, and shows the new member where to go next', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;
    accept.mockReturnValue(of({ householdId: 'h', householdName: 'Rivera Household', role: 'MEMBER', userCreated: true }));
    typeInto(root, '#join-name', 'Riley Okafor');
    typeInto(root, '#join-password', 'a long fixture passphrase');

    buttonNamed(root, 'Accept and join').click();
    await settle(fixture);

    expect(accept).toHaveBeenCalledWith('tok-123', { displayName: 'Riley Okafor', password: PASSPHRASE });
    expect(root.textContent).toContain('Welcome to Rivera Household');
    expect(root.textContent).toContain('Your account has been created.');
    expect(root.querySelector('a[href="/login?reason=joined"]')).not.toBeNull();
    expect(root.textContent).not.toContain('tok-123');
  });

  it('lets an existing user accept with both fields empty', async () => {
    const fixture = await render();
    accept.mockReturnValue(of({ householdId: 'h', householdName: 'Rivera Household', role: 'VIEWER', userCreated: false }));

    buttonNamed(fixture.nativeElement, 'Accept and join').click();
    await settle(fixture);

    expect(accept).toHaveBeenCalledWith('tok-123', {});
    expect(fixture.nativeElement.textContent).not.toContain('Your account has been created.');
    expect(fixture.nativeElement.textContent).toContain('Viewer');
  });

  it('refuses a name without a password, and a password without a name, before asking the server', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;

    typeInto(root, '#join-name', 'Riley');
    buttonNamed(root, 'Accept and join').click();
    await settle(fixture);
    expect(root.textContent).toContain('This field is required.');

    typeInto(root, '#join-name', '');
    typeInto(root, '#join-password', 'a long fixture passphrase');
    buttonNamed(root, 'Accept and join').click();
    await settle(fixture);

    expect(accept).not.toHaveBeenCalled();
    expect(root.querySelectorAll('.field-error').length).toBeGreaterThan(0);
  });

  it('validates the password before sending it: too short, and too many bytes', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;
    typeInto(root, '#join-name', 'Riley');

    typeInto(root, '#join-password', 'short');
    await settle(fixture);
    expect(root.textContent).toContain('Use at least 12 characters.');

    typeInto(root, '#join-password', '€'.repeat(30));
    await settle(fixture);
    expect(root.textContent).toContain('at most 72 bytes');
  });

  it('says which fields a brand-new account needs when the server refuses a bare acceptance', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;
    accept.mockReturnValue(throwError(() => apiError(400, 'validation-failed', { fields: ['displayName', 'password'] }, ['displayName', 'password'])));

    buttonNamed(root, 'Accept and join').click();
    await settle(fixture);

    expect(root.querySelectorAll('.field-error').length).toBe(2);
  });

  it('gives one generic answer for an expired, used or revoked link, and takes the form away', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;
    accept.mockReturnValue(throwError(() => apiError(404, 'invitation-unusable')));

    buttonNamed(root, 'Accept and join').click();
    await settle(fixture);

    expect(root.querySelector('[role=alert]')?.textContent).toContain('may have expired, been used already, or been cancelled');
    expect(root.querySelector('form')).toBeNull();
    expect(root.querySelector('a[href="/login"]')).not.toBeNull();
  });

  it('shows the form but a signed-in notice when the server refuses because somebody is signed in', async () => {
    const fixture = await render();
    const root = fixture.nativeElement as HTMLElement;
    accept.mockReturnValue(throwError(() => apiError(409, 'invitation-requires-sign-out')));

    buttonNamed(root, 'Accept and join').click();
    await settle(fixture);

    expect(root.textContent).toContain('You are already signed in');
    expect(root.querySelector('form')).toBeNull();
  });

  it('asks a signed-in visitor to sign out, stays on the page, and then offers the form', async () => {
    const fixture = await render(true);
    const root = fixture.nativeElement as HTMLElement;
    expect(root.textContent).toContain('You are already signed in');

    buttonNamed(root, 'Sign out').click();
    auth.user.set(null);
    await settle(fixture);

    expect(auth.logout).toHaveBeenCalledWith({ redirect: false });
    expect(root.querySelector('#join-disclosure')).not.toBeNull();
  });

  it('shows nothing but a loading line until it knows whether somebody is signed in', async () => {
    const fixture = await render();
    auth.loaded.set(false);
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('#join-disclosure')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Loading');
  });

  it('shows other failures beside the form and keeps the form', async () => {
    const fixture = await render();
    accept.mockReturnValue(throwError(() => apiError(500, 'internal-error')));

    buttonNamed(fixture.nativeElement, 'Accept and join').click();
    await settle(fixture);

    expect(fixture.nativeElement.querySelector('[role=alert]')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('form')).not.toBeNull();
  });
});
