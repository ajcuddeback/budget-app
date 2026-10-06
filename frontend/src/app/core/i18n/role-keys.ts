import { HouseholdRole } from '../auth.models';
import { MessageKey } from './messages';

/** Typed, so a template cannot ask for a role label that does not exist. */
export const ROLE_KEY: Readonly<Record<HouseholdRole, MessageKey>> = {
  OWNER: 'role.OWNER',
  MEMBER: 'role.MEMBER',
  VIEWER: 'role.VIEWER',
};

export const ROLE_DESCRIPTION_KEY: Readonly<Record<HouseholdRole, MessageKey>> = {
  OWNER: 'role.OWNER.description',
  MEMBER: 'role.MEMBER.description',
  VIEWER: 'role.VIEWER.description',
};
