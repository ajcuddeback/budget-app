# ADR-0018: First-party auth, optional OIDC, opaque tokens for mobile

- **Status:** Accepted
- **Date:** 2026-09-05
- **Deciders:** Repository owner
- **Supersedes:** ADR-0004

## Context

ADR-0004 chose server-side sessions with cookies, and stated its own expiry condition: *"A future
mobile client would need this decision revisited."* Two things now force that revisit.

**Mobile (ADR-0019).** Cookies and CSRF tokens are browser mechanics. A native client has no
cookie jar worth the name, and CSRF is meaningless without a browser's ambient-credential
behaviour. Sessions also fit mobile expectations badly: people expect to open a budgeting app
weeks later and still be logged in.

**Self-hosting (ADR-0016).** This reverses the recommendation that seemed obvious a week ago.
External OIDC — Auth0, or a Keycloak the user must run — was ADR-0004's named upgrade path. It is
wrong here: requiring a self-hoster to stand up an identity provider, or pay a SaaS, to log into
their own budget app on their own hardware is hostile, and would be the single most complained-
about thing in the project.

At the same time, a large share of self-hosters *already* run Authentik, Authelia or Keycloak and
will expect to use it.

## Decision

**One authentication system, two credential transports, and an optional identity source.**

**Web (unchanged from ADR-0004):** server-side session, cookie `HttpOnly` / `Secure` /
`SameSite=Lax`, CSRF tokens mandatory. This remains the most XSS-resistant option available to a
browser client, and ADR-0004's reasoning for it was and is correct.

**Mobile: opaque bearer tokens, stored server-side.**

- Issued by exchanging credentials; presented as `Authorization: Bearer`.
- **Opaque and server-side, not JWT.** This preserves the property ADR-0004 valued most —
  revocation is a `DELETE` — which a self-contained JWT gives up. For an app holding financial
  data, being able to kill a stolen phone's access immediately is worth more than statelessness
  we have no use for.
- Each token records a device label, creation time and last use, so the user gets a **"logged-in
  devices"** screen and can revoke one. That is a product feature falling out of the mechanism.
- Stored on device in the platform secure store — iOS Keychain, Android Keystore — never in
  shared preferences or a file.
- Sliding expiry with an absolute cap; both server-enforced.

**Optional OIDC, off by default.** A self-hoster may point the instance at their own provider by
configuration. When enabled it is an additional login route, not a replacement, and it changes
nothing about how the app authorizes requests.

Password hashing, credential policy, enumeration resistance, rate limiting and session-fixation
handling carry over from ADR-0004 and `docs/architecture/security-model.md` unchanged.

## On the two-transport tension

Earlier in this project's design I argued against running two auth paths, on the grounds that two
paths mean two attack surfaces. That objection was aimed at two auth *systems* — separate user
stores, separate revocation, separate rules. This is not that.

There is one user store, one authorization layer, one set of roles (ADR-0017), and one revocation
model. Only the transport differs, and it differs because browsers and native apps have genuinely
different threat models: a cookie is the right answer where XSS is the dominant risk, a
secure-store token is the right answer where there is no DOM to attack. Both terminate in the same
Spring Security `Authentication`.

The alternative — tokens for the web client too — would mean putting a bearer token somewhere
JavaScript can read it. ADR-0004 rejected that for good reason and this ADR does not reopen it.

## Alternatives considered

| Option | Why not |
|---|---|
| Mandatory external OIDC (Keycloak/Auth0) | ADR-0004's own suggested successor, and wrong under ADR-0016. Forces a self-hoster to run or pay for identity infrastructure to use their own app |
| JWT access + refresh | Loses instant revocation, the property most worth keeping for a finance app, in exchange for statelessness we cannot use. Adds rotation and replay detection to get back what sessions gave free |
| Sessions for mobile too | Technically possible with a cookie-capable HTTP client; fights the platform, breaks on background refresh, and leaves CSRF machinery that protects nothing |
| Spring Authorization Server (our own OAuth2) | Standards-correct and the right call if we ever have third-party clients. Disproportionate for two first-party clients on a home server |

## Consequences

**Good:** mobile works, self-hosters need nothing beyond the Compose file, and people already
running an IdP can use it. Revocation stays immediate on every transport. Device-session
management becomes a visible feature.

**Bad / costs:** two transports to test — every endpoint needs coverage under both, and the
mandatory-test list in `docs/guides/testing-style.md` grows accordingly. Token issuance is a new
credential-handling surface with its own rate limiting. Optional OIDC is a configuration path
that will be under-exercised precisely because it is optional, so it needs integration tests
against a real provider container rather than a mock.

**Follow-ups:** rewrite `docs/features/accounts-and-auth.md` around this model — it is currently
written against ADR-0004 and is now wrong. Update `docs/architecture/security-model.md`. Add the
bearer-token and role cases to the `security-auditor` checklist.
