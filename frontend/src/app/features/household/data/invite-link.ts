/**
 * The absolute invitation link, built from *this browser's* origin.
 *
 * The server returns a relative `acceptPath` on purpose: the only origin it knows is the `Host`
 * header, which the caller chooses, and a credential-bearing URL assembled from it would be a
 * phishing link with our name on it. The origin the owner is looking at is the right one.
 *
 * `acceptPath` is parsed against the origin and the result must still be on it, so a path that is
 * really a protocol-relative or absolute URL (`//evil.test/…`) cannot redirect the link.
 * Returns `null` when it cannot be made safely, and the caller shows nothing rather than a guess.
 */
export function buildInviteLink(acceptPath: string, origin: string): string | null {
  if (!acceptPath.startsWith('/') || acceptPath.startsWith('//')) {
    return null;
  }
  try {
    const link = new URL(acceptPath, origin);
    return link.origin === new URL(origin).origin ? link.href : null;
  } catch {
    return null;
  }
}
