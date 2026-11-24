import { MessageKey } from '../../../core/i18n/messages';

/**
 * The wire shapes of the debt API, as `DebtResponses.java` sends them.
 *
 * **Every amount and the rate are strings and stay strings** (ADR-0006). Nothing in this feature
 * parses one: the server computes and the client displays, and the only numbers here are counts,
 * rungs and a month stride — things that were never money.
 *
 * **Every solver figure travels with its source.** A figure the app worked out must never be
 * mistaken for one the household asserted, which is what {@link TermSource} is for and why the
 * screens render a `Solved` tag from it rather than guessing from nullability.
 */

/** How a term got its value. `FROM_PLAN` is the payment the ladder allocated. */
export const TERM_SOURCES = ['ENTERED', 'SOLVED', 'FROM_PLAN'] as const;

export type TermSource = (typeof TERM_SOURCES)[number];

const TERM_SOURCE_KEY: Readonly<Record<TermSource, MessageKey>> = {
  ENTERED: 'debt.source.ENTERED',
  SOLVED: 'debt.source.SOLVED',
  FROM_PLAN: 'debt.source.FROM_PLAN',
};

export function termSourceKey(source: string): MessageKey {
  return (TERM_SOURCES as readonly string[]).includes(source)
    ? TERM_SOURCE_KEY[source as TermSource]
    : 'debt.source.unknown';
}

export const DEBT_KINDS = ['CREDIT_CARD', 'INSTALLMENT'] as const;

export type DebtKind = (typeof DEBT_KINDS)[number];

const DEBT_KIND_KEY: Readonly<Record<DebtKind, MessageKey>> = {
  CREDIT_CARD: 'debt.kind.CREDIT_CARD',
  INSTALLMENT: 'debt.kind.INSTALLMENT',
};

export function debtKindKey(kind: string): MessageKey {
  return (DEBT_KINDS as readonly string[]).includes(kind)
    ? DEBT_KIND_KEY[kind as DebtKind]
    : 'debt.kind.unknown';
}

/**
 * Where a balance came from. **The UI never renders two of these identically.**
 *
 * `LINKED_STATEMENT` is not reachable in slice 10 and is listed because the server can send it.
 */
export const BALANCE_SOURCES = ['CONFIRMED', 'ESTIMATED', 'LINKED_STATEMENT'] as const;

export type BalanceSource = (typeof BALANCE_SOURCES)[number];

const BALANCE_SOURCE_KEY: Readonly<Record<BalanceSource, MessageKey>> = {
  CONFIRMED: 'debt.balanceSource.CONFIRMED',
  ESTIMATED: 'debt.balanceSource.ESTIMATED',
  LINKED_STATEMENT: 'debt.balanceSource.LINKED_STATEMENT',
};

export function balanceSourceKey(source: string): MessageKey {
  return (BALANCE_SOURCES as readonly string[]).includes(source)
    ? BALANCE_SOURCE_KEY[source as BalanceSource]
    : 'debt.balanceSource.unknown';
}

/** How a payment row got here. Slice 10 writes the first and the third. */
export const PAYMENT_PROVENANCES = ['USER_LOGGED', 'AUTO_MATCHED', 'STATEMENT_CORRECTION'] as const;

export type PaymentProvenance = (typeof PAYMENT_PROVENANCES)[number];

const PROVENANCE_KEY: Readonly<Record<PaymentProvenance, MessageKey>> = {
  USER_LOGGED: 'debt.provenance.USER_LOGGED',
  AUTO_MATCHED: 'debt.provenance.AUTO_MATCHED',
  STATEMENT_CORRECTION: 'debt.provenance.STATEMENT_CORRECTION',
};

export function provenanceKey(provenance: string): MessageKey {
  return (PAYMENT_PROVENANCES as readonly string[]).includes(provenance)
    ? PROVENANCE_KEY[provenance as PaymentProvenance]
    : 'debt.provenance.unknown';
}

export const PAYOFF_STRATEGIES = ['AVALANCHE', 'SNOWBALL', 'CUSTOM'] as const;

export type PayoffStrategy = (typeof PAYOFF_STRATEGIES)[number];

const STRATEGY_KEY: Readonly<Record<PayoffStrategy, MessageKey>> = {
  AVALANCHE: 'debt.strategy.AVALANCHE',
  SNOWBALL: 'debt.strategy.SNOWBALL',
  CUSTOM: 'debt.strategy.CUSTOM',
};

