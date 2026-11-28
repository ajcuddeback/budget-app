/**
 * The shapes `CheckInController` sends and accepts (`docs/features/weekly-check-in.md`, API).
 *
 * **Every amount is a {@link Money}**: a decimal string beside its currency (ADR-0006,
 * `MoneyJsonModule`). Nothing here is a `number` except counts and the `version` a write sends
 * back. The browser does no money arithmetic: every figure, the step 4 previews included, is the
 * server's.
 *
 * **No sentence is pre-composed.** Names arrive beside their ids, and `check-in-text.ts` builds every
 * sentence from an i18n message with parameters (ADR-0023).
 */

export interface Money {
  amount: string;
  currency: string;
}

/** A member, by id and display name. The name is `null` for a member who has been removed. */
export interface Person {
  id: string;
  name: string | null;
}

export type CheckInStatus = 'NOT_STARTED' | 'DRAFT' | 'COMPLETED';

export type StepName = 'WHATS_NEW' | 'CATEGORIZE' | 'BILLS' | 'NEXT_WEEK';

export type ItemKind =
  'OVERDUE_BILL' | 'UNUSUAL_CHARGE' | 'CATEGORIZE' | 'BILL_CHARGE' | 'ALLOCATION';

export type AnswerChoice =
  | 'MARK_PAID'
  | 'SKIP'
  | 'PREPAID'
  | 'CATEGORY'
  | 'ATTACH'
  | 'NOT_A_BILL'
  | 'LEAVE'
  | 'DEBT'
  | 'GOAL'
  | 'ROLL_OVER';

/** Why step 3 is asking about a charge, or how a rule pre-answered it. */
export type ChargeReason = 'RULE_MATCH' | 'RULE_OUTSIDE_TOLERANCE' | 'RECURRING';

export type OccurrenceStatus = 'DUE' | 'PAID' | 'SKIPPED';

/** What a rule would answer. Derived on every read; a read never writes one into the draft. */
export interface PreAnswer {
  choice: AnswerChoice;
  categoryId: string | null;
  categoryName: string | null;
  billId: string | null;
  billName: string | null;
  dueDate: string | null;
}

export interface Answer {
  choice: AnswerChoice;
  categoryId: string | null;
  categoryName: string | null;
  billId: string | null;
  billName: string | null;
  dueDate: string | null;
  goalId: string | null;
  debtId: string | null;
  amount: Money | null;
  remember: boolean;
  recordDebtPayment: boolean;
  byRule: boolean;
  answeredBy: Person | null;
  version: number;
}

export interface DebtPrompt {
  debtId: string;
  debtName: string;
}

/** One item. Which fields are set depends on its kind; the rest are `null`. */
export interface Item {
  itemKey: string;
  kind: ItemKind;
  /** `false` for an item a rule pre-answered: shown and changeable, but not asked. */
  question: boolean;
  transactionId: string | null;
  transactionVersion: number | null;
  date: string | null;
  amount: Money | null;
  /** From a bank file: rendered in its own `dir="auto"` element, never inside a sentence. */
  payeeName: string | null;
  accountId: string | null;
  accountName: string | null;
  accountLastFour: string | null;
  billId: string | null;
  billName: string | null;
  dueDate: string | null;
  expected: Money | null;
  usual: Money | null;
  /** `k` for a prepaid offer, or `null` when only "Leave it" is offered. */
  multiple: number | null;
  skips: number | null;
  reason: ChargeReason | null;
  suggestedBillId: string | null;
  suggestedDueDate: string | null;
  debtPrompt: DebtPrompt | null;
  ruleCategoryId: string | null;
  ruleCategoryName: string | null;
  preAnswer: PreAnswer | null;
  answer: Answer | null;
}

/** Step 1's income line: information, never a question. `transactionId` is null until it lands. */
export interface IncomeLine {
  incomeSourceId: string;
  name: string;
  expectedDate: string;
  expected: Money;
  transactionId: string | null;
  date: string | null;
  actual: Money | null;
  accountId: string | null;
  accountName: string | null;
  accountLastFour: string | null;
}

/** One row of step 3's table, from the same set Bills & Income reads. */
export interface OccurrenceLine {
  billId: string;
  billName: string;
  dueDate: string;
  status: OccurrenceStatus;
  amount: Money;
  overdue: boolean;
}

/** A bill the "Different bill" picker offers, with the dates a charge may attach to. */
export interface BillChoice {
  billId: string;
  name: string;
  expected: Money;
  amountVaries: boolean;
  tolerance: string | null;
  dueDates: string[];
}

