import { MessageKey } from '../../../core/i18n/messages';
import { Schedule, ScheduleRequest } from './schedule.models';

/**
 * What a household has recorded about one occurrence.
 *
 * **There is no `OVERDUE`.** Overdue is `DUE` with a due date in the past, derived per request
 * (ADR-0025) — a stored flag would need a job to stay true and would therefore eventually be
 * false.
 */
export const OCCURRENCE_STATUSES = ['DUE', 'PAID', 'SKIPPED'] as const;

export type OccurrenceStatus = (typeof OCCURRENCE_STATUSES)[number];

const STATUS_KEY: Readonly<Record<OccurrenceStatus, MessageKey>> = {
  DUE: 'bills.status.DUE',
  PAID: 'bills.status.PAID',
  SKIPPED: 'bills.status.SKIPPED',
};

/** How a status was reached. Slice 7 only ever writes `MANUAL` (ADR-0032). */
export const STATUS_SOURCES = ['MANUAL', 'AUTO_MATCHED'] as const;

export type StatusSource = (typeof STATUS_SOURCES)[number];

const SOURCE_KEY: Readonly<Record<StatusSource, MessageKey>> = {
  MANUAL: 'bills.source.MANUAL',
  AUTO_MATCHED: 'bills.source.AUTO_MATCHED',
};

export function isOccurrenceStatus(value: string): value is OccurrenceStatus {
  return (OCCURRENCE_STATUSES as readonly string[]).includes(value);
}

export function occurrenceStatusKey(status: string): MessageKey {
  return isOccurrenceStatus(status) ? STATUS_KEY[status] : 'bills.status.unknown';
}

export function statusSourceKey(source: string): MessageKey {
  return (STATUS_SOURCES as readonly string[]).includes(source)
    ? SOURCE_KEY[source as StatusSource]
    : 'bills.source.unknown';
}

/**
 * One occurrence of a bill, on one date.
 *
 * **A bill has 0..n of these in a month** — four or five for a weekly one, none for an
 * `IRREGULAR` one or a `YEARLY` one outside its month. Each is its own thing to tick off, and is
 * addressed by its `dueDate`, because for most of its life it has no row at all (ADR-0032).
 *
 * Every amount is a string and stays one (ADR-0006).
 */
export interface BillOccurrence {
  billId: string;
  /** `YYYY-MM-DD`. The identity of the occurrence, and what the write path addresses. */
  dueDate: string;
  expectedAmount: string;
  status: OccurrenceStatus;
  /** What was really paid. `null` until somebody records a payment. */
  actualAmount: string | null;
  paidDate: string | null;
  transactionId: string | null;
  statusSource: StatusSource;
  /**
   * Whether anything is stored.
   *
   * `false` is the common case and is **not** an error: the occurrence is `DUE` because that is
   * what the schedule implies, not because anybody said so. "Forget" is only offered on a
   * recorded one — there is nothing to forget about a projection.
   */
  recorded: boolean;
  /** Derived per request: `dueDate` in the past and still `DUE`. */
  overdue: boolean;
  /** Paid further from what was budgeted than the bill says is unremarkable. */
  surprising: boolean;
  /** `null` for a projected occurrence, which has no row to be stale against. */
  version: number | null;
}

/** One bill, exactly as `BillResponse` sends it. */
export interface Bill {
  id: string;
  name: string;
  categoryId: string | null;
  categoryName: string | null;
  categoryParentName: string | null;
  expectedAmount: string;
  /** The household's base currency. Every bill shares it, so totals are safe (ADR-0022). */
  currency: string;
  schedule: Schedule;
  amountVaries: boolean;
  /** `null` unless the amount varies. The two move together, in the form and in a patch. */
  tolerance: string | null;
  /** A retired bill stops projecting and keeps every period already recorded. */
  active: boolean;
  /** This month's, in date order. Empty is ordinary — see {@link BillOccurrence}. */
  occurrences: BillOccurrence[];
  /**
   * The charge rules a weekly check-in created for this bill: *"COMCAST XFINITY · Chase •4412"*.
   * Optional so a fixture written before slice 14 still type-checks; absent reads as none.
   */
  mappings?: BillMapping[];
  createdAt: string;
  updatedAt: string;
  version: number;
}

/** A payee and account a check-in remembered as paying this bill. Removed with Unmap. */
export interface BillMapping {
  ruleId: string;
  /** From a bank file: rendered in its own `dir="auto"` element. */
  payeeName: string;
  accountId: string;
  accountName: string;
  accountLastFour: string | null;
}

/** One month's bills and what they come to. */
export interface BillsPage {
  content: Bill[];
  /** The month's bills in all, paid ones included at what was really paid. */
  total: string;
  /** What is still to pay. Reported alongside {@link total}, never in place of it. */
  outstanding: string;
  overdue: string;
  currency: string;
}

export interface CreateBillRequest {
  name: string;
  expectedAmount: string;
  schedule: ScheduleRequest;
  categoryId?: string;
  amountVaries?: boolean;
  /** Required when `amountVaries`, refused when not: the server will not guess either way. */
  tolerance?: string;
}

/**
 * A partial update. An absent property means "leave it alone".
 *
 * `categoryId` is three-way: absent keeps it, `null` clears it, a value sets it. `amountVaries`
 * and `tolerance` move together — a tolerance without the flag is a `400`.
 */
