# ADR-0006: Money as BigDecimal / NUMERIC(19,4)

- **Status:** Accepted
- **Date:** 2026-08-27
- **Deciders:** Repository owner

## Context

This is a budgeting app; incorrect arithmetic is a product-defining bug. Binary floating point
cannot represent `0.10` exactly, so `0.1 + 0.2 != 0.3`. Errors accumulate across sums,
allocations, and repeated rounding.

The legacy app used Sequelize `DECIMAL` in MySQL but passed values through JavaScript numbers —
IEEE-754 doubles — on the way in and out.

## Decision

- **Java:** a `Money` value object wrapping `BigDecimal amount` + `Currency currency`.
- **Database:** `NUMERIC(19,4)`.
- **Wire format:** a JSON **string** (`"1234.56"`), never a JSON number.
- `float` and `double` are banned for monetary values, including in tests and display code.
- Cross-currency arithmetic throws rather than coercing.
- Rounding is always explicit — `RoundingMode.HALF_EVEN` — and a split must reconcile exactly
  to the original amount, with the remainder allocated deterministically.

Scale 4 rather than 2 so intermediate results (proportional allocation, interest, splitting a
bill three ways) keep precision until a final, explicit rounding step.

## Alternatives considered

| Option | Why not |
|---|---|
| `long` minor units (cents) | Genuinely good and avoids decimals entirely, but makes sub-cent intermediates awkward and every read/write site must remember the scale. `NUMERIC` gives exactness without that discipline |
| `double` | Wrong. Not a real option for money |
| A money library (Joda-Money / JSR-354) | Reasonable, but a small `Money` record is a few dozen lines and avoids a dependency plus its JPA and Jackson integration |

## Consequences

**Good:** exact arithmetic end to end. Currency is part of the type, so mixing currencies is a
compile-or-runtime error rather than a silent wrong number.

**Bad / costs:** `BigDecimal` is verbose and `equals` compares scale (`1.10 != 1.1`) — `Money`
must normalize scale and use `compareTo`. Custom JPA `AttributeConverter` and Jackson
serializer/deserializer needed. Frontend must treat amounts as strings and use a decimal
library rather than `parseFloat`.

**Follow-ups:** `Money`, its converter, and its serializers land in `common/` with the first
feature that stores an amount, with tests covering scale, rounding, and currency mismatch.
