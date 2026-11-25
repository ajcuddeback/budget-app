import { MessageKey, MessageParams } from '../../../core/i18n/messages';
import { isAmount, sameAmount } from '../../../core/money';
import {
  CreateContributionRequest,
  CreateGoalRequest,
  DraftPreviewRequest,
  Goal,
  GoalPreviewRequest,
  GoalProjection,
  UpdateGoalRequest,
  projectedReasonKey,
  requiredReasonKey,
} from './goal.models';

/**
 * Turning what somebody typed into a request, and a projection into sentences.
 *
 * Kept out of the components so that the two decisions most likely to be wrong silently — **what a
 * `PATCH` omits versus what it sends as `null`**, and **which sentence a reason code gets** — are
 * made once and tested directly. Nothing here does arithmetic on money: amounts are compared with
 * `sameAmount` (exact `bigint`) and passed on as the strings they were typed as.
 */

/** A translated sentence waiting for its parameters — the template does no logic. */
export interface Said {
  readonly key: MessageKey;
  readonly params?: MessageParams;
}

/** The add and edit dialogs' fields, as typed. Empty strings mean "nothing entered". */
export interface GoalFormValue {
  name: string;
  targetAmount: string;
  /** `YYYY-MM` from a month input, or `''`. */
  targetMonth: string;
  startingAmount: string;
  monthlyContribution: string;
  currency: string;
  accountId: string;
}

/** The set-aside dialog's fields. The amount is unsigned; the direction carries the sign. */
export interface ContributionFormValue {
  amount: string;
  date: string;
  note: string;
}

export type Direction = 'in' | 'out';

/** `POST /api/goals`, with nothing sent that was not entered. */
export function buildCreateGoal(form: GoalFormValue): CreateGoalRequest {
  const request: CreateGoalRequest = {
    name: form.name.trim(),
    targetAmount: form.targetAmount.trim(),
  };
  if (form.targetMonth !== '') {
    request.targetMonth = form.targetMonth;
  }
  if (form.startingAmount.trim() !== '') {
    request.startingAmount = form.startingAmount.trim();
  }
  if (form.monthlyContribution.trim() !== '') {
    request.monthlyContribution = form.monthlyContribution.trim();
  }
  // The account owns its currency, and the server refuses the two together rather than guessing
  // which was meant. So exactly one of them is ever sent.
  if (form.accountId !== '') {
    request.accountId = form.accountId;
  } else if (form.currency !== '') {
    request.currency = form.currency;
  }
  return request;
}

/**
 * `PATCH /api/goals/{id}` with **only what changed**.
 *
 * Three-way on the month and the account: an untouched field is *absent*, a cleared one is
 * `null`, and a new one is the value. Sending every field would be correct today and wrong the
 * moment two people edit the same goal — the second save would quietly put back the first one's
 * month.
 */
export function buildGoalPatch(goal: Goal, form: GoalFormValue): UpdateGoalRequest {
  const patch: UpdateGoalRequest = { version: goal.version };
  const name = form.name.trim();
  if (name !== goal.name) {
    patch.name = name;
  }
  if (!sameAmount(form.targetAmount.trim(), goal.targetAmount)) {
    patch.targetAmount = form.targetAmount.trim();
  }
  const startingAmount = form.startingAmount.trim() === '' ? '0' : form.startingAmount.trim();
  if (!sameAmount(startingAmount, goal.startingAmount)) {
    patch.startingAmount = startingAmount;
  }
  const month = form.targetMonth === '' ? null : form.targetMonth;
  if (month !== goal.targetMonth) {
    patch.targetMonth = month;
  }
  const account = form.accountId === '' ? null : form.accountId;
  if (account !== goal.accountId) {
    patch.accountId = account;
  }
  return patch;
}

/** Whether a patch would change nothing, so the dialog can close without a request. */
export function patchIsEmpty(patch: UpdateGoalRequest): boolean {
  return Object.keys(patch).every((key) => key === 'version');
}

/**
 * What the edit dialog asks the server to project: the goal with the typed target, month and
 * starting amount substituted. `null` while an amount is half-typed — a question the server would
 * refuse is not worth asking.
 */
export function buildEditPreview(goal: Goal, form: GoalFormValue): GoalPreviewRequest | null {
  const target = form.targetAmount.trim();
  const start = form.startingAmount.trim() === '' ? '0' : form.startingAmount.trim();
  if (!isAmount(target) || !isAmount(start)) {
    return null;
  }
  const request: GoalPreviewRequest = {};
  if (!sameAmount(target, goal.targetAmount)) {
    request.targetAmount = target;
  }
  if (!sameAmount(start, goal.startingAmount)) {
    request.startingAmount = start;
  }
  const month = form.targetMonth === '' ? null : form.targetMonth;
  if (month !== goal.targetMonth) {
    request.targetMonth = month;
  }
  return request;
}

