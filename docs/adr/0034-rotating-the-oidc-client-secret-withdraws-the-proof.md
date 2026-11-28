# ADR-0034: Rotating the OIDC client secret withdraws the proof that the provider works

- **Status:** Accepted
- **Date:** 2026-11-22
- **Deciders:** Repository owner

## Context

Password login may be hidden only while OIDC is switched on **and** an `OWNER` has completed a
login through it. `ck_instance_settings_password_login_lockout` enforces it in the schema, and
`InstanceSettings.oidcOwnerLoginAt` is the evidence. The point of the rule is in
`security-model.md`: proving the new way in works before removing the old one is the only version
of this that is safe, because the alternative locks somebody out of their own financial records on
their own hardware with nobody to call.

`configureOidc` decided whether that evidence still applied by comparing the **issuer** and the
**client id**. Point the instance at a different provider and the proof is discarded, password
login comes back, and it must be re-proved. The client secret was deliberately left out of that
comparison, and slice 9's own unit test recorded why:

> Rotating the client secret is not a change of provider, and forcing password login back on for
> it would train an administrator to ignore the setting.

That is a real concern — a control that fires on routine maintenance is a control people learn to
click past.

A security review of the admin console found what it costs. The secret is **half the client
credential**. From a state that is entirely legal and ordinary — OIDC on, proof recorded, password
login hidden — one request is enough:

```
PATCH /api/admin/settings
{"oidcClientSecret": "S2-typo", "version": N}
```

The issuer and the client id are carried through unchanged, so the comparison is false, so the
proof survives and password login stays hidden. The constraint is satisfied throughout: all it can
see is that OIDC is switched on and that somebody signed in through it once. It cannot see that the
credential changed underneath. The token exchange now fails for everybody, including the
administrator who typed it, and the only way back is the host shell.

So the two sides are not comparable. One is "an administrator is mildly annoyed and has to re-hide
a setting". The other is "nobody can sign in to this instance, and the browser cannot help".

## Decision

**Any change to the client credential — issuer, client id, or client secret — discards
`oidcOwnerLoginAt` and switches password login back on.**

The comparison is by value, not by "was the field present in the request". An administrator who
saves the form without changing anything, or who changes only the provisioning flag, sends all
three values back unchanged and nothing is disturbed. Only a credential that differs from the
stored one is treated as unproven, because only that one is.

This is the same bargain already struck for the issuer, applied consistently to the thing that
makes up the other half of the same credential. It fails in the safe direction: one login route too
many, briefly, rather than none at all.

## Consequences

- **Rotating a working secret un-hides password login** until an `OWNER` signs in through the
  provider again. That is the cost, accepted knowingly. The admin console can soften it by saying
  so at the moment the secret field is edited; it cannot be designed away, because an unproven
  credential is unproven whatever the UI says about it.
- **The alarm-fatigue concern stands and is not solved here.** If rotation becomes frequent enough
  that administrators learn to ignore the resulting state, the answer is to make re-proving cheap —
  a "test this provider" action that records the proof without a full hide/unhide cycle — not to
  narrow the comparison again.
- **The schema cannot enforce this half.** The CHECK sees booleans and a timestamp, never the
  credential, so `configureOidc` is the only place the rule lives. `InstanceSettingsTest`
  (`rotatingTheClientSecretDiscardsTheProofToo` and
  `resavingTheIdenticalConfigurationDisturbsNothing`) and
  `AdminIT.replacing_the_client_secret_withdraws_the_evidence_too` are what hold it, and the last
  of those was proved against the unfixed code before being trusted.
- **A related hole stays open and is not this ADR's to close.** `ck_instance_settings_oidc_configured`
  does not require a client secret at all, so SQL can still null one out and leave no working
  route. The obvious constraint would forbid a public PKCE client, which is a legitimate
  configuration, so the invariant wants to be "a secret, or PKCE declared" — and migrations here
  are append-only, so it is worth deciding before writing. Recorded in the admin console's known
  gaps.

## Alternatives considered

**Leave the comparison as it was and warn in the UI.** Rejected: non-negotiable #2's spirit is that
the mechanism refuses, rather than the screen advising. A warning is not available to the next
caller — a CLI, an import, a future API client — and the lockout it fails to prevent is
unrecoverable from the browser.

**Refuse a secret rotation while password login is hidden**, forcing the administrator to re-enable
password login first, rotate, re-prove, and hide again. Rejected: it reaches the same end state
through more steps, and a refusal mid-way through a credential rotation leaves the instance holding
a secret the provider may already have retired.

**Clear the proof but leave password login hidden.** Not available — the constraint refuses the
transaction, which is the schema correctly insisting there always be one route in.
