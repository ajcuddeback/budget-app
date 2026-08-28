# ADR-0014: Adopt the official Angular agent skills

- **Status:** Accepted
- **Date:** 2026-08-28
- **Deciders:** Repository owner

## Context

Angular publishes agent skills at [angular/skills](https://github.com/angular/skills), generated
from the `angular/angular` repository: `angular-developer` (framework guidance, ~40 reference
documents covering signals, DI, routing, forms, ARIA, testing, CLI, migrations) and
`angular-new-app` (scaffolding a new application).

Our `docs/guides/angular-style.md` was written from what this session knew about Angular. That
has two problems an official skill solves:

- **It ages.** Angular ships every six months. A hand-written style guide silently drifts from
  the framework, and an agent following it writes last year's Angular.
- **It is shallow.** Our guide is about a page. The skill covers `linkedSignal`, `resource`,
  hierarchical injectors, ARIA component patterns, and router testing harnesses — depth we would
  never maintain by hand, and that an agent would otherwise reconstruct from memory, badly.

Adopting it introduces a real risk: **two sources of truth giving contradictory instructions.**
That is precisely the convention drift this harness exists to prevent, and inspection found
genuine conflicts rather than hypothetical ones.

## Decision

Vendor both skills into the repository, pinned, and define a strict precedence between them and
our own guidance.

**Vendored, not installed on demand.** `npx skills add https://github.com/angular/skills` writes
the skill content to `.agents/skills/`, symlinks it into `.claude/skills/`, and records
content hashes in `skills-lock.json`. All three are committed (42 files, ~244K, MIT, © Google
LLC). Anyone who clones gets the same guidance without a setup step, updates are a deliberate
reviewable commit rather than a silent change, and the lock file pins exactly what we reviewed.

**Precedence, stated once and enforced by a table.** `angular-developer` answers *how does
Angular do this*; `docs/guides/angular-style.md` answers *what does this project do*. Where they
disagree, the project guide wins — and every such point is listed in an override table at the
top of that guide, so the exceptions are enumerable rather than folklore. The Angular skill
itself instructs agents to prioritise existing project conventions, so this is the mechanism it
expects.

**The overrides, decided here:**

| Topic | Skill | Us | Reason |
|---|---|---|---|
| Forms | Prefer Signal Forms (v22+) | **Typed reactive forms** | Forms are the untrusted-input edge and security is the top priority. We want a boring, well-trodden surface with substantial prior art on validation. Not a permanent judgement on Signal Forms — revisiting is a new ADR |
| Test runner | Vitest | **Vitest** | We changed *to* Vitest because of the skill. It is Angular's default; Jest needs `jest-preset-angular` and is drifting toward legacy. Free to change with no frontend code written |
| Styling | Tailwind reference available | **SCSS + tokens** | A dependency we have not taken; taking it needs an ADR |
| Scaffolding | `ng new <app-name>` | **`frontend/` only** | ADR-0005 fixes the layout |
| `--ai-config` | Recommended for `ng new` | **Never** | It writes a competing `CLAUDE.md`/`AGENTS.md` that would fight this harness's routing |
| SSR | Offered | **CSR only** | Session-authenticated SPA; SSR changes the auth story and needs an ADR |

## Alternatives considered

| Option | Why not |
|---|---|
| Keep only our hand-written guide | Ages with every Angular release and is far shallower. We would be maintaining a worse copy of documentation Angular already maintains |
| Install via `npx skills add` as a documented setup step | Depends on every contributor and CI runner running it, with network access, and on upstream not changing under us between runs. Reproducibility is the whole point of this harness |
| Adopt the skill and delete our style guide | Loses every project-specific decision — the security model, money-as-strings, the layering rules. The skill knows Angular; it does not know this app |
| Adopt without resolving the conflicts | The failure mode the harness exists to prevent: two documents giving an agent contradictory instructions, resolved differently each session |

## Consequences

**Good:** framework guidance is now the Angular team's, current and deep, and refreshing it is
one command plus a review. Our style guide shrinks toward what it should have been all along —
project decisions, not framework tutorials. The override table makes every deviation explicit
and countable, which is exactly the artifact that stops the same argument recurring.

**Bad / costs:** 42 third-party files in the repository that we do not control the contents of,
and updating them is a manual, reviewable step someone has to remember (`tools/update-skills.sh`
exists for this). Precedence between two sources of guidance is a rule agents must actually
follow — stated in the agent prompt, the style guide, and `docs/memory/conventions.md`, but
still a rule rather than a mechanism. The override table is now a maintenance surface: when
Angular's defaults change, the table is what goes stale.

The `.claude/skills/` entries are **symlinks** into `.agents/skills/`. Git stores these correctly
(mode 120000), but a Windows checkout without `core.symlinks` support gets plain text files
containing a path, and the skills silently fail to load. Noted in `docs/memory/gotchas.md`.

**Follow-ups:** re-run `tools/update-skills.sh` after each Angular major and review the diff,
paying particular attention to whether any override in the table has become unnecessary.
Consider the Angular CLI's MCP server (`ng mcp`) separately — it is referenced by the skill but
is its own decision.