export function strategyKey(strategy: string): MessageKey {
  return (PAYOFF_STRATEGIES as readonly string[]).includes(strategy)
    ? STRATEGY_KEY[strategy as PayoffStrategy]
    : 'debt.strategy.unknown';
}

export function isPayoffStrategy(value: string): value is PayoffStrategy {
  return (PAYOFF_STRATEGIES as readonly string[]).includes(value);
}

/**
 * Why there is no projection. **Neither of these is an error**: `never-clears` is a fact about the
 * terms, and `plan-below-minimums` is a fact about the household's month.
 */
export const PROJECTION_REASONS = ['never-clears', 'plan-below-minimums'] as const;

export type ProjectionReason = (typeof PROJECTION_REASONS)[number];

const REASON_KEY: Readonly<Record<ProjectionReason, MessageKey>> = {
  'never-clears': 'debt.reason.neverClears',
  'plan-below-minimums': 'debt.reason.belowMinimums',
};

export function projectionReasonKey(reason: string): MessageKey {
  return (PROJECTION_REASONS as readonly string[]).includes(reason)
    ? REASON_KEY[reason as ProjectionReason]
    : 'debt.reason.unknown';
}

/**
 * One debt, exactly as `DebtResponse` sends it.
 *
 * Four of these fields are the ones a screen gets wrong by rendering them as though they were
 * ordinary, and each is called out where it is used:
 *
 * - **`planExtra` can be negative** (ADR-0036) — a debt owing less than its own minimum, cleared
 *   in month 1. `planMinimum + planExtra` is what was really allocated, so the figure is true, but
 *   "extra &minus;$65" is not a sentence.
 * - **`apr` is null with `aprDerivable: false`** for a rate that was neither entered nor can be
 *   worked out. The screen says the rate is not available; it never prints 0%.
 * - **`balanceStale`** means the estimate has run past twelve months. The screen asks for a
 *   statement figure instead of showing a year of extrapolation.
 * - **`progressPercent` is null** when there is no original balance. Absent, never 0%.
 */
export interface Debt {
  id: string;
  name: string;
  kind: DebtKind;
  servicer: string | null;
  /** The debt's own currency — an account-linked debt takes the account's (ADR-0022). */
  currency: string;
  /** Derived: the anchor rolled forward by the payments after it. Never stored. */
  balance: string;
  balanceSource: BalanceSource;
  balanceAsOf: string;
  /** How many months were assumed to reach {@link balance}. Zero for a confirmed figure. */
  estimatedMonths: number;
  /** Past the estimation cap: show the last confirmed figure and ask for a statement. */
  balanceStale: boolean;
  anchorBalance: string;
  anchorDate: string;
  /** `null` when neither entered nor derivable — see {@link aprDerivable}. A percentage. */
  apr: string | null;
  aprSource: TermSource;
  /** `false` with a null `apr` means no rate follows from the figures. Never render 0%. */
  aprDerivable: boolean;
  minimumPayment: string;
  originalBalance: string | null;
  paidOffSoFar: string | null;
  /** A percentage of {@link originalBalance}. `null` when there is none. */
  progressPercent: string | null;
  payment: string | null;
  paymentSource: TermSource;
  monthsLeft: number | null;
  /** `YYYY-MM`. Months, not days — monthly arithmetic cannot be day-accurate. */
  payoffMonth: string | null;
  horizonSource: TermSource;
  totalInterestLeft: string | null;
  /** Set when the projection is null. `never-clears` is the common real case. */
  projectionReason: string | null;
  /** `null` for a debt outside the ladder: archived, settled, or at zero. */
  rung: number | null;
  planMinimum: string | null;
  /** **Can be negative** (ADR-0036). See the type's own notes. */
  planExtra: string | null;
  accountId: string | null;
  billId: string | null;
  customOrder: number | null;
  archived: boolean;
  paidOffAt: string | null;
  paymentCount: number;
  version: number;
}

