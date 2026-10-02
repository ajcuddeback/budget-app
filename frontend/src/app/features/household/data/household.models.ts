import { HouseholdRole } from '../../../core/auth.models';

export interface Household {
  id: string;
  name: string;
  baseCurrency: string;
  /** The caller's own role, so the screen never has to ask twice. */
  role: HouseholdRole;
}

export interface Member {
  /** The membership id — what the role and removal endpoints take, not the user id. */
  id: string;
  userId: string;
  email: string;
  displayName: string;
  role: HouseholdRole;
  joinedAt: string;
  displayCurrency: string | null;
  locale: string | null;
}

export interface UpdateHouseholdRequest {
  name: string;
  baseCurrency: string;
}

export interface CreateInvitationRequest {
  email: string;
  role: HouseholdRole;
}

/**
 * A new invitation. `token` is a credential and exists in this one response only — the server
 * keeps a hash. It is held in memory for as long as the dialog is open and never stored.
 */
export interface Invitation {
  id: string;
  email: string;
  role: HouseholdRole;
  token: string;
  acceptPath: string;
  expiresAt: string;
}

/** Both `null` means "follow the household and the platform" — a meaning, not a missing value. */
export interface OwnPreferences {
  displayCurrency: string | null;
  locale: string | null;
}
