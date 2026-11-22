import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { firstValueFrom, forkJoin } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ConfirmDialogComponent } from '../../../shared/components/confirm-dialog.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { AdminAccountListComponent } from '../components/account-list.component';
import { InstanceFactsComponent } from '../components/instance-facts.component';
import { SigningInFormComponent } from '../components/signing-in-form.component';
import { AdminAccount, InstanceInfo, InstanceSettings } from '../data/admin.models';
import { AdminService } from '../data/admin.service';
import {
  SettingsField,
  SigningInFormValue,
  buildSettingsPatch,
  differingSettings,
  settingsPatchIsEmpty,
} from '../data/settings-patch';

/** Field names for the conflict notice. Names only — one of these fields is a secret. */
const FIELD_LABEL_KEY: Readonly<Record<SettingsField, MessageKey>> = {
  registrationOpen: 'admin.field.registrationOpen',
  passwordLoginEnabled: 'admin.field.passwordLoginEnabled',
  oidcEnabled: 'admin.field.oidcEnabled',
  oidcIssuerUri: 'admin.field.oidcIssuerUri',
  oidcClientId: 'admin.field.oidcClientId',
  oidcClientSecretSet: 'admin.field.oidcClientSecret',
  oidcProvisioningEnabled: 'admin.field.oidcProvisioningEnabled',
};

/**
 * The operator's screen: the instance, how people sign in, and who can.
 *
 * Smart — it fetches, coordinates and holds the state; the three components it renders take inputs
 * and emit outputs. Administrator-only, and that is the server's decision: the masthead hides the
 * link and a guard refuses the route, but `403 not-instance-admin` is what actually holds
 * (ADR-0029).
 *
 * Two behaviours are worth knowing before changing anything here:
 *
 * - **A `409 stale-version` re-reads the server's copy without touching the form.** The person
 *   keeps what they typed, is told which settings somebody else changed, and chooses. Nothing is
 *   written until they do, and "save mine anyway" sends the diff against the *refreshed* copy —
 *   never against the one that lost the race, which would silently undo the other change.
 * - **Turning the provider off is confirmed first**, because the server also switches password
 *   login back on. That is the right behaviour and a surprise if nobody said so.
 */