/**
 * Every debt, with the figures Bills &amp; Income shares. **Not a page envelope**: the plan ranks
 * every debt and these totals are sums over all of them, and the per-household cap is what makes
 * that safe.
 *
 * **`debtCount` spans every currency while `owed` and `debtMinimums` are the household's base
 * currency only** (ADR-0022). A household with a foreign-currency debt therefore gets a count and
 * a total that do not correspond, and a screen must not imply they do.
 *
 * **There is deliberately no `debtMinimumsNotInBills` here.** The split of the minimums depends on
 * which bills a *given month's* bills term actually carries — a retired bill, a yearly bill
 * outside its month and a skipped occurrence all leave `bill_id` in place while contributing
 * nothing to that month — and this endpoint names no month, so it could only answer by guessing.
 * The split lives on `GET /api/periods/{period}/summary`, where it is derived from the very bills
 * the subtraction is made from, so both halves of one sum read one object.
 */
export interface DebtList {
  debts: Debt[];
  strategy: PayoffStrategy;
  /** The base currency `owed` and `debtMinimums` are in. */
  currency: string;
  owed: string;
  debtMinimums: string;
  debtCount: number;
}

/** One point on a chart: what is still owed at the start of that month. */
export interface SeriesPoint {
  /** `YYYY-MM`. */
  month: string;
  balance: string;
}

/**
 * One debt and the two lines its chart draws.
 *
 * `seriesMonthStride` is how many months one point stands for — more than 1 for a plan that runs
 * long, because the line is **thinned rather than truncated**. A client that assumed one point per
 * month would mislabel the axis of exactly the long plan the cap exists for.
 */
export interface DebtDetail {
  debt: Debt;
  pace: SeriesPoint[];
  atMinimum: SeriesPoint[];
  seriesMonthStride: number;
  /** True when paying the minimum *is* the plan, so one line is the whole story. */
  atMinimumIsTheSameLine: boolean;
}

/** One payment, with the split as it was recorded and how the row got here. */
export interface DebtPayment {
  id: string;
  debtId: string;
  paidDate: string;
  currency: string;
  amount: string;
  /** Stored as at the time: a later rate change must not rewrite history. */
  interest: string;
  principal: string;
  balanceAfter: string;
  provenance: PaymentProvenance;
  /** Evidence of a real movement, never the authority for it (ADR-0035). */
  transactionId: string | null;
  billOccurrenceId: string | null;
  note: string | null;
  version: number;
}