/** What the add dialog asks before the goal exists, or `null` until there is a target. */
export function buildDraftPreview(form: GoalFormValue): DraftPreviewRequest | null {
  const target = form.targetAmount.trim();
  const start = form.startingAmount.trim();
  const monthly = form.monthlyContribution.trim();
  if (!isAmount(target) || (start !== '' && !isAmount(start))) {
    return null;
  }
  if (monthly !== '' && !isAmount(monthly)) {
    return null;
  }
  const request: DraftPreviewRequest = { targetAmount: target };
  if (form.currency !== '') {
    request.currency = form.currency;
  }
  if (start !== '') {
    request.startingAmount = start;
  }
  if (form.targetMonth !== '') {
    request.targetMonth = form.targetMonth;
  }
  if (monthly !== '') {
    request.monthlyAmount = monthly;
  }
  return request;
}

/**
 * A contribution, signed by the direction the household chose.
 *
 * **Taking money out is its own dialog, not a minus sign typed into the first one**, so the field
 * is unsigned and the sign is added here by string surgery — a `-` in front of digits that were
 * already validated. No arithmetic, and nothing parsed.
 */
export function buildContribution(
  form: ContributionFormValue,
  direction: Direction,
): CreateContributionRequest {
  const amount = form.amount.trim();
  const request: CreateContributionRequest = {
    amount: direction === 'out' ? `-${amount}` : amount,
    date: form.date,
  };
  if (form.note.trim() !== '') {
    request.note = form.note.trim();
  }
  return request;
}

/** Months from `from` to `to`, both `YYYY-MM`: date arithmetic, which the client may do. */
export function monthsBetween(from: string | null, to: string | null): number | null {
  const start = monthIndex(from);
  const end = monthIndex(to);
  return start === null || end === null ? null : end - start;
}

/** The month after `YYYY-MM`. */
export function nextMonth(period: string): string {
  const index = monthIndex(period);
  if (index === null) {
    return period;
  }
  const next = index + 1;
  return `${Math.floor(next / 12)}-${`${(next % 12) + 1}`.padStart(2, '0')}`;
}

function monthIndex(period: string | null): number | null {
  if (period === null || !/^\d{4}-(0[1-9]|1[0-2])$/.test(period)) {
    return null;
  }
  const [year, month] = period.split('-').map((part) => Number.parseInt(part, 10));
  return year * 12 + (month - 1);
}

/** What the projection sentences need, which a list row, a detail page and a preview all have. */
export interface ProjectionFacts {
  readonly currency: string;
  readonly targetMonth: string | null;
  readonly monthlyContribution: string;
  readonly reached: boolean;
  readonly overdue: boolean;
  readonly projection: GoalProjection;
}

/** How a figure is put into a sentence: the page passes its i18n formatters in. */
export interface Formatters {
  readonly money: (amount: string, currency: string) => string;
  readonly month: (period: string) => string;
}

/**
 * The projection in words — *"At $110 a month you reach it in July 2028 — 8 months after
 * November 2027. $163.13 a month gets you there by November 2027."*
 *
 * **Every reason code has its own sentence and is never shown as a blank.** The figures are the
 * server's; the one thing worked out here is how many months late or early, which is date
 * arithmetic on two month labels and not a subtraction of money.
 */
export function projectionSaid(goal: ProjectionFacts, format: Formatters): readonly Said[] {
  const { projection } = goal;
  if (goal.reached) {
    return [{ key: 'goals.reason.reached' }];
  }
  const monthly = format.money(goal.monthlyContribution, goal.currency);
  const said: Said[] = [];

  if (projection.projectedMonth !== null) {
    const reaches = format.month(projection.projectedMonth);
    if (goal.overdue && goal.targetMonth !== null) {
      said.push({
        key: 'goals.projection.overdueReaches',
        params: { target: format.month(goal.targetMonth), monthly, month: reaches },
      });
    } else if (goal.targetMonth !== null) {
      const late = monthsBetween(goal.targetMonth, projection.projectedMonth) ?? 0;
      said.push(
        late > 0
          ? {
              key: late === 1 ? 'goals.projection.lateOne' : 'goals.projection.late',
              params: {
                monthly,
                month: reaches,
                count: late,
                target: format.month(goal.targetMonth),
              },
            }
          : {
              key: 'goals.projection.onTime',
              params: { monthly, month: reaches, target: format.month(goal.targetMonth) },
            },
      );
    } else {
      said.push({ key: 'goals.projection.reaches', params: { monthly, month: reaches } });
    }
  } else if (projection.projectedReason !== null) {
    if (goal.overdue && goal.targetMonth !== null) {
      said.push({
        key: 'goals.projection.overdue',
        params: { target: format.month(goal.targetMonth) },
      });
    }
    said.push({ key: projectedReasonKey(projection.projectedReason), params: { monthly } });
  }

  if (projection.requiredMonthly !== null && goal.targetMonth !== null) {
    said.push({
      key: 'goals.projection.required',
      params: {
        amount: format.money(projection.requiredMonthly, goal.currency),
        target: format.month(goal.targetMonth),
      },
    });
  } else if (
    projection.requiredReason !== null &&
    projection.requiredReason !== 'no-target-month' &&
    projection.requiredReason !== 'target-month-passed'
  ) {
    // No month and a passed month are already said above, or need no saying on a list row; the
    // rest each get their own sentence.
    said.push({
      key: requiredReasonKey(projection.requiredReason),
      params: { target: goal.targetMonth === null ? '' : format.month(goal.targetMonth) },
    });
  }
  return said;
}
