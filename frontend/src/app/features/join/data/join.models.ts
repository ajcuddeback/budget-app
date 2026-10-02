import { HouseholdRole } from '../../../core/auth.models';

/**
 * Both fields are needed only when the invited address has no account yet. An existing user is
 * simply added to the household, and the server ignores a password sent for them.
 */
export interface AcceptInvitationRequest {
  displayName?: string;
  password?: string;
}

/**
 * What a joiner is told. No credential comes back: accepting a link proves possession of the
 * link, not of the account, so they sign in afterwards like anybody else.
 */
export interface AcceptedInvitation {
  householdId: string;
  householdName: string;
  role: HouseholdRole;
  userCreated: boolean;
}