/** Step 4's figures, every one of them the server's. */
export interface Figures {
  weekTarget: Money | null;
  weekSpending: Money;
  surplus: Money | null;
  free: Money;
  base: Money;
  basePerDay: Money;
  rolledOver: Money | null;
  rolledPerDay: Money | null;
  /** `false` when the month is already short, so only rolling over is offered. */
  surplusAllocatable: boolean;
  /** The week is not over yet, so its spending is "so far". */
  soFar: boolean;
}

/** One place the surplus can go, with figures from the real write paths' previews. */
export interface Choice {
  choice: AnswerChoice;
  debtId: string | null;
  debtName: string | null;
  /** `YYYY-MM`: months, never days. */
  payoffMonthNow: string | null;
  payoffMonthWith: string | null;
  goalId: string | null;
  goalName: string | null;
  savedNow: Money | null;
  savedWith: Money | null;
  target: Money | null;
  /**
   * For a debt: this month's interest the payment would carry, because it would be the debt's
   * first payment this month (ADR-0043). Null when the month's interest is already charged.
   */
  monthInterest?: Money | null;
}

export interface DueLine {
  billId: string;
  billName: string;
  dueDate: string;
}

export interface Streak {
  weeks: number;
  /** `YYYY-MM`, both `null` unless both exist and differ. */
  debtFreeFrom: string | null;
  debtFreeTo: string | null;
}

/** One shown step. The fields a step does not use are `null`. */
export interface Step {
  step: StepName;
  answered: boolean;
  questions: number;
  answeredCount: number;
  items: Item[];
  income: IncomeLine[] | null;
  /** Uncategorised rows beyond the 200 shown (step 2). */
  more: number | null;
  weekSpending: Money | null;
  bills: OccurrenceLine[] | null;
  stillToPay: Money | null;
  billChoices: BillChoice[] | null;
  figures: Figures | null;
  choices: Choice[] | null;
  dueNextWeek: DueLine[] | null;
  streak: Streak | null;
}

export interface Snapshot {
  weekTarget: Money | null;
  weekSpending: Money;
  rolledOver: Money | null;
  nextWeekTarget: Money;
  debtFreeMonth: string | null;
}

/** One record a completed check-in wrote, with the names its summary needs. */
export interface Outcome {
  kind: ItemKind;
  choice: AnswerChoice;
  byRule: boolean;
  transactionId: string | null;
  payeeName: string | null;
  categoryId: string | null;
  categoryName: string | null;
  billId: string | null;
  billName: string | null;
  dueDate: string | null;
  goalId: string | null;
  goalName: string | null;
  debtId: string | null;
  debtName: string | null;
  amount: Money | null;
  remember: boolean;
  recordDebtPayment: boolean;
}

/** One week, as every caller reads it. */
export interface Week {
  /** `2026-W34`. */
  week: string;
  weekStart: string;
  weekEnd: string;
  windowStart: string;
  status: CheckInStatus;
  /** The draft's; `null` before a draft exists. */
  version: number | null;
  /** What completion sends back; `null` on a completed week. */
  basis: string | null;
  startedBy: Person | null;
  completedAt: string | null;
  completedBy: Person | null;
  currency: string;
  otherCurrencyRows: number;
  byRule: { categorized: number; charges: number };
  steps: Step[];
  snapshot: Snapshot | null;
  outcome: Outcome[];
}

/** One row of the history. */
export interface HistoryEntry {
  week: string;
  status: CheckInStatus;
  completedAt: string | null;
  currency: string | null;
  snapshot: Snapshot | null;
  allocation: AnswerChoice | null;
  allocationTargetId: string | null;
}

/** An answer body, exactly as the API takes it for each kind of key. */
export type AnswerBody =
  | { choice: 'PREPAID' | 'LEAVE' | 'SKIP' | 'NOT_A_BILL'; version?: number }
  | { choice: 'MARK_PAID'; recordDebtPayment: boolean; version?: number }
  | { choice: 'CATEGORY'; categoryId: string; remember: boolean; version?: number }
  | {
      choice: 'ATTACH';
      billId: string;
      dueDate: string;
      remember: boolean;
      recordDebtPayment: boolean;
      version?: number;
    }
  | { choice: 'DEBT'; debtId: string; amount: string; version?: number }
  | { choice: 'GOAL'; goalId: string; amount: string; version?: number }
  | { choice: 'ROLL_OVER'; amount: string; version?: number };

export interface PayeeRule {
  id: string;
  payeeId: string;
  payeeName: string;
  categoryId: string;
  categoryName: string;
  categoryArchived: boolean;
}

export interface ChargeRule {
  id: string;
  billId: string;
  billName: string;
  payeeId: string;
  payeeName: string;
  accountId: string;
  accountName: string;
  accountLastFour: string | null;
}

/** One item a stale refusal names, with why (`check-in-stale`, `params.items`). */
export interface StaleItem {
  itemKey: string | null;
  reason: string;
}
