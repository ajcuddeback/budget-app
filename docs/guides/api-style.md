# API Style Guide

Read `../architecture/security-model.md` before adding an endpoint. Every rule there applies
here first.

## Two transports, one API

Every endpoint is reachable by a browser with a session cookie and by the mobile app with a
bearer token (ADR-0018). The **API surface is identical** — same paths, same payloads, same
authorization. There is no `/api/mobile/...`, and no endpoint that exists for one client only.

CSRF applies to the cookie transport only; bearer requests carry no ambient credential to forge.
That is the one difference, and it is handled in the security configuration, not per-controller.

## Shape

- Base path `/api`. Version only when we break something: `/api/v2/...`.
- Resources are **plural nouns**: `/api/accounts`, `/api/accounts/{id}/transactions`.
- Verbs live in the HTTP method, not the path. `POST /api/accounts` — never
  `/api/createAccount`.
- Nest only to express real ownership, and never more than two levels deep.
- Actions that aren't CRUD get a sub-resource: `POST /api/transactions/{id}/reconcile`.

## Methods and status codes

| Method | Use | Success |
|---|---|---|
| `GET` | Read. Never has side effects | `200`, `404` |
| `POST` | Create, or a non-idempotent action | `201` + `Location`, `200` for actions |
| `PUT` | Full replace, idempotent | `200` / `204` |
| `PATCH` | Partial update | `200` |
| `DELETE` | Remove, idempotent | `204` |

- `400` malformed or failed validation · `401` not authenticated · `403` authenticated but
  forbidden by role · `404` missing **or not yours** (ADR-0008) · `409` conflict ·
  `422` semantically invalid · `429` rate-limited.
- Never `200` with an error in the body. The status code is the contract.

## Requests

- Every request body is a DTO with Bean Validation annotations, and every handler takes `@Valid`.
- **Entities are never request or response bodies.** Not once, not "just for this simple case".
  Binding an entity to a request body is how mass-assignment vulnerabilities happen.
- The acting user is never a request field. It comes from the `SecurityContext`.
- Unknown JSON properties are rejected (`FAIL_ON_UNKNOWN_PROPERTIES`), so a typo'd field is an
  error rather than a silently ignored value.

## Responses

- Amounts are **strings** (ADR-0006): `{"amount": "1234.56", "currency": "USD"}`.
- Dates are ISO-8601: `2026-08-27`. Timestamps are UTC instants: `2026-08-27T14:03:00Z`.
  Periods are `2026-08`.
- Enums are `SCREAMING_SNAKE_CASE` strings, never ordinals — an ordinal breaks the moment
  someone reorders the enum.
- No entity graphs. Return exactly what the client needs; use a sub-request for the rest.

## Collections

Every collection endpoint is paginated from day one — retrofitting pagination is a breaking change.

```json
{ "content": [...], "page": 0, "size": 50, "totalElements": 137, "totalPages": 3 }
```

- Default size 50, maximum 200. A request over the maximum is clamped, not rejected.
- Sort fields come from an **allowlist enum**, never a raw string interpolated into a query.
- Filters are explicit typed query parameters. No generic query languages in the URL.

## Errors — RFC 7807

```json
{
  "type": "https://budgetapp.dev/errors/validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "instance": "/api/transactions",
  "correlationId": "0f9c...",
  "errors": [{ "field": "amount", "message": "must be greater than zero" }]
}
```

- `Content-Type: application/problem+json`.
- Handled centrally in a `@RestControllerAdvice`. Controllers don't build error responses.
- **Never** leak stack traces, SQL, class names, or framework internals. The `correlationId` is
  what ties the user's report to the real error in the logs.
- Error messages are generic enough not to be an oracle — see the login-enumeration rule in the
  security model.

## Idempotency

Endpoints that move money accept an `Idempotency-Key` header and return the original result on
retry. A network timeout must never be able to create a duplicate transaction.

## Documentation

springdoc-openapi generates the spec from annotations. Keep `@Operation` and `@Schema`
descriptions accurate — they're read by humans and by agents. Never hand-maintain a spec file.
