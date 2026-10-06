export type HouseholdRole = 'OWNER' | 'MEMBER' | 'VIEWER';

export const HOUSEHOLD_ROLES: readonly HouseholdRole[] = ['OWNER', 'MEMBER', 'VIEWER'];

/** The signed-in user's place in the one household an instance holds (ADR-0026). */
export interface Membership {
  householdId: string;
  name: string;
  baseCurrency: string;
  role: HouseholdRole;
  membershipId: string;
  /** `null` means "follow the household's base currency" (ADR-0022). */
  displayCurrency: string | null;
  /** `null` means "follow the platform locale" (ADR-0023). */
  locale: string | null;
}

export interface CurrentUser {
  id: string;
  email: string;
  displayName: string;
  instanceAdmin: boolean;
  /** `null` for a signed-in user nobody has invited yet — an empty state, not an error. */
  household: Membership | null;
}

export interface LoginRequest {
  email: string;
  password: string;
}

/** The collection envelope every list endpoint returns (docs/guides/api-style.md). */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}
