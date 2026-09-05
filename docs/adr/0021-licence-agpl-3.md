# ADR-0021: Licence — AGPL-3.0

- **Status:** **Proposed** — awaiting the repository owner's decision
- **Date:** 2026-09-05
- **Deciders:** Repository owner

## Context

Budget Owl is to be open source (ADR-0016). No licence has been chosen, and the repository
currently has none — which means, by default, **nobody has any right to use, modify or distribute
it**. That is fine while it is private and wrong the moment it is public.

The licence interacts with a possible commercial layer: managed hosting is under consideration,
and an LLM-related paid tier has been floated.

Licence choice is close to irreversible in practice. Relicensing later needs the agreement of
every contributor who has since accepted a patch, and a project that has to hunt down past
contributors usually does not relicense at all.

## Decision (proposed)

**AGPL-3.0-or-later.**

- It is Immich's licence, and Immich is the reference point for this product.
- It closes the SaaS loophole: anyone offering Budget Owl as a network service must publish their
  modifications. If managed hosting ever becomes a business, this stops a larger provider taking
  the work and running a closed competing service — the specific commercial risk this project has.
- Self-hosters are entirely unaffected. Running it for yourself, your household, or modifying it
  privately triggers nothing.
- It signals the project's values to an audience that reads licences and cares.

Practical requirement either way: adopt the **Developer Certificate of Origin** (a `Signed-off-by`
line) rather than a CLA. It keeps contribution friction low while establishing that contributors
had the right to contribute. A CLA with copyright assignment would preserve the option to
relicense or dual-licence later, but it deters exactly the contributors this project wants.

## Alternatives considered

| Option | Why not |
|---|---|
| **MIT / Apache-2.0** | Friendlier to contributors and to commercial adoption, and Apache-2.0 adds an explicit patent grant. But it permits a hosted closed-source competitor built on this work, which is the one commercial risk worth guarding against. Choose this if maximising adoption and contribution matters more than protecting a hosting business |
| **GPL-3.0** (not Affero) | Copyleft for distribution but not for network use — and a hosted competitor never distributes, so the obligation never triggers. Gives up the protection that motivates copyleft here |
| **Open core** (OSS core, proprietary extras) | Keeps a clearer commercial path, but splitting the codebase creates permanent tension about which side a feature belongs on, and the self-hosting audience is rightly suspicious of it |
| **No licence** (status quo) | Legally the most restrictive outcome — no rights granted at all — which is the opposite of the intent |

## Consequences

**Good:** protects against a closed-source hosted competitor. Aligns with the audience's
expectations. Compatible with selling hosting ourselves, since we would publish our own changes
anyway.

**Bad / costs:** AGPL deters some corporate contributors and users whose employers ban it, so the
contributor pool is smaller than a permissive licence would give. If we later want to dual-licence
or offer a proprietary edition, we cannot without every contributor's agreement — the DCO does not
give us that right, and choosing a CLA instead is the trade that would.

**This decision is not mine to make.** It is recorded as Proposed because the reasoning is
worth having written down; it becomes Accepted only when the owner says so. **A lawyer should
confirm the choice before public release**, particularly given the financial-data context and the
in-product terms discussed in ADR-0020.

**Follow-ups on acceptance:** add `LICENSE`, an SPDX header convention, `CONTRIBUTING.md` with
the DCO, and a licence section in the README.