export interface UpdateBillRequest {
  version: number;
  name?: string;
  expectedAmount?: string;
  schedule?: ScheduleRequest;
  categoryId?: string | null;
  amountVaries?: boolean;
  tolerance?: string;
  active?: boolean;
}

/**
 * What a household is recording about one occurrence.
 *
 * **No version**: the URL is valid before anything exists at it, and recording the same status
 * twice is the same request twice (ADR-0032). `actualAmount` and `paidDate` belong to `PAID` and
 * are both optional there — the one-tap case is "I paid it", which means what was budgeted.
 */
export interface RecordStatusRequest {
  status: OccurrenceStatus;
  actualAmount?: string;
  paidDate?: string;
}

/** Where an income figure came from. Only `MANUAL_ESTIMATE` is written until slice 13. */
export const SOURCES_OF_TRUTH = ['MANUAL_ESTIMATE', 'AUTO'] as const;

export type SourceOfTruth = (typeof SOURCES_OF_TRUTH)[number];

const TRUTH_KEY: Readonly<Record<SourceOfTruth, MessageKey>> = {
  MANUAL_ESTIMATE: 'bills.truth.MANUAL_ESTIMATE',
  AUTO: 'bills.truth.AUTO',
};

export function sourceOfTruthKey(source: string): MessageKey {
  return (SOURCES_OF_TRUTH as readonly string[]).includes(source)
    ? TRUTH_KEY[source as SourceOfTruth]
    : 'bills.truth.unknown';
}

/**
 * One income source, with what it is worth in the month asked about.
 *
 * **Income projects no occurrences**: nobody ticks their salary off, they notice it arrived. What
 * a month has is `dates` — **three for a fortnightly wage in some months**, which is the month
 * the household actually has and not a figure to be averaged flat.
 */
export interface IncomeSource {
  id: string;
  name: string;
  /** What arrives each time. Not the month's total — see {@link expectedForPeriod}. */
  amount: string;
  currency: string;
  schedule: Schedule;
  accountId: string | null;
  accountName: string | null;
  sourceOfTruth: SourceOfTruth;
  active: boolean;
  /** When it arrives this month, `YYYY-MM-DD`. */
  dates: string[];
  /** `amount` times the number of arrivals, computed on the server. */
  expectedForPeriod: string;
  createdAt: string;
  updatedAt: string;
  version: number;
}

/** One month's income sources and what they come to. An expectation, not money that arrived. */
export interface IncomeList {
  content: IncomeSource[];
  total: string;
  currency: string;
}

export interface CreateIncomeRequest {
  name: string;
  amount: string;
  schedule: ScheduleRequest;
  accountId?: string;
}

export interface UpdateIncomeRequest {
  version: number;
  name?: string;
  amount?: string;
  schedule?: ScheduleRequest;
  accountId?: string | null;
  active?: boolean;
}

/**
 * The **five** terms and the subtraction — the figure the page exists for.
 *
 * **`left` goes negative** and this client renders that rather than clamping it: "you are £180
 * short this month" is the information. The terms are shown beneath it so that a plan counted
 * twice — rent as a bill *and* as an envelope — is visible rather than hidden.
 *
 * **The fourth term is `debtMinimumsNotInBills`** (`debt-plan.md`, decision 2), which changed a
 * figure that had already shipped. Only the minimums *not* already carried by a bill row come off,
 * so a card's minimum is counted once; the rest is already inside `bills`.
 *
 * **The fifth term is `goalsSetAside`** (`goals.md`, decision 2), which changed the figure again in
 * slice 11. A household with no goals has a fifth term of zero.
 *
 * **`owed` is never part of the subtraction.** It is a balance, not a monthly commitment, and
 * taking it off would tell a household owing £26,000 that it has nothing to spend this month.
 */
export interface PeriodSummary {
  /** `YYYY-MM`. */
  period: string;
  income: string;
  /** The month's bills, paid or not: a paid bill is not money still available. */
  bills: string;
  billsOutstanding: string;
  billsOverdue: string;
  envelopesAllocated: string;
  /** The total debt balance — the **Owed** card. Not subtracted; see the type's own notes. */
  owed: string;
  /**
   * How many debts there are, **in every currency**.
   *
   * `owed`, `debtMinimums` and `debtMinimumsNotInBills` are the household's base currency only
   * (ADR-0022), so a household with a foreign-currency debt gets a count and a total that do not
   * describe the same set. The card says so rather than implying they match.
   */
  debtCount: number;
  /** Every active debt's minimum, in the base currency. Reported so the fourth term adds up. */
  debtMinimums: string;
  /** The only debt figure `left` loses: the minimums no bill row already carries this month. */
  debtMinimumsNotInBills: string;
  /**
   * The fifth term (`goals.md`, decision 2): what the month's goals set aside — each unarchived,
   * base-currency goal counting the greater of its plan and what was recorded. `left` loses it.
   */
  goalsSetAside: string;
  /** How many goals that figure was summed over. */
  goalsCounted: number;
  /**
   * What the debt plan puts towards debt beyond its minimums, or `null` with no plan or one below
   * them. **Reported, never subtracted** (`goals.md`, decision 3).
   */
  debtPlanExtra: string | null;
  /** Compared on the server, so this client compares no amounts to say it. */
  debtPlanExtraExceedsLeft: boolean;
  /** Negative when the month does not add up. Never clamp it. */
  left: string;
  currency: string;
}

/** Whether a figure the server sent is negative, read from its sign and never from a parse. */
export function isNegative(amount: string): boolean {
  return amount.trimStart().startsWith('-');
}
