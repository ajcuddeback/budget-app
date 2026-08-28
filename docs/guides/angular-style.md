# Angular Style Guide

Formatting is Prettier; linting is ESLint. Run them, don't debate them.

## Use the official Angular skill first

This repo vendors Angular's own agent skills (`angular-developer`, `angular-new-app`) from
[angular/skills](https://github.com/angular/skills), pinned in `skills-lock.json`. They are the
Angular team's guidance, kept current with the framework, and they cover far more ground than
this file: signals, `resource`, DI, routing, ARIA components, pipes, CLI, migrations.

**Reach for `angular-developer` when you need framework knowledge.** This guide is not a
replacement for it — it is the layer on top that says what *this project* does.

### Where this project overrides the skill

The Angular skill states general best practice for a new Angular app. We are not a generic new
app, and on these points **this guide wins**. The skill itself says to prioritise existing
project conventions, so this is the mechanism it expects.

| Topic | Angular skill says | We do | Why |
|---|---|---|---|
| **Forms** | Prefer Signal Forms for new v22+ apps | **Typed reactive forms** | Forms are our untrusted-input edge and security is the top priority. We want that surface boring and well-trodden, with a large body of prior art for validation patterns. Revisit when Signal Forms has more mileage — that would be an ADR |
| **Styling** | Tailwind CSS reference available | **SCSS + design tokens** | ADR-level dependency we have not taken. Do not introduce Tailwind without one |
| **Project creation** | `ng new <app-name>` in the current directory | **`frontend/`, and only that path** | ADR-0005 fixes the monorepo layout. Never scaffold to a different directory |
| **`--ai-config`** | Pass it to `ng new` to write an agent config | **Do not** | It writes a competing `CLAUDE.md`/`AGENTS.md` that would fight this harness's routing. Our config is `CLAUDE.md` at the repo root |
| **SSR** | Offers SSR/prerendering | **CSR only for now** | We are a session-authenticated SPA. SSR changes the auth story and needs an ADR |
| **Test runner** | Vitest | **Vitest** | Agreed — we moved to it *because* of the skill (ADR-0014) |

Everything the skill says that is **not** in this table applies. Signals, standalone components,
`inject()`, native control flow, ARIA patterns, naming — follow it.

When the skill and this guide appear to disagree on something not listed here, that is a gap
worth resolving rather than guessing: raise it, and record the answer with `/remember` or `/adr`.

## Non-negotiables

- **`strict: true`** in `tsconfig`, plus `strictTemplates`. No `any` — if you truly need an
  escape hatch it's `unknown` plus a type guard. `any` in a PR needs a written reason.
- **Standalone components.** No NgModules.
- **`OnPush` change detection** on every component. With signals this is the default good path.
- **Lazy-load every feature route.** The initial bundle stays small.

## Structure

```
src/app
├── core/          singletons — auth service, interceptors, guards, error handler
│                  imported once by the app config; never by feature modules
├── shared/        presentational components, pipes, directives — no business logic,
│                  no services with state
├── features/
│   └── <feature>/
│       ├── <feature>.routes.ts
│       ├── pages/          routed, smart: fetch data, hold state
│       ├── components/     presentational, dumb: inputs in, outputs out
│       ├── data/           <feature>.service.ts (HTTP), models
│       └── <feature>.store.ts   signal-based state, if the feature needs it
└── styles/        design tokens, global styles
```

**Smart vs. dumb** is the discipline that keeps this testable. A page component fetches and
coordinates. A presentational component takes `input()`s, emits `output()`s, and has no
injected services. If a dumb component needs `HttpClient`, it's in the wrong folder.

## State

- **Signals** for component and feature state. `computed()` for derived values — never
  recompute in the template.
- **RxJS at the HTTP boundary**, converted to signals for consumption. Don't thread observables
  through the whole component tree.
- No NgRx. It's not warranted at this size, and adding it is an ADR-level decision.
- Never mutate a signal's value in place — always set a new value, so change detection sees it.

## HTTP and API access

- Feature services own HTTP calls. Components never call `HttpClient` directly.
- Every API call is typed. The response type is a real interface, not `any` or `object`.
- **`withCredentials: true`** on every API request — session cookie auth (ADR-0004). Configure
  it once in an interceptor rather than at each call site.
- Angular sends the `X-XSRF-TOKEN` header automatically when the CSRF cookie is named
  `XSRF-TOKEN`. Don't hand-roll CSRF handling.
- Errors are handled by an interceptor that maps RFC 7807 problem responses to a typed error
  and routes `401` to the login flow.

## Money on the frontend

Amounts arrive from the API as **strings** (ADR-0006). Do not `parseFloat` them.

- Keep them as strings, format for display with a currency pipe fed by a decimal library.
- Never do arithmetic on money in the browser and send the result to the server as truth.
  The server computes; the client displays.

## Forms

- **Typed reactive forms** only. No template-driven forms, no `FormGroup<any>`, and **not
  Signal Forms** — the `angular-developer` skill prefers those for new apps and we deliberately
  do not. See the override table at the top of this guide.
- Validation mirrors the server's rules for immediate feedback — it never replaces them.
  Client-side validation is a UX feature, not a security control.
- Disable the submit button while a request is in flight; double-submit is a real bug when
  money is involved.

## Templates

- Control flow with `@if` / `@for` / `@switch`, not `*ngIf` / `*ngFor`.
- `@for` always has a `track` expression. Missing tracking is a performance bug and a
  identity bug in lists.
- No logic in templates beyond a `computed()` reference. If there's a ternary chain in the
  template, it belongs in the component.
- **Never** `[innerHTML]` with user content, and never `bypassSecurityTrust*`. Angular escapes
  by default — keep it that way.

## Accessibility

Not optional. Semantic elements, labels tied to inputs, keyboard reachability, visible focus,
`aria-live` for async status. Money is often read by people using screen readers and by people
who are tired and stressed. Contrast ratios meet WCAG AA.

## Testing

Vitest plus Angular Testing Library. Test what a user does — query by role and label, not by CSS
class. See `testing-style.md`.
