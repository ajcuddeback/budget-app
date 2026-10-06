import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from '../../../core/api-error';
import { HOUSEHOLD_ROLES, HouseholdRole } from '../../../core/auth.models';
import { DateTimePipe } from '../../../core/i18n/format.pipes';
import { ROLE_DESCRIPTION_KEY, ROLE_KEY } from '../../../core/i18n/role-keys';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ModalDirective } from '../../../shared/components/modal.directive';
import { CreateInvitationRequest, Invitation } from '../data/household.models';

type CopyState = 'idle' | 'copied' | 'manual';

/**
 * Creates an invitation and shows the link to share.
 *
 * **The link is a credential.** Possession of it is all that is needed to join, so the dialog says
 * so, shows it exactly once (the server keeps only a hash and cannot show it again), keeps it in
 * memory only, and lets the owner revoke it. It is never written to storage or the console.
 *
 * Presentational: the page creates and revokes; this renders each state and reports what was asked.
 */
@Component({
  selector: 'app-invite-dialog',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    DateTimePipe,
    ModalDirective,
    FieldErrorComponent,
    ErrorAlertComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './invite-dialog.component.html',
  styleUrl: './invite-dialog.component.css',
})
export class InviteDialogComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  /** Set once the server has made the invitation. */
  readonly invitation = input<Invitation | null>(null);
  /** The absolute link, already built from this browser's origin. */
  readonly link = input<string | null>(null);
  readonly busy = input(false);
  readonly revoked = input(false);
  readonly error = input<ApiError | null>(null);

  readonly created = output<CreateInvitationRequest>();
  readonly revokeRequested = output<void>();
  readonly another = output<void>();
  readonly closed = output<void>();

  protected readonly roles = HOUSEHOLD_ROLES;
  protected readonly roleKey = ROLE_KEY;
  protected readonly roleDescriptionKey = ROLE_DESCRIPTION_KEY;
  protected readonly copyState = signal<CopyState>('idle');
  private readonly linkField = viewChild<ElementRef<HTMLTextAreaElement>>('linkField');

  readonly form = this.fb.group({
    email: ['', [Validators.required, Validators.email, Validators.maxLength(254)]],
    role: ['MEMBER' as HouseholdRole, [Validators.required]],
  });

  protected submit(): void {
    if (this.busy()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { email, role } = this.form.getRawValue();
    this.created.emit({ email: email.trim(), role });
  }

  protected inviteAnother(): void {
    this.form.reset({ email: '', role: 'MEMBER' });
    this.copyState.set('idle');
    this.another.emit();
  }

  /**
   * Copies the link. The async clipboard API needs a secure context, and a self-hosted instance on
   * a home network is often plain `http://`, so the fallback matters: select the text and ask the
   * browser to copy it, and failing that leave it selected for the person to copy themselves.
   */
  protected async copy(): Promise<void> {
    const field = this.linkField()?.nativeElement;
    const link = this.link();
    if (!field || !link) {
      return;
    }
    field.focus();
    field.select();
    try {
      if (navigator.clipboard) {
        await navigator.clipboard.writeText(link);
        this.copyState.set('copied');
        return;
      }
    } catch {
      // Fall through to the legacy path.
    }
    this.copyState.set(document.execCommand?.('copy') ? 'copied' : 'manual');
  }

  protected selectLink(event: Event): void {
    (event.target as HTMLTextAreaElement).select();
  }
}
