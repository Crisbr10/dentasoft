import { ClinicUserDetails } from '../../api/models/clinic-user-details';

// Plain functions, outside the component file, so a screen that needs them (practitioners) does not
// load the users screen with them.

/** The access of a person to the clinic, as the list shows it. */
export type UserStatus = 'ACTIVE' | 'PENDING' | 'INACTIVE';

export function userStatus(user: ClinicUserDetails): UserStatus {
  if (!user.active) {
    return 'INACTIVE';
  }
  return user.invitationPending ? 'PENDING' : 'ACTIVE';
}

/** The full name, or the email while the person has not accepted the invitation. */
export function displayName(user: ClinicUserDetails): string {
  const name = [user.profile?.firstName, user.profile?.lastName]
    .filter((part) => part?.trim())
    .join(' ');
  return name || (user.profile?.email ?? '');
}
