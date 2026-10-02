/** What a visitor may learn before signing in: which screen to show, and which routes exist. */
export interface SetupStatus {
  setupComplete: boolean;
  registrationOpen: boolean;
  passwordLoginEnabled: boolean;
  oidcEnabled: boolean;
}

export interface FirstUserRequest {
  email: string;
  displayName: string;
  password: string;
  householdName: string;
  baseCurrency: string;
}

export interface FirstUserResponse {
  userId: string;
  email: string;
  displayName: string;
  householdId: string;
  householdName: string;
  baseCurrency: string;
}
