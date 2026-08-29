# Java Style Guide

Formatting is handled by Spotless (google-java-format, AOSP). Never argue about it, never
hand-format — run `mvn spotless:apply`. This guide covers the things a formatter can't.

## Types

- **Records for immutable data**: DTOs, value objects, commands, query results. Not entities
  (JPA needs a no-arg constructor and mutable state).
- **`final` by default** on fields and locals that don't change. Classes that aren't designed
  for extension are `final`.
- **Never return `null`** from a method that can legitimately have no result — return
  `Optional<T>`. Never *accept* `Optional` as a parameter; overload instead.
- **Sealed interfaces** for closed hierarchies (result types, domain events), so `switch` is
  exhaustive and adding a case is a compile error.
- **`var`** where the right-hand side makes the type obvious. Not where it hides it.

## Nulls and validation

- Constructor-validate. A record's compact constructor rejects invalid state, so an instance
  that exists is always valid.
- `Objects.requireNonNull` on constructor parameters that must not be null.
- Bean Validation annotations on request DTOs, at the web edge. Domain objects validate
  themselves rather than trusting that someone else did.

## Naming

| Thing | Pattern | Example |
|---|---|---|
| Service | `<Noun>Service` | `TransactionService` |
| Controller | `<Noun>Controller` | `TransactionController` |
| Repository | `<Noun>Repository` | `TransactionRepository` |
| Request DTO | `<Verb><Noun>Request` | `CreateTransactionRequest` |
| Response DTO | `<Noun>Response` | `TransactionResponse` |
| Mapper | `<Noun>Mapper` | `TransactionMapper` |
| Test | `<ClassUnderTest>Test` / `...IT` | `TransactionServiceTest` |

Never `Manager`, `Helper`, `Util`, `Handler`, or `Processor` as a suffix — they mean nothing.
If you can't name a class precisely, it probably does more than one thing.

Boolean methods read as assertions: `isReconciled()`, `hasPendingTransactions()`.
Never negative names (`isNotArchived`) — double negatives in conditions are a bug factory.

## Dependency injection

- **Constructor injection only.** No `@Autowired` on fields — it hides dependencies and blocks
  immutability. One constructor means no annotation is needed at all.
- No `@Component` scanning of things that should be explicit configuration.
- If a constructor takes more than about five dependencies, the class does too much.

## Transactions

- `@Transactional` on **service** methods. Never on controllers, never on repositories.
- `@Transactional(readOnly = true)` for queries — it's a real optimization and documents intent.
- Never call an external service (HTTP, email, queue) inside a transaction. Commit first, then
  publish an event.
- Self-invocation doesn't proxy: calling `this.otherTransactionalMethod()` silently runs without
  a new transaction. Split the class instead.

## Exceptions

- Domain exceptions extend a common `DomainException` and carry enough context to render a
  useful problem response.
- Never catch `Exception` broadly to log-and-continue. Either handle it meaningfully or let it
  propagate to the `@RestControllerAdvice`.
- Never swallow an exception silently. An empty `catch` block is always a bug.
- Exception messages describe what failed and with which identifier — never include the value
  of a secret or an amount.

## Streams and collections

- Streams for transformation. A `for` loop is clearer for side effects — use it and don't
  apologize.
- Return immutable collections (`List.copyOf`, `Collectors.toUnmodifiableList`). Callers must
  not be able to mutate your internals.
- Never `Optional.get()` without `isPresent()`. Use `orElseThrow` with a meaningful exception.

## Logging

- SLF4J with parameterized messages: `log.info("Created transaction {}", id)` — never string
  concatenation.
- `DEBUG` for flow, `INFO` for business events, `WARN` for recoverable oddities, `ERROR` for
  things a human must look at. If everything is `ERROR`, nothing is.
- **Never log** passwords, session ids, tokens, or raw amounts. See the security model.

## Comments

Comment the *why*, never the *what*. If a method needs a comment to explain what it does,
rename it or split it. Javadoc on public API surface that isn't self-evident. Delete commented-out
code — git remembers.
