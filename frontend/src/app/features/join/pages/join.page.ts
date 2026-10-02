import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { ROLE_KEY } from '../../../core/i18n/role-keys';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { JoinFormComponent } from '../components/join-form.component';
import { InvitationService } from '../data/invitation.service';
import { AcceptedInvitation, AcceptInvitationRequest } from '../data/join.models';

/**
 * Following an invitation link. **Public** — the person has no account yet — and what authorises
 * them is the token in the URL, which is a credential: it is read from the route, sent once in the
 * request path, and never stored, logged or shown.
 *
 * The page cannot preview the invitation (there is no endpoint for it, and an answer that said
 * whether a link is live would be an oracle), so it cannot know whether the invited address
 * already has an account. The form therefore asks for a name and password and says that an
 * existing user leaves them empty.
 *
 * The disclosure that the person running this instance can see everything is in the form
 * component, above the button (ADR-0026).
 */
@Component({
  selector: 'app-join-page',
  imports: [RouterLink, TranslatePipe, ErrorAlertComponent, JoinFormComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './join.page.html',
})
export class JoinPage {
  private readonly invitations = inject(InvitationService);
  protected readonly auth = inject(AuthService);
  private readonly form = viewChild(JoinFormComponent);

  /** From the route, via component input binding. */
  readonly token = input.required<string>();

  protected readonly busy = signal(false);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly joined = signal<AcceptedInvitation | null>(null);
  /** The server refused because somebody is signed in, even if this page did not know it. */
  protected readonly signedInConflict = signal(false);
  protected readonly unusable = signal(false);
  protected readonly signingOut = signal(false);

  protected readonly mustSignOut = computed(
    () => this.signedInConflict() || this.auth.isAuthenticated(),
  );
  protected readonly roleKey = ROLE_KEY;

  constructor() {
    // Whether anybody is signed in decides which of two screens to show, so ask first.
    void this.auth.ensureLoaded();
  }

  protected async accept(request: AcceptInvitationRequest): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      this.joined.set(await firstValueFrom(this.invitations.accept(this.token(), request)));
    } catch (thrown) {
      this.handleFailure(asApiError(thrown));
    } finally {
      this.busy.set(false);
    }
  }

  private handleFailure(failure: ApiError): void {
    switch (failure.code) {
      case 'invitation-unusable':
        // One answer for expired, used, revoked and never-existed — and one screen for it.
        this.unusable.set(true);
        this.error.set(failure);
        break;
      case 'invitation-requires-sign-out':
        this.signedInConflict.set(true);
        break;
      case 'validation-failed':
        this.error.set(failure);
        this.form()?.markMissing(failure);
        break;
      default:
        this.error.set(failure);
    }
  }

  protected async signOut(): Promise<void> {
    this.signingOut.set(true);
    try {
      // Stay on this page: the link is the point of being here.
      await this.auth.logout({ redirect: false });
      this.signedInConflict.set(false);
    } finally {
      this.signingOut.set(false);
    }
  }
}