/** One page of a debt's history — the envelope every collection returns. */
export interface DebtPaymentPage {
  content: DebtPayment[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** One debt's place in the ladder, and what the plan allocates it in the first month. */
export interface Rung {
  rung: number;
  debtId: string;
  name: string;
  kind: DebtKind;
  servicer: string | null;
  balance: string;
  apr: string | null;
  aprDerivable: boolean;
  minimum: string;
  /** **Negative** where a rung owes less than its minimum and clears at once (ADR-0036). */
  extra: string;
  /** `minimum + extra`, computed by the server. The figure the household will act on. */
  monthlyAmount: string;
  payoffMonth: string | null;
  totalInterest: string | null;
}

/**
 * One strategy's answer. **Both named strategies are projected whichever is selected** — the
 * comparison card is a product requirement, not a view of the selection.
 */
export interface StrategyProjection {
  strategy: PayoffStrategy;
  /** `YYYY-MM`, or null when a debt does not clear — never a date that ignores it. */
  debtFreeMonth: string | null;
  /** When rung 1 clears. Reported on its own because it is snowball's whole argument. */
  firstWinMonth: string | null;
  totalInterest: string | null;
  reason: string | null;
  /** Empty on the two comparison projections: the table is drawn once, for the selection. */
  rungs: Rung[];
  series: SeriesPoint[];
  seriesMonthStride: number;
}

/**
 * One currency's ladder.
 *
 * `monthlyAmount` is what **this group's** projection assumed: the household's figure for its own
 * currency, that group's minimums for any other, because a surplus exists in one currency and
 * cannot be allocated in another without a rate the instance may not have (ADR-0022).
 */
export interface CurrencyGroup {
  currency: string;
  planCurrency: boolean;
  monthlyAmount: string;
  minimums: string;
  surplus: string;
  owed: string;
  /** Accepted, not refused — and no projection, because every figure from it would be a fiction. */
  belowMinimums: boolean;
  selected: StrategyProjection;
  avalanche: StrategyProjection;
  snowball: StrategyProjection;
}

/** The plan: the selection, the order, both projections and the comparison. */
export interface Plan {
  strategy: PayoffStrategy;
  currency: string;
  monthlyAmount: string;
  /** The sum of every ranked debt's minimum, in the base currency. */
  minimums: string;
  /** Zero is the empty state: the page says what it is for rather than charting nothing. */
  debtCount: number;
  mixedCurrency: boolean;
  /** One per currency, the household's base currency first. */
  currencies: CurrencyGroup[];
  version: number;
}

/** A solved term set, with the one field that was derived named. */
export interface SolvedTerms {
  currency: string;
  balance: string;
  apr: string | null;
  payment: string | null;
  monthsLeft: number | null;
  payoffMonth: string | null;
  totalInterest: string | null;
  solvedField: string | null;
  reason: string | null;
  series: SeriesPoint[];
  seriesMonthStride: number;
  bisectionSteps: number;
}

// ------------------------------------------------------------------------------------ requests

/**
 * A debt to record.
 *
 * `apr` is required **unless** a payment and a horizon are both given — the installment-loan case,
 * where the statement names a balance, a payment and a term but never the rate.
 */
export interface CreateDebtRequest {
  name: string;
  kind: DebtKind;
  servicer?: string;
  currency?: string;
  balance: string;
  /** `YYYY-MM-DD` — the date the balance was true, which is what makes it an anchor. */
  balanceDate: string;
  apr?: string;
  minimumPayment: string;
  originalBalance?: string;
  payment?: string;
  monthsLeft?: number;
  /** `YYYY-MM`. One unknown with two faces: sending this *and* `monthsLeft` is a `400`. */
  payoffMonth?: string;
  accountId?: string;
  billId?: string;
}

/**
 * The terms editor's change, and **the three-way `PATCH` this feature turns on**.
 *
 * For every solver field: **absent** leaves it exactly as it was, an **explicit `null`** releases
 * it so the server solves it, and a **value** enters it. The server never infers "absent means
 * release" — a field dropped by accident would silently change what the household believes about
 * its own payoff date, which is why {@link buildDebtPatch} builds this key by key.
 *
 * In TypeScript that distinction is `'apr' in patch` versus `patch.apr === null`, and it survives
 * `JSON.stringify` because an absent property is not serialized while an explicit null is.
 */
export interface UpdateDebtRequest {
  version: number;
  name?: string;
  kind?: DebtKind;
  /** Three-way. A blank string is a clear, which the server treats as a cleared servicer. */
  servicer?: string | null;
  minimumPayment?: string;
  /** Three-way. */
  originalBalance?: string | null;
  /** Three-way. `null` **releases the rate to the solver** — the field becomes `Solved`. */
  apr?: string | null;
  /** Three-way. `null` hands the payment back to the plan's allocation. */
  payment?: string | null;
  /** Three-way, and one unknown with `payoffMonth`: entering one face clears the other. */
  monthsLeft?: number | null;
  /** Three-way. `YYYY-MM`. */
  payoffMonth?: string | null;
  /** Three-way. */
  accountId?: string | null;
  /** Three-way. Clearing it starts counting that minimum into Bills &amp; Income. */
  billId?: string | null;
  /** Three-way. Only `CUSTOM` stores an order. */
  customOrder?: number | null;
}

/** A payment to log. `transactionId` is evidence of a real movement, not authority for it. */
export interface LogPaymentRequest {
  paidDate: string;
  amount: string;
  transactionId?: string;
  billOccurrenceId?: string;
  note?: string;
}

/** A correction to a payment. `transactionId` and `note` are three-way. */
export interface CorrectPaymentRequest {
  version: number;
  paidDate?: string;
  amount?: string;
  transactionId?: string | null;
  note?: string | null;
}

/** What a statement says is owed, and when that was true. A statement wins. */
export interface ConfirmBalanceRequest {
  balance: string;
  balanceDate: string;
  note?: string;
}

/**
 * The plan's two figures and the household's own order.
 *
 * `customOrder` is debt ids from rung 1 down, or **absent to leave every position alone**: the list
 * is the whole order, so a debt it omits has its position cleared.
 */
export interface UpdatePlanRequest {
  version: number;
  strategy?: PayoffStrategy;
  monthlyAmount?: string;
  customOrder?: string[];
}

/**
 * A term set to solve and not save. Exactly one of `apr`, `payment` and the horizon is left out;
 * the balance is always supplied and never solved.
 */
export interface SolveTermsRequest {
  balance: string;
  currency?: string;
  apr?: string;
  payment?: string;
  monthsLeft?: number;
  payoffMonth?: string;
  firstPaymentMonth?: string;
}
