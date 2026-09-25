# ADR-0027: The assistant is an optional self-hosted sidecar, or it does not exist

- **Status:** Accepted
- **Date:** 2026-09-25
- **Deciders:** Repository owner
- **Amends:** ADR-0016 — removes the hosted-endpoint and bring-your-own-key options it allowed

## Context

The designs include "Owl", an assistant that answers questions about your money — *"Can I afford
a $900 flight to Osaka in November?"* — and proposes changes to goals and budgets. To answer that
it needs the budget, the goals, the debts and the spending history. It is the single largest
privacy surface in the product.

ADR-0016 allowed three delivery routes: a local model, the user's own API key, or a hosted
endpoint we operate for a fee. `docs/architecture/security-model.md` covers none of them — it
does not mention an LLM at all. The caption in the designs, *"Sees your budget, never your
login"*, is a design promise with no control behind it.

Two of the three routes send a household's complete financial picture to a third party. The
bring-your-own-key route makes that the user's decision, which is defensible in the way ADR-0020
makes aggregator credentials the user's decision. The hosted route makes it ours, and would put
us in possession of exactly the data ADR-0016 says we must never hold — while also making the
feature require a service we operate, against non-negotiable #9.

## Decision

**The assistant runs on the user's own hardware, in a separate optional container, or the feature
does not exist on that instance.**

- The model ships as its **own Docker service**, not inside the API image. Nobody downloads
  several gigabytes of weights to run a budgeting app they will use without the assistant.
- It is **opt-in and absent by default.** A stock `docker compose up` gives you Budget Owl with no
  assistant and no reference to one.
- **The UI keys off the service, not a setting.** If the sidecar is reachable, Owl appears in the
  app. If it is not, Owl does not exist — no greyed-out button, no upsell, no "configure AI"
  panel. A feature you cannot use should not be advertised on your own machine.
- **Hardware capability is detected and reported honestly** before the assistant is offered. A
  model that will run but take ninety seconds a reply is a worse experience than no model, so the
  admin console reports what was detected and what it implies.
- **No third-party endpoints. No bring-your-own-key. No hosted API of ours.** All three are
  removed as options, not deferred.
- Financial data therefore **never leaves the instance for the assistant**, which makes the design
  caption true by construction rather than by policy.

## Alternatives considered

| Option | Why not |
|---|---|
| Hosted endpoint we operate, for a fee | Puts us in possession of users' complete financial picture — the one thing ADR-0016 says makes a decision wrong. Also makes a core-adjacent feature depend on a service we run, against non-negotiable #9. Commercially tempting and architecturally disqualifying. |
| Bring-your-own API key to a commercial provider | Defensible by analogy with ADR-0020, but the analogy is weak: an aggregator gets read access to transactions the user chose to connect, whereas the assistant needs the *whole* picture to be useful, on every question. It also makes "your data never leaves your hardware" conditional on a setting, which is the kind of promise people remember wrongly. |
| Ship the model inside the API image | Forces the download on everyone, including the majority who will never use it, and couples the API's release cadence to model updates. |
| A setting that enables the UI regardless of the sidecar | Produces a feature that is visible and broken. Keying off reachability means the app never offers what it cannot do. |

## Consequences

**Good:** the privacy claim becomes structural. There is no configuration in which the assistant
leaks financial data off the box, because there is no egress path to leak it through. The security
model's treatment of the assistant reduces to "the sidecar is on the instance network, not the
internet", which is a small and testable statement. It also keeps the core dependency-free — the
app is whole without it.

**Bad / costs:** the assistant will be worse than a frontier model, and on modest hardware
noticeably so. Most self-hosters will never see the feature, which makes it hard to justify much
investment in it. It closes off the API-for-a-fee revenue idea entirely — that was a real
commercial option and this decision rejects it on privacy grounds. Packaging, updating and
supporting a second container is real work, and hardware detection is a source of platform-specific
bugs (GPU, VRAM, Apple Silicon, ARM).

**Follow-ups:** `vision.md` and `README.md` still describe the removed options and need
correcting. `security-model.md` needs a section on the sidecar — network placement, that it is
never exposed publicly, and that prompts and responses are not logged. The admin console reports
detection results (slice 9). Which model, and how it is packaged, is a later decision — this ADR
fixes the shape, not the weights.
