# Security policy

Budget Owl handles people's financial data and runs on hardware we have no access to. Both halves
of that matter here: a vulnerability is serious, **and** we cannot patch anyone's instance for
them. Every fix has to reach a human who then chooses to upgrade.

> **Status: early.** Nothing is built or released yet, so there is nothing deployed to attack.
> This policy is here so it exists before the first release, not because there is a running
> version to report against.

## Reporting a vulnerability

**Do not open a public issue, discussion, or pull request for a security vulnerability.**

Report it privately through GitHub:
[**Report a vulnerability**](https://github.com/ajcuddeback/budget-app/security/advisories/new)
(Security → Advisories → Report a vulnerability). That opens a private thread visible only to you
and the maintainers.

If private reporting is unavailable to you for any reason, open a public issue that says only
*"I would like to report a security issue privately"* — no details, no reproduction, no affected
version — and you will be given a channel.

Helpful, in rough order of usefulness:

- What an attacker gets. "Any authenticated user can read another household's transactions" tells
  us more than a CVSS score.
- The smallest reproduction you have — a request, a sequence of steps, a diff.
- The version, commit, or image tag.
- Whether it needs an account, and what role that account has.

You do not need a working exploit. A precise description of a flaw is worth more than a
half-finished proof of concept.

## What to expect

| | |
|---|---|
| Acknowledgement | Within 3 working days |
| Initial assessment | Within 7 days — severity, and whether we agree it is a vulnerability |
| Fix and advisory | As fast as severity warrants; you will be kept informed either way |

This is a small open-source project, not a vendor with an on-call security team. If a deadline
slips you will be told it slipped rather than left in silence.

We will publish a [GitHub Security Advisory](https://github.com/ajcuddeback/budget-app/security/advisories)
for every confirmed vulnerability, with a CVE where one is warranted — **including ones we found
ourselves.** Self-hosters cannot upgrade to a fix they never heard about, so quietly slipping a
patch into a release is not an option available to us.

You will be credited in the advisory unless you would rather not be. Say so and you will not be.

## Disclosure

Please give us a reasonable window to ship a fix before going public — **90 days** is the default,
and less when a fix lands sooner. If we go quiet on you, or you disagree with our assessment,
publish; a policy that lets a project sit on a real flaw indefinitely is not a security policy.

There is no bug bounty. There is no money in this project to pay one from.

## Supported versions

Until the first release, only `main` is supported. Once releases exist this table will say which
ones get fixes; assume the latest release and nothing older.

## Scope

**In scope** — the application (API, web, mobile), the Docker Compose distribution and its default
configuration, the build and release pipeline, and this repository's CI workflows.

In particular, we treat these as vulnerabilities and want to hear about them:

- Reading or writing another household's data — the boundary the whole authorization model rests
  on ([ADR-0008](docs/adr/0008-user-scoped-data-access.md),
  [ADR-0017](docs/adr/0017-households-own-financial-data.md)).
- A `VIEWER` performing a write, or any role escalation.
- Authentication, session, token or CSRF defects
  ([`docs/architecture/security-model.md`](docs/architecture/security-model.md)).
- Anything that sends a user's financial data off their instance. Data leaving the machine is a
  vulnerability *by definition* here, not a feature request
  ([ADR-0016](docs/adr/0016-self-hosted-open-source-product.md)).
- Injection, deserialization, SSRF, path traversal, or dependency vulnerabilities we ship.
- Secrets in the repository, in built images, or in logs.

**Out of scope** — an instance the operator configured insecurely (exposed to the internet with no
TLS, a weak first-user password, a public database port); credentials the operator supplies for
their own bank aggregator accounts, which are their responsibility by design
([ADR-0020](docs/adr/0020-bank-connections-use-user-credentials.md)); the security of a third-party
model or endpoint someone opts into; automated scanner output with no demonstrated impact; and
missing hardening headers with no exploitable consequence.

"Out of scope" means it will not be treated as a vulnerability. It does not mean we are
uninterested — if the default configuration makes an operator likely to get this wrong, that is a
bug in the defaults, and the defaults *are* in scope. Open an issue.

## For people running Budget Owl

Your instance is yours, which means its security is too. The deployment guide will carry the full
list; the short version is: keep it off the open internet unless you have a reason, put TLS in
front of it if you do, keep it updated, and watch the
[advisories](https://github.com/ajcuddeback/budget-app/security/advisories) — that is how a fix
reaches you.
