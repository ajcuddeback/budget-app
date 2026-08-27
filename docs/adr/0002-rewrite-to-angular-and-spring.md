# ADR-0002: Rewrite to Angular + Spring Boot

- **Status:** Accepted
- **Date:** 2026-08-27
- **Deciders:** Repository owner

## Context

The existing app is a small React + Express + Sequelize/MySQL budget tracker. It works, but its
foundations do not support the intended product (a full budgeting app: accounts, transactions,
categories, budgets, recurring items, reporting), and it has security defects that are
structural rather than incidental — an unauthenticated endpoint returning every user's
financial data, `httpOnly: false` on the session cookie, no CSRF protection, user enumeration
on login, and schema managed by `sequelize.sync()`. `docs/domain/legacy-app.md` has the full list.

Fixing these one at a time would mean rebuilding the data model, the auth layer, and the API
surface — which is the whole application.

The owner wants a typed, strongly-structured backend where security controls are framework
defaults rather than things you remember to add.

## Decision

We rewrite the application as an Angular SPA against a Java 21 / Spring Boot API.

Spring Security gives us session management, CSRF, password encoding, and method security as
mature, audited defaults. Angular gives us a typed frontend with a strong opinion about
structure, which suits AI-assisted development — there is usually one right way to do a thing.

## Alternatives considered

| Option | Why not |
|---|---|
| Incrementally harden the existing MERN app | The data model, auth, and API all need replacing — that is a rewrite with extra steps and a worse end state |
| Keep React, replace only the backend | Reasonable, but the owner chose Angular; a single opinionated frontend framework also reduces AI-generated drift |
| Node + TypeScript backend (NestJS) | Viable, but Spring Security's defaults and Java's type system better match "security is the top priority" |

## Consequences

**Good:** strong typing end to end; mature, audited security primitives; a structure that
AI agents can follow consistently.

**Bad / costs:** a full rewrite, and a JVM toolchain to run and deploy. Nothing ships until the
first vertical slice is done. Legacy data needs a migration path.

**Follow-ups:** ADR-0003 through ADR-0009 pin the stack details. `client/` and `server/` are
deleted once feature parity is reached, not before.
