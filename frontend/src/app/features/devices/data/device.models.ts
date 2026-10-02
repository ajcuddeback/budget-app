/** `SESSION` is a web browser (cookie); `BEARER` is a mobile app's token (ADR-0018). */
export type DeviceKind = 'SESSION' | 'BEARER';

/**
 * One signed-in browser or phone belonging to the caller.
 *
 * `id` is a handle, not a credential: a SHA-256 of the session id for a session (the session id
 * *is* the cookie, so the real one is never sent), a row id for a token.
 */
export interface Device {
  id: string;
  kind: DeviceKind;
  label: string;
  createdAt: string;
  lastUsedAt: string | null;
  expiresAt: string | null;
  current: boolean;
}