@Component({
  selector: 'app-admin-page',
  imports: [
    TranslatePipe,
    ErrorAlertComponent,
    ConfirmDialogComponent,
    InstanceFactsComponent,
    SigningInFormComponent,
    AdminAccountListComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './admin.page.html',
  styleUrl: './admin.page.css',
})
export class AdminPage {
  private readonly service = inject(AdminService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);

  protected readonly instance = signal<InstanceInfo | null>(null);
  /** The server's copy: what the facts are read from, and what a change is diffed against. */
  protected readonly settings = signal<InstanceSettings | null>(null);
  protected readonly accounts = signal<readonly AdminAccount[]>([]);

  protected readonly myUserId = computed(() => this.auth.user()?.id ?? '');

  /**
   * Bumped when the form should be filled from {@link settings} again. Deliberately not every
   * time `settings` changes: a conflict re-reads the server's copy, and that must not discard
   * what somebody typed.
   */
  protected readonly formRevision = signal(0);

  protected readonly saving = signal(false);
  protected readonly savedKey = signal<MessageKey | null>(null);
  protected readonly saveError = signal<ApiError | null>(null);

  /** Which settings somebody else changed, by name, while this page was open. */
  protected readonly conflict = signal<readonly MessageKey[] | null>(null);
  protected readonly inConflict = computed(() => this.conflict() !== null);

  /** Held back for the confirmation: turning the provider off brings password login with it. */
  protected readonly pendingOidcOff = signal<SigningInFormValue | null>(null);
  protected readonly oidcOffText = computed(() => ({
    heading: this.i18n.t('admin.oidcOffHeading'),
    message: this.i18n.t('admin.oidcOffMessage'),
    action: this.i18n.t('admin.oidcOffConfirm'),
  }));

  /** The last thing submitted, so "save mine anyway" can send it against the refreshed copy. */
  private submittedForm: SigningInFormValue | null = null;

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    await this.auth.ensureLoaded();
    try {
      const [instance, settings, accounts] = await firstValueFrom(
        forkJoin([this.service.instance(), this.service.settings(), this.service.accounts()]),
      );
      this.instance.set(instance);
      this.settings.set(settings);
      this.accounts.set(accounts.content);
      this.formRevision.update((revision) => revision + 1);
    } catch (thrown) {
      this.loadError.set(asApiError(thrown));
    } finally {
      this.loading.set(false);
    }
  }

  /** The form's submit. Turning the provider off is confirmed; everything else is saved. */
  protected submit(form: SigningInFormValue): void {
    const current = this.settings();
    if (!current) {
      return;
    }
    if (current.oidcEnabled && !form.oidcEnabled) {
      this.pendingOidcOff.set(form);
      return;
    }
    void this.save(form);
  }

  protected confirmOidcOff(): void {
    const form = this.pendingOidcOff();
    this.pendingOidcOff.set(null);
    if (form) {
      void this.save(form);
    }
  }

  protected cancelOidcOff(): void {
    this.pendingOidcOff.set(null);
  }

  /** "Take the latest": discard what was typed and fill the form from the server's copy. */
  protected useLatest(): void {
    this.conflict.set(null);
    this.submittedForm = null;
    this.formRevision.update((revision) => revision + 1);
  }

  /**
   * "Save mine anyway": the same choices, now against the copy the person has been shown.
   *
   * Through {@link submit} rather than straight to the save, so a conflict cannot become the one
   * route that turns the provider off without the confirmation.
   */
  protected keepMine(): void {
    const form = this.submittedForm;
    this.conflict.set(null);
    if (form) {
      this.submit(form);
    }
  }

  private async save(form: SigningInFormValue): Promise<void> {
    const current = this.settings();
    if (!current) {
      return;
    }
    this.submittedForm = form;
    this.savedKey.set(null);
    this.saveError.set(null);

    const patch = buildSettingsPatch(current, form);
    if (settingsPatchIsEmpty(patch)) {
      // Nothing to send. Said plainly rather than reported as a save that did something.
      this.savedKey.set('admin.nothingChanged');
      return;
    }

    this.saving.set(true);
    try {
      const saved = await firstValueFrom(this.service.updateSettings(patch));
      this.settings.set(saved);
      this.conflict.set(null);
      this.submittedForm = null;
      this.formRevision.update((revision) => revision + 1);
      this.savedKey.set('admin.saved');
      // The accounts block does not change, but the instance block's count can: opening
      // registration is the one setting here that lets the number move on its own.
      void this.refreshInstance();
    } catch (thrown) {
      const error = asApiError(thrown);
      if (error.status === 409 && error.code === 'stale-version') {
        await this.explainConflict(current);
      } else if (error.status === 409 && error.code === 'password-login-locked') {
        // The rule was met between the page loading and the click — the provider was turned off
        // in another tab, or an owner's proof was cleared. Say so, and show what is now true.
        this.saveError.set(error);
        await this.reread();
      } else {
        this.saveError.set(error);
      }
    } finally {
      this.saving.set(false);
    }
  }

  /**
   * Somebody else saved first. Re-read, name the settings that moved, and stop: the next move is
   * the administrator's. The form keeps what they typed, and nothing has been written.
   */
  private async explainConflict(opened: InstanceSettings): Promise<void> {
    try {
      const latest = await firstValueFrom(this.service.settings());
      this.settings.set(latest);
      this.conflict.set(differingSettings(opened, latest).map((field) => FIELD_LABEL_KEY[field]));
    } catch (thrown) {
      // The re-read failed too. Say that rather than inventing a list of what changed.
      this.saveError.set(asApiError(thrown));
    }
  }

  /** Back to the server's truth, form included. */
  private async reread(): Promise<void> {
    try {
      this.settings.set(await firstValueFrom(this.service.settings()));
      this.conflict.set(null);
      this.submittedForm = null;
      this.formRevision.update((revision) => revision + 1);
    } catch {
      // The alert already explains the failure the person was told about; a second one would not
      // add anything they can act on.
    }
  }

  private async refreshInstance(): Promise<void> {
    try {
      this.instance.set(await firstValueFrom(this.service.instance()));
    } catch {
      // A stale account count is not worth an error on a screen whose save just succeeded.
    }
  }
}
