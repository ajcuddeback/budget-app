import { InstanceSettings, UpdateSettingsRequest } from './admin.models';

/** The signing-in form's raw value. Strings are what the controls hold, trimmed on the way out. */
export interface SigningInFormValue {
  registrationOpen: boolean;
  passwordLoginEnabled: boolean;
  oidcEnabled: boolean;
  oidcIssuerUri: string;
  oidcClientId: string;
  /** Empty means "nothing was typed" — which is not the same as "clear the secret". */
  oidcClientSecret: string;
  oidcProvisioningEnabled: boolean;
}

/**
 * The difference between what the server last said and what the form holds — nothing more.
 *
 * Two rules are load-bearing:
 *
 * - **The client secret is sent only when somebody typed one.** The input is empty on load because
 *   the API never says what the stored secret is, so an unchanged form must not mention the field:
 *   the server reads a present-but-blank `oidcClientSecret` as "clear it" (`AdminRequests`), and a
 *   form that sent `''` every time would wipe the secret on any unrelated save.
 * - **`version` always travels.** It is what the caller read; the server refuses a stale one with
 *   `409 stale-version` rather than letting two administrators overwrite each other.
 */
export function buildSettingsPatch(
  current: InstanceSettings,
  form: SigningInFormValue,
): UpdateSettingsRequest {
  const patch: UpdateSettingsRequest = { version: current.version };

  if (form.registrationOpen !== current.registrationOpen) {
    patch.registrationOpen = form.registrationOpen;
  }
  if (form.passwordLoginEnabled !== current.passwordLoginEnabled) {
    patch.passwordLoginEnabled = form.passwordLoginEnabled;
  }

  if (form.oidcEnabled !== current.oidcEnabled) {
    patch.oidcEnabled = form.oidcEnabled;
  }

  // Turning the provider off says only that. The server clears the configuration and switches
  // password login back on (`InstanceSettings.disableOidc`), so sending an issuer alongside would
  // describe a configuration the request is in the middle of abandoning.
  if (form.oidcEnabled) {
    const issuer = form.oidcIssuerUri.trim();
    if (issuer !== (current.oidcIssuerUri ?? '')) {
      patch.oidcIssuerUri = issuer;
    }
    const clientId = form.oidcClientId.trim();
    if (clientId !== (current.oidcClientId ?? '')) {
      patch.oidcClientId = clientId;
    }
    // Sent as typed, never trimmed: a secret is an opaque string and this is not the place to
    // decide which of its characters the operator did not mean.
    if (form.oidcClientSecret.trim() !== '') {
      patch.oidcClientSecret = form.oidcClientSecret;
    }
    if (form.oidcProvisioningEnabled !== current.oidcProvisioningEnabled) {
      patch.oidcProvisioningEnabled = form.oidcProvisioningEnabled;
    }
  }

  return patch;
}

/** Nothing but the version: there is nothing to save, so nothing is sent. */
export function settingsPatchIsEmpty(patch: UpdateSettingsRequest): boolean {
  return Object.keys(patch).length === 1;
}

/** Which settings the stored copy and a freshly read one disagree about. */
export type SettingsField =
  | 'registrationOpen'
  | 'passwordLoginEnabled'
  | 'oidcEnabled'
  | 'oidcIssuerUri'
  | 'oidcClientId'
  | 'oidcClientSecretSet'
  | 'oidcProvisioningEnabled';

const COMPARED: readonly SettingsField[] = [
  'registrationOpen',
  'passwordLoginEnabled',
  'oidcEnabled',
  'oidcIssuerUri',
  'oidcClientId',
  'oidcClientSecretSet',
  'oidcProvisioningEnabled',
];

/**
 * What somebody else changed, as **field names only**.
 *
 * Deliberately not their values. One of these fields is the client secret, and a conflict notice
 * that rendered values would be the one place in the console that put a secret on screen — so the
 * whole notice names fields and leaves the values to the form below it.
 */
export function differingSettings(
  opened: InstanceSettings,
  latest: InstanceSettings,
): SettingsField[] {
  return COMPARED.filter((field) => opened[field] !== latest[field]);
}
