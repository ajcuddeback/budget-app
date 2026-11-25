import { MessageKey } from '../../../core/i18n/messages';

/**
 * The shapes `GoalController` sends and accepts (`docs/features/goals.md`, API).
 *
 * **Every amount is a string** (ADR-0006) and travels beside its currency. Nothing here is a
 * `number` except the `version` a write sends back. `requiredMonthly` alone is at the currency's
 * own minor-unit scale — `"163.13"`, or `"16313"` for yen — because it is an amount somebody will
 * type; every other amount is at the server's storage scale.
 *
 * **The name and the month are separate fields.** The canvas's *"Japan, Nov 2027"* is composed here
 * from an i18n message, so a right-to-left locale can reorder it (ADR-0023).
 */

/** Why a projected figure is absent. Never an error, and each has its own sentence. */
export type ProjectionReason =
  | 'no-contribution'
  | 'beyond-horizon'
  | 'reached'
  | 'no-target-month'
  | 'target-month-passed'
  | 'not-reachable-by-target-month';

/** This month's line: what the plan asks of it, what was recorded, and what is still to come. */
export interface ThisPeriod {
  /** `YYYY-MM` — the server's current month, which is what "this month" means here. */
  period: string;
  /** Zero for a month that began with the goal already reached. */
  planned: string;
  /** Withdrawals included, so it can be negative. */
  recorded: string;
  /** What *Set money aside* opens with. */
  stillToCome: string;
}

export interface GoalProjection {
  /** `YYYY-MM`, or `null` with {@link projectedReason}. */
  projectedMonth: string | null;
  projectedReason: ProjectionReason | null;
  /** At the currency's minor-unit scale, or `null` with {@link requiredReason}. */
  requiredMonthly: string | null;
  requiredReason: ProjectionReason | null;
}

/** One goal, the same shape on every route that returns one. */
export interface Goal {
  id: string;
  name: string;
  currency: string;
  targetAmount: string;
  /** `YYYY-MM`, or `null` — a goal with no month is normal. */
  targetMonth: string | null;
  startingAmount: string;
  /** Never clamped: over the target when over-funded, negative after a large withdrawal. */
  saved: string;
  remaining: string;
  reached: boolean;
  overdue: boolean;
  /** Where the money is kept. Informational only: never the source of `saved`. */
  accountId: string | null;
  thisPeriod: ThisPeriod;
  /** The plan's step in force this month. */
  monthlyContribution: string;
  projection: GoalProjection;
  archived: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export type GoalPage = Page<Goal>;

/** One step of the monthly plan: from this month on, this much. */
export interface PlanStep {
  fromPeriod: string;
  monthlyAmount: string;
  currency: string;
}

/** One recorded contribution. A negative amount is a withdrawal and is shown as one. */
export interface Contribution {
  id: string;
  goalId: string;
  /** `YYYY-MM-DD`. */
  date: string;
  /** The month it counts towards. */
  period: string;
  amount: string;
  currency: string;
  note: string | null;
  transactionId: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}

/** The add form's preview: the figures a goal would have, with nothing stored. */
export interface DraftProjection {
  currency: string;
  saved: string;
  remaining: string;
  reached: boolean;
  overdue: boolean;
  thisPeriod: ThisPeriod;
  monthlyContribution: string;
  projection: GoalProjection;
}

/**
 * `POST /api/goals`. No household and no user: both come from the caller's membership.
 * `currency` is never sent beside `accountId`; the account owns that fact.
 */
export interface CreateGoalRequest {
  name: string;
  targetAmount: string;
  targetMonth?: string;
  startingAmount?: string;
  monthlyContribution?: string;
  currency?: string;
  accountId?: string;
}

/**
 * `PATCH /api/goals/{id}`. **Three-way** on `targetMonth` and `accountId`: an absent key leaves it
 * alone and `null` clears it. Built in `goal-patch.ts`, where the difference is tested.
 */
export interface UpdateGoalRequest {
  name?: string;
  targetAmount?: string;
  targetMonth?: string | null;
  startingAmount?: string;
  accountId?: string | null;
  version: number;
}

export interface SetPlanRequest {
  monthlyAmount: string;
  version: number;
}

/** Signed: a negative amount is a withdrawal. The currency is the goal's and is not sent. */
export interface CreateContributionRequest {
  amount: string;
  date: string;
  note?: string;
}

/** What the goal-bound preview substitutes. An absent key keeps the goal's own value. */
export interface GoalPreviewRequest {
  monthlyAmount?: string;
  targetAmount?: string;
  startingAmount?: string;
  targetMonth?: string | null;
}

export interface DraftPreviewRequest {
  currency?: string;
  targetAmount: string;
  startingAmount?: string;
  targetMonth?: string;
  monthlyAmount?: string;
}

const REASONS: readonly ProjectionReason[] = [
  'no-contribution',
  'beyond-horizon',
  'reached',
  'no-target-month',
  'target-month-passed',
  'not-reachable-by-target-month',
];

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isReason(value: unknown): value is ProjectionReason | null {
  return value === null || (typeof value === 'string' && REASONS.some((one) => one === value));
}

function isTextOrNull(value: unknown): boolean {
  return value === null || typeof value === 'string';
}

/**
 * A projection the screen can state. `unknown` plus a guard, because a preview's figures go on
 * screen beside the words "a month gets you there", and a body that is merely JSON-shaped would
 * render as a sentence with blanks in it.
 */
export function isProjection(value: unknown): value is GoalProjection {
  return (
    isRecord(value) &&
    isTextOrNull(value['projectedMonth']) &&
    isReason(value['projectedReason']) &&
    isTextOrNull(value['requiredMonthly']) &&
    isReason(value['requiredReason'])
  );
}

/** A preview answer with every figure a dialog states. */
export function isPreviewAnswer(
  value: unknown,
): value is Pick<
  Goal,
  'currency' | 'saved' | 'monthlyContribution' | 'projection' | 'reached' | 'overdue'
> {
  return (
    isRecord(value) &&
    typeof value['currency'] === 'string' &&
    typeof value['reached'] === 'boolean' &&
    typeof value['overdue'] === 'boolean' &&
    typeof value['saved'] === 'string' &&
    typeof value['monthlyContribution'] === 'string' &&
    isProjection(value['projection'])
  );
}

/** The sentence for a reason the projected month is absent. Exhaustive: never a blank. */
export function projectedReasonKey(reason: ProjectionReason): MessageKey {
  switch (reason) {
    case 'no-contribution':
      return 'goals.reason.noContribution';
    case 'beyond-horizon':
      return 'goals.reason.beyondHorizon';
    case 'reached':
      return 'goals.reason.reached';
    case 'no-target-month':
      return 'goals.reason.noTargetMonth';
    case 'target-month-passed':
      return 'goals.reason.targetMonthPassed';
    case 'not-reachable-by-target-month':
      return 'goals.reason.notReachable';
  }
}

/** The sentence for a reason the required figure is absent. Exhaustive: never a blank. */
export function requiredReasonKey(reason: ProjectionReason): MessageKey {
  switch (reason) {
    case 'no-contribution':
      return 'goals.required.noContribution';
    case 'beyond-horizon':
      return 'goals.required.beyondHorizon';
    case 'reached':
      return 'goals.reason.reached';
    case 'no-target-month':
      return 'goals.required.noTargetMonth';
    case 'target-month-passed':
      return 'goals.required.targetMonthPassed';
    case 'not-reachable-by-target-month':
      return 'goals.reason.notReachable';
  }
}
