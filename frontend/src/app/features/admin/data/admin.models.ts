/**
 * The instance an operator runs: who may sign in, and how. **No financial data lives in any of
 * these shapes** — not a balance, not a count of transactions (ADR-0026, admin-console.md).
 *
 * `AdminResponses.java` is the contract these mirror.
 */
import { MessageKey } from '../../../core/i18n/messages';

/** What the operator needs to know about their server. */
export interface InstanceInfo {
  version: string;
  /** When first-run setup finished. `null` on an instance whose row predates the column. */
  setupCompletedAt: string | null;
  administratorId: string;
  administratorEmail: string;
  administratorName: string;
  /** How many accounts exist. A count of people, never of their money. */
  accountCount: number;
}

/**
 * How this instance lets people sign in.
 *
 * **There is no client-secret field, and there must never be one.** The API does not return the
 * secret to anybody, including the administrator who typed it: a secret that is read back lands in
 * a browser cache, a screenshot, an HTTP log and a bug report. {@link oidcClientSecretSet} is the
 * only thing said about it.
 */
export interface InstanceSettings {
  registrationOpen: boolean;
  passwordLoginEnabled: boolean;
  /**
   * Password login cannot be turned off yet, because no `OWNER` has proved the other route works
   * (security-model.md). The console states the rule rather than offering a control that 409s.
   */
  passwordLoginLocked: boolean;
  oidcEnabled: boolean;
  oidcIssuerUri: string | null;
  oidcClientId: string | null;
  /** Whether a secret is configured. Never what it is. */
  oidcClientSecretSet: boolean;
  oidcProvisioningEnabled: boolean;
  /** When an `OWNER` last completed a sign-in through the provider. `null` means never. */
  oidcOwnerLoginAt: string | null;
  updatedAt: string;
  /** Required on every write: optimistic locking only works if the caller says what it read. */
  version: number;
}

export type AccountStatus = 'ACTIVE' | 'DISABLED';

const STATUS_KEY: Readonly<Record<AccountStatus, MessageKey>> = {
  ACTIVE: 'admin.status.ACTIVE',
  DISABLED: 'admin.status.DISABLED',
};

/**
 * The label for a status, tolerating one this client has not heard of — a newer server may add a
 * value, and an operator reading their own account list should not meet a blank where a word was.
 */
export function accountStatusKey(status: string): MessageKey {
  return Object.hasOwn(STATUS_KEY, status)
    ? STATUS_KEY[status as AccountStatus]
    : 'admin.status.unknown';
}

/** One account that can sign in. */
export interface AdminAccount {
  id: string;
  email: string;
  displayName: string;
  status: AccountStatus;
  /** The instance administrator. Exactly one account has this (ADR-0029). */
  instanceAdmin: boolean;
  createdAt: string;
}

/** Not the standard page envelope: this collection is small and the server sends it whole. */
export interface AdminAccountList {
  content: AdminAccount[];
  total: number;
}

/**
 * A partial change to the settings.
 *
 * Every field is optional but `version`, and an absent field means "leave it alone". The secret is
 * absent unless somebody typed one — sending `oidcClientSecret: ''` would *clear* it server-side,
 * which is a different request from not mentioning it.
 */
export interface UpdateSettingsRequest {
  registrationOpen?: boolean;
  passwordLoginEnabled?: boolean;
  oidcEnabled?: boolean;
  oidcIssuerUri?: string;
  oidcClientId?: string;
  oidcClientSecret?: string;
  oidcProvisioningEnabled?: boolean;
  version: number;
}
