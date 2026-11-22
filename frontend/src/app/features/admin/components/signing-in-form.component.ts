import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from '../../../core/api-error';
import { DateTimePipe } from '../../../core/i18n/format.pipes';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { issuerUrl } from '../../../shared/forms/validators';
import { InstanceSettings } from '../data/admin.models';
import { SigningInFormValue } from '../data/settings-patch';

const ISSUER_MAX_LENGTH = 500;
const CLIENT_ID_MAX_LENGTH = 200;
const SECRET_MAX_LENGTH = 500;

/**
 * How this instance lets people sign in. Presentational: it takes the settings, reports what the
 * administrator chose, and injects no service.
 *
 * Four things here are rules rather than layout:
 *
 * - **The client secret input is empty on load, always.** The API never returns the stored secret
 *   to anybody, so there is nothing to put in it; rendering a row of dots would claim to be
 *   showing a value this screen does not have. {@link InstanceSettings.oidcClientSecretSet} is
 *   said in words beside the field instead.
 * - **When password login cannot be turned off, no switch is offered.** The database refuses it
 *   until an `OWNER` has completed a sign-in through the provider (security-model.md), so the rule
 *   is stated beside the control it governs rather than answered with a `409` after the click.
 * - **Turning the provider off switches password login back on**, which the server does so that
 *   nobody is left with no way in. It is said here, before the change is confirmed.
 * - **Opening registration is said in plain words, in the flow**, not in a tooltip: anybody who
 *   can reach the instance over the network can then create themselves an account.
 */
@Component({
  selector: 'app-signing-in-form',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    DateTimePipe,
    ErrorAlertComponent,
    FieldErrorComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './signing-in-form.component.html',
  styleUrl: './signing-in-form.component.css',
})
export class SigningInFormComponent {
  private readonly fb = inject(NonNullableFormBuilder);

  readonly settings = input.required<InstanceSettings>();
  /**
   * Bumped by the page when the form should be filled from {@link settings} again — on load and
   * after a save. **Not whenever `settings` changes**: a conflict makes the page re-read the
   * server's copy, and that must not wipe what somebody has typed.
   */
  readonly resetKey = input(0);
  /**
   * Bumped by the page when the administrator cancelled the confirmation for turning the provider
   * off.
   *
   * **Only the provider switch goes back**, not the whole form. Cancel means "do not turn the
   * provider off"; it does not mean "throw away the client id I just corrected". Reusing {@link
   * resetKey} here would do the second thing, which is why this is its own input.
   */
  readonly restoreOidcKey = input(0);

  readonly busy = input(false);
  readonly error = input<ApiError | null>(null);
  /** A conflict is waiting for a decision. Nothing is sent until the page resolves it. */
  readonly blocked = input(false);

  readonly submitted = output<SigningInFormValue>();

  readonly form = this.fb.group({
    registrationOpen: [false],
    passwordLoginEnabled: [true],
    oidcEnabled: [false],
    oidcIssuerUri: ['', [Validators.maxLength(ISSUER_MAX_LENGTH), issuerUrl]],
    oidcClientId: ['', [Validators.maxLength(CLIENT_ID_MAX_LENGTH)]],
    oidcClientSecret: ['', [Validators.maxLength(SECRET_MAX_LENGTH)]],
    oidcProvisioningEnabled: [false],
  });

  private readonly oidcChecked = signal(false);
  private readonly registrationChecked = signal(false);
  private readonly passwordChecked = signal(true);

  protected readonly oidcOn = this.oidcChecked.asReadonly();
  protected readonly secretSet = computed(() => this.settings().oidcClientSecretSet);

  /**
   * Whether the password-login switch is offered at all.
   *
   * Offered when the lockout does not apply — and also when password login is somehow already off,
   * so that turning it back on is never unreachable from here.
   */
  protected readonly offerPasswordSwitch = computed(
    () => !this.settings().passwordLoginLocked || !this.settings().passwordLoginEnabled,
  );

  /** The provider is on and about to be turned off, which brings password login back. */
  protected readonly turningOidcOff = computed(() => this.settings().oidcEnabled && !this.oidcOn());

  /**
   * Password login is on and about to be turned off — the one change on this screen that can
   * leave somebody unable to reach their own records, so the way back in is named here.
   */
  protected readonly turningPasswordOff = computed(
    () => this.settings().passwordLoginEnabled && !this.passwordChecked(),
  );

  /** Registration is closed and about to be opened. */
  protected readonly openingRegistration = computed(
    () => !this.settings().registrationOpen && this.registrationChecked(),
  );

  constructor() {
    this.form.controls.oidcEnabled.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((enabled) => this.oidcChecked.set(enabled));
    this.form.controls.registrationOpen.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((open) => this.registrationChecked.set(open));
    this.form.controls.passwordLoginEnabled.valueChanges
      .pipe(takeUntilDestroyed())
      .subscribe((enabled) => this.passwordChecked.set(enabled));

    // The provider's three values only mean anything together, so they are required together —
    // and the secret only when there is not one stored already. The server says the same
    // (`AdminService.applyOidc`); this is so the administrator finds out before submitting.
    effect(() => this.applyOidcRules(this.oidcOn(), this.secretSet()));

    // Cancelling the confirmation re-ticks the provider box. Without this the form sat showing
    // the provider as off while the server still had it on — the issuer and client id fields
    // vanish with the tick, so an administrator who cancelled appeared to have lost their
    // configuration, and the next Save re-opened the confirmation they had just declined.
    effect(() => {
      this.restoreOidcKey();
      const settings = untracked(this.settings);
      untracked(() => this.form.controls.oidcEnabled.setValue(settings.oidcEnabled));
    });

    effect(() => {
      this.resetKey();
      const settings = untracked(this.settings);
      this.form.reset({
        registrationOpen: settings.registrationOpen,
        passwordLoginEnabled: settings.passwordLoginEnabled,
        oidcEnabled: settings.oidcEnabled,
        oidcIssuerUri: settings.oidcIssuerUri ?? '',
        oidcClientId: settings.oidcClientId ?? '',
        // Empty, every time. There is no stored value to show and no response that carries one.
        oidcClientSecret: '',
        oidcProvisioningEnabled: settings.oidcProvisioningEnabled,
      });
    });
  }

  protected submit(): void {
    if (this.busy() || this.blocked()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitted.emit(this.form.getRawValue());
  }

  private applyOidcRules(enabled: boolean, secretSet: boolean): void {
    const { oidcIssuerUri, oidcClientId, oidcClientSecret } = this.form.controls;
    oidcIssuerUri.setValidators(
      enabled
        ? [Validators.required, Validators.maxLength(ISSUER_MAX_LENGTH), issuerUrl]
        : [Validators.maxLength(ISSUER_MAX_LENGTH), issuerUrl],
    );
    oidcClientId.setValidators(
      enabled
        ? [Validators.required, Validators.maxLength(CLIENT_ID_MAX_LENGTH)]
        : [Validators.maxLength(CLIENT_ID_MAX_LENGTH)],
    );
    oidcClientSecret.setValidators(
      enabled && !secretSet
        ? [Validators.required, Validators.maxLength(SECRET_MAX_LENGTH)]
        : [Validators.maxLength(SECRET_MAX_LENGTH)],
    );
    for (const control of [oidcIssuerUri, oidcClientId, oidcClientSecret]) {
      control.updateValueAndValidity({ emitEvent: false });
    }
  }
}
