# ADR-0020: Bank connections run on the user's own aggregator credentials

- **Status:** Accepted
- **Date:** 2026-09-05
- **Deciders:** Repository owner

## Context

Manually entering every transaction is the main reason people abandon budgeting apps. Automatic
bank feeds are the single highest-value optional feature.

They are also the feature most in tension with ADR-0016: bank data is the most sensitive data in
the product, and the usual way to deliver feeds — a hosted service holding aggregator credentials
and relaying everyone's transactions — is precisely what our users self-host to avoid.

## Decision

**The user supplies their own aggregator credentials, and their instance talks to the aggregator
directly. We are never in the path.**

- Credentials are configured per instance, encrypted at rest with a key derived from instance
  configuration, and never leave the instance.
- No Budget Owl-operated service sees credentials, tokens, or transaction data.
- **Read-only access only, always.** Budget Owl never initiates payments. Where an aggregator
  offers write scopes, we do not request them.
- Connections are **opt-in and off by default**. A fresh instance has none.
- Provider support is a **pluggable interface**, not a hard-coded vendor, because which
  aggregators a given user can actually use depends on their country and legal status.

**File import (CSV / OFX / QIF) is the baseline and ships first.** It works for every bank in
every country, needs no credentials, no regulatory status and no third party. Connections are an
enhancement layered on top — never a prerequisite for using the product.

### Liability, honestly

The intent is that a self-hoster connecting their own bank accounts does so at their own risk,
and that is largely how it works out — but "largely" is doing real work in that sentence, and the
mechanism is mostly architectural rather than contractual.

**What genuinely protects us:** we do not hold the credentials, we do not receive the data, and
we do not operate the instance. There is no breach of ours to have. That is worth more than any
paragraph of terms, and it is the reason the architecture above is shaped this way.

**What a disclaimer does do:** the warranty and liability disclaimers in the licence (ADR-0021)
are standard, well-tested for *distributed* open-source software, and should be kept. An
additional in-product acknowledgement before enabling connections — stating that the user is
using their own credentials under the aggregator's terms and is responsible for securing their
instance — is worth having, and makes the risk explicit at the moment it is taken.

**What it does not do, and should not be relied on:**

- **The aggregator's terms are between the user and the aggregator.** We cannot disclaim those,
  and we should not ship documentation encouraging anyone to breach them.
- **Access to bank account data is a regulated activity in the UK and EU** (an AISP permission
  under PSD2). Aggregators satisfy this by being regulated themselves and extending access under
  their licence and their conditions. Most require the account holder to be a registered
  business, and some prohibit it entirely for personal or redistributed use. **A hobbyist
  self-hoster may simply be unable to obtain credentials for a given provider** — which is a
  product constraint, not a legal risk to us, and it is the main reason the provider layer is
  pluggable and file import is the baseline.
- Consumer-protection law in some jurisdictions limits how far liability can be disclaimed to
  consumers, regardless of what a document says.

None of this blocks the feature. It does mean the disclaimer is a supporting measure and the
architecture is the actual control. **I am not a lawyer — the licence choice and any in-product
terms should be reviewed by one before a public release**, and that review is cheap compared to
getting it wrong in a financial product.

### Providers worth investigating first

To be verified against current terms and availability when the work starts:

| Provider | Notes |
|---|---|
| **SimpleFIN** | Designed for personal and self-hosted use; the lowest barrier. Used by Actual Budget |
| **GoCardless Bank Account Data** (formerly Nordigen) | Free tier, broad EU/UK coverage, popular with self-hosters |
| **Plaid** | Strong US coverage; requires a company and production approval |
| **File import** | Always works, no third party. Ships first |

## Alternatives considered

| Option | Why not |
|---|---|
| We operate the aggregator relationship for all users | Puts us in possession of everyone's bank credentials and transactions — the exact thing ADR-0016 exists to avoid, plus a regulatory and breach burden we are not equipped for |
| Screen-scraping the user's bank | Fragile, usually a breach of the bank's terms, and requires holding actual banking passwords. No |
| Manual entry and file import only | The safe answer, and it is the baseline — but automatic feeds are the highest-value optional feature and this design delivers them without holding anything |
| Ship credentials for a shared developer account | Would breach every aggregator's terms and put our account at risk from every user's behaviour |

## Consequences

**Good:** the highest-value feature ships without us ever touching a credential or a transaction.
Users in different countries can use whichever provider they can actually obtain. No breach
surface, because we hold nothing.

**Bad / costs:** setup is harder for the user — they must obtain their own credentials, which for
some providers and some countries they will not be able to do. Support requests will arrive for
provider behaviour we cannot see or reproduce. A pluggable provider layer is more work than
hard-coding one vendor, and each provider needs its own integration, tests and documentation.
Credential encryption at rest, key handling and rotation are new security-critical code.

**Follow-ups:** file import gets a feature doc and lands before any connection work. The provider
interface, the encryption-at-rest design, and the in-product acknowledgement each need
`/threat-model` before implementation. Legal review of the licence and terms before public
release.
