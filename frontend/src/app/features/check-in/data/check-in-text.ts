import { ApiError } from '../../../core/api-error';
import { MessageKey, MessageParams } from '../../../core/i18n/messages';
import {
  AnswerBody,
  Choice,
  DueLine,
  Figures,
  IncomeLine,
  Item,
  Money,
  OccurrenceLine,
  Outcome,
  StaleItem,
  Step,
  StepName,
  Streak,
  Week,
} from './check-in.models';

/**
 * Every sentence the check-in shows, as a message key and its parameters, made here once and tested
 * directly (`check-in-data.spec.ts`). The template does no logic and **nothing here does money
 * arithmetic**: an amount is only ever inspected for its sign, compared exactly, or passed on as
 * the string the server sent.
 *
 * **A payee is never put inside a sentence.** It arrives from a bank file and renders in its own
 * `dir="auto"` element beside the sentence (`file-import.md`, the bidi control), so no sentence
 * here takes a payee parameter.
 */

/** A translated sentence waiting for its parameters. */
export interface Said {
  readonly key: MessageKey;
  readonly params?: MessageParams;
}

/** A summary line, and where the record it describes lives. */
export interface SummaryLine {
  readonly said: Said;
  readonly link: readonly string[] | null;
}

export interface Formatters {
  money(money: Money): string;
  date(iso: string): string;
  month(period: string): string;
  /** "Car payment on Aug 24, Internet on Aug 25 and Visa on Aug 28", in the locale's own list. */
  list(parts: readonly string[]): string;
  /** A message on its own, for the pieces a list or a sentence is built from. */
  t(key: MessageKey, params?: MessageParams): string;
}

/** The four steps, in order: chips, sections and screens all follow it. */
export const STEP_ORDER: readonly StepName[] = ['WHATS_NEW', 'CATEGORIZE', 'BILLS', 'NEXT_WEEK'];

// ------------------------------------------------------------------------------------- amounts

/** Exact: compares digits, never floats. `-0.0000` is not negative. */
export function isNegative(money: Money): boolean {
  return money.amount.trim().startsWith('-') && !isZero(money);
}

export function isZero(money: Money): boolean {
  return /^-?0*(\.0*)?$/.test(money.amount.trim());
}

export function isPositive(money: Money): boolean {
  return !isNegative(money) && !isZero(money);
}

/** The same amount without its sign, by string surgery: a charge reads "$126", not "-$126". */
export function magnitude(money: Money): Money {
  return { amount: money.amount.trim().replace(/^-/, ''), currency: money.currency };
}

/** Exact equality at any scale: "2620", "2620.00" and "2620.0000" are the same amount. */
export function sameAmount(left: Money, right: Money): boolean {
  return left.currency === right.currency && scaled(left.amount) === scaled(right.amount);
}

function scaled(amount: string): bigint | null {
  const trimmed = amount.trim();
  if (!/^-?\d+(\.\d{1,4})?$/.test(trimmed)) {
    return null;
  }
  const negative = trimmed.startsWith('-');
  const [whole, fraction = ''] = (negative ? trimmed.slice(1) : trimmed).split('.');
  const value = BigInt(whole + fraction.padEnd(4, '0'));
  return negative ? -value : value;
}

// ---------------------------------------------------------------------------------- the week

/** `2026-W34` → 34. */
export function weekNumber(week: string): number {
  const match = /^\d{4}-W(\d{2})$/.exec(week);
  return match ? Number(match[1]) : 0;
}

export function stepNameKey(step: StepName): MessageKey {
  switch (step) {
    case 'WHATS_NEW':
      return 'checkIn.step.WHATS_NEW';
    case 'CATEGORIZE':
      return 'checkIn.step.CATEGORIZE';
    case 'BILLS':
      return 'checkIn.step.BILLS';
    case 'NEXT_WEEK':
      return 'checkIn.step.NEXT_WEEK';
  }
}

/** What the footer of a screen says comes next: "Next: categorize". */
export function nextStepKey(step: StepName): MessageKey {
  switch (step) {
    case 'WHATS_NEW':
      return 'checkIn.nextStep.WHATS_NEW';
    case 'CATEGORIZE':
      return 'checkIn.nextStep.CATEGORIZE';
    case 'BILLS':
      return 'checkIn.nextStep.BILLS';
    case 'NEXT_WEEK':
      return 'checkIn.nextStep.NEXT_WEEK';
  }
}

/** The shown steps, in the canvas's order whatever order the server listed them in. */
export function shownSteps(week: Week): Step[] {
  return [...week.steps].sort(
    (left, right) => STEP_ORDER.indexOf(left.step) - STEP_ORDER.indexOf(right.step),
  );
}

/** "2 of 3 answered": counts of the steps shown, never of all four. */
export function progress(week: Week): { answered: number; shown: number } {
  const steps = week.steps;
  return { answered: steps.filter((step) => step.answered).length, shown: steps.length };
}

/** A step's status beside its heading: "Both handled", "1 waiting on you", "Nothing to answer". */
export function stepStatus(step: Step): Said {
  if (step.questions === 0) {
    return { key: 'checkIn.status.nothingToAnswer' };
  }
  if (step.answeredCount >= step.questions) {
    return step.questions === 2
      ? { key: 'checkIn.status.bothHandled' }
      : { key: 'checkIn.status.allHandled' };
  }
  const waiting = step.questions - step.answeredCount;
  return waiting === 1
    ? { key: 'checkIn.status.waitingOne' }
    : { key: 'checkIn.status.waiting', params: { count: waiting } };
}

/** Each step's heading, a message with parameters (the canvas's four screens). */
export function stepHeading(step: Step, f: Formatters): Said {
  switch (step.step) {
    case 'WHATS_NEW':
      return step.questions === 0
        ? { key: 'checkIn.whatsNew.headingQuiet' }
        : step.questions === 1
          ? { key: 'checkIn.whatsNew.headingOne' }
          : { key: 'checkIn.whatsNew.heading', params: { count: step.questions } };
    case 'CATEGORIZE':
      return step.questions === 0
        ? { key: 'checkIn.categorize.headingPlaced' }
        : step.questions === 1
          ? { key: 'checkIn.categorize.headingOne' }
          : { key: 'checkIn.categorize.heading', params: { count: step.questions } };
    case 'BILLS':
      return { key: 'checkIn.bills.heading' };
    case 'NEXT_WEEK':
      return weekResult(step.figures, f);
  }
}

/** "You came in $63 under", "You went $40 over", "Right on target", or a first week's line. */
export function weekResult(figures: Figures | null, f: Formatters): Said {
  const surplus = figures?.surplus ?? null;
  if (surplus === null) {
    return { key: 'checkIn.next.headingFirst' };
  }
  if (isPositive(surplus)) {
    return { key: 'checkIn.next.headingUnder', params: { amount: f.money(surplus) } };
  }
  if (isNegative(surplus)) {
    return { key: 'checkIn.next.headingOver', params: { amount: f.money(magnitude(surplus)) } };
  }
  return { key: 'checkIn.next.headingOnTarget' };
}

// ------------------------------------------------------------------------------------ items

/** "Chase •4412", or the name alone when the account has no digits recorded. */
export function accountSaid(name: string | null, lastFour: string | null): Said | null {
  if (name === null) {
    return null;
  }
  return lastFour
    ? { key: 'checkIn.account', params: { name, lastFour } }
    : { key: 'checkIn.accountNoDigits', params: { name } };
}

/** Step 1's income line, information only: landed with its comparison, or not seen yet. */
export function incomeSaid(line: IncomeLine, f: Formatters): Said[] {
  if (line.transactionId === null || line.actual === null || line.date === null) {
    return [
      {
        key: 'checkIn.income.notYet',
        params: { name: line.name, date: f.date(line.expectedDate) },
      },
    ];
  }
  const landed: Said = {
    key: 'checkIn.income.landed',
    params: { name: line.name, date: f.date(line.date), amount: f.money(line.actual) },
  };
  // An exact comparison, not a subtraction: the browser does no money arithmetic, so a difference
  // is stated as the expected figure rather than worked out here.
  return [
    landed,
    sameAmount(line.actual, line.expected)
      ? { key: 'checkIn.income.asExpected' }
      : { key: 'checkIn.income.expected', params: { amount: f.money(line.expected) } },
  ];
}

/** Why an item is being asked about, as one sentence. */
export function itemReason(item: Item, f: Formatters): Said | null {
  switch (item.kind) {
    case 'UNUSUAL_CHARGE':
      if (item.usual === null) {
        return null;
      }
      return item.billName
        ? {
            key: 'checkIn.odd.usuallyBill',
            params: { usual: f.money(item.usual), bill: item.billName },
          }
        : { key: 'checkIn.odd.usually', params: { usual: f.money(item.usual) } };
    case 'OVERDUE_BILL':
      return {
        key: 'checkIn.overdue.reason',
        params: {
          bill: item.billName ?? '',
          date: item.dueDate ? f.date(item.dueDate) : '',
          amount: item.expected ? f.money(item.expected) : '',
        },
      };
    case 'BILL_CHARGE':
      return chargeReason(item, f);
    default:
      return null;
  }
}

function chargeReason(item: Item, f: Formatters): Said | null {
  const amount = item.amount ? f.money(magnitude(item.amount)) : '';
  switch (item.reason) {
    case 'RULE_MATCH':
      return { key: 'checkIn.charge.matched', params: { bill: item.billName ?? '' } };
    case 'RULE_OUTSIDE_TOLERANCE':
      return {
        key: 'checkIn.charge.outside',
        params: {
          amount,
          bill: item.billName ?? '',
          expected: item.expected ? f.money(item.expected) : '',
        },
      };
    case 'RECURRING': {
      const said = accountSaid(item.accountName, item.accountLastFour);
      const account = said ? f.t(said.key, said.params) : '';
      return item.suggestedBillId && item.billName
        ? {
            key: 'checkIn.charge.recurring',
            params: { amount, account, bill: item.billName },
          }
        : { key: 'checkIn.charge.recurringPick', params: { amount, account } };
    }
    default:
      return null;
  }
}

/** The prepaid offer, or why there is none. */
export function prepaidSaid(item: Item): Said {
  if (item.multiple === null || item.billName === null) {
    return { key: 'checkIn.odd.noOffer' };
  }
  return (item.skips ?? 0) === 1
    ? { key: 'checkIn.odd.prepaidOne', params: { multiple: item.multiple, bill: item.billName } }
    : {
        key: 'checkIn.odd.prepaid',
        params: { multiple: item.multiple, bill: item.billName, skips: item.skips ?? 0 },
      };
}

/** Step 3's table: "Paid", "Skipped", "Overdue" or "Due Aug 24". */
export function occurrenceStatus(line: OccurrenceLine, f: Formatters): Said {
  switch (line.status) {
    case 'PAID':
      return { key: 'checkIn.bills.paid' };
    case 'SKIPPED':
      return { key: 'checkIn.bills.skipped' };
    case 'DUE':
      return line.overdue
        ? { key: 'checkIn.bills.overdue' }
        : { key: 'checkIn.bills.due', params: { date: f.date(line.dueDate) } };
  }
}

/** "Replaces your rule filing it under Shopping", only when the rule would change. */
export function replacesRule(item: Item, chosenCategoryId: string | null): Said | null {
  if (item.ruleCategoryId === null || item.ruleCategoryName === null) {
    return null;
  }
  if (chosenCategoryId === item.ruleCategoryId) {
    return null;
  }
  return { key: 'checkIn.categorize.replacesRule', params: { category: item.ruleCategoryName } };
}

/** Who gave an answer, for a household where two people answer one draft. */
export function answeredBy(item: Item): Said | null {
  const answer = item.answer;
  if (answer === null || answer.byRule) {
    return null;
  }
  return answer.answeredBy?.name
    ? { key: 'checkIn.answeredBy', params: { name: answer.answeredBy.name } }
    : null;
}

// ---------------------------------------------------------------------------------- step 4

/** The line under each choice: what it does, from the server's own preview figures. */
export function choiceEffect(choice: Choice, figures: Figures, f: Formatters): Said[] {
  const surplus = figures.surplus ? f.money(figures.surplus) : '';
  switch (choice.choice) {
    case 'DEBT': {
      const debt = choice.debtName ?? '';
      const moved: Said =
        choice.payoffMonthNow && choice.payoffMonthWith
          ? choice.payoffMonthNow === choice.payoffMonthWith
            ? { key: 'checkIn.choice.debtSameMonth', params: { debt } }
            : {
                key: 'checkIn.choice.debtMoves',
                params: {
                  debt,
                  from: f.month(choice.payoffMonthNow),
                  to: f.month(choice.payoffMonthWith),
                },
              }
          : { key: 'checkIn.choice.debtNoDate', params: { debt } };
      return [moved, { key: 'checkIn.choice.debtNeverMoves', params: { amount: surplus, debt } }];
    }
    case 'GOAL':
      return [
        {
          key: 'checkIn.choice.goalMoves',
          params: {
            goal: choice.goalName ?? '',
            from: choice.savedNow ? f.money(choice.savedNow) : '',
            to: choice.savedWith ? f.money(choice.savedWith) : '',
            target: choice.target ? f.money(choice.target) : '',
          },
        },
        {
          key: 'checkIn.choice.goalNeverMoves',
          params: { amount: surplus, goal: choice.goalName ?? '' },
        },
      ];
    case 'ROLL_OVER':
      return [
        {
          key: 'checkIn.choice.rollOver',
          params: { amount: figures.rolledOver ? f.money(figures.rolledOver) : '' },
        },
      ];
    default:
      return [];
  }
}

export function choiceLabel(choice: Choice): Said {
  switch (choice.choice) {
    case 'DEBT':
      return { key: 'checkIn.choice.debt', params: { debt: choice.debtName ?? '' } };
    case 'GOAL':
      return { key: 'checkIn.choice.goal', params: { goal: choice.goalName ?? '' } };
    default:
      return { key: 'checkIn.choice.rollOverLabel' };
  }
}

/** Which of the server's two figures next week is: rolled over when that is the answer. */
export function nextWeekFigure(
  figures: Figures,
  rolledOver: boolean,
): { amount: Money; perDay: Money } {
  return rolledOver && figures.rolledOver && figures.rolledPerDay
    ? { amount: figures.rolledOver, perDay: figures.rolledPerDay }
    : { amount: figures.base, perDay: figures.basePerDay };
}

/** "Spent $487 so far this week". */
export function spentSaid(spending: Money, soFar: boolean, f: Formatters): Said {
  return {
    key: soFar ? 'checkIn.spentSoFar' : 'checkIn.spent',
    params: { amount: f.money(spending) },
  };
}

/** "After Car payment on Aug 24 and Visa on Aug 28." An explanation only: `left` has them already. */
export function dueNextWeekSaid(lines: readonly DueLine[], f: Formatters): Said | null {
  if (lines.length === 0) {
    return null;
  }
  return {
    key: 'checkIn.next.dueNextWeek',
    params: {
      bills: f.list(
        lines.map((line) =>
          f.t('checkIn.next.dueOn', { bill: line.billName, date: f.date(line.dueDate) }),
        ),
      ),
    },
  };
}

/** Shown only for a run of two or more, and names the months only when both exist and differ. */
export function streakSaid(streak: Streak | null, f: Formatters): Said | null {
  if (streak === null || streak.weeks < 2) {
    return null;
  }
  if (streak.debtFreeFrom && streak.debtFreeTo && streak.debtFreeFrom !== streak.debtFreeTo) {
    return {
      key: 'checkIn.next.streakMoved',
      params: {
        count: streak.weeks,
        from: f.month(streak.debtFreeFrom),
        to: f.month(streak.debtFreeTo),
      },
    };
  }
  return { key: 'checkIn.next.streak', params: { count: streak.weeks } };
}

// ----------------------------------------------------------------------------------- answers

/** The body for a choice, with the answer's version when it replaces one. */
export function withVersion(item: Item, body: AnswerBody): AnswerBody {
  return item.answer ? { ...body, version: item.answer.version } : body;
}

/** The category an item reads as chosen: the answer's, else a rule's pre-answer. */
export function chosenCategory(item: Item): string | null {
  if (item.answer) {
    return item.answer.choice === 'CATEGORY' ? item.answer.categoryId : null;
  }
  return item.preAnswer?.choice === 'CATEGORY' ? item.preAnswer.categoryId : null;
}

// ------------------------------------------------------------------------------------- stale

/**
 * The items a `409 check-in-stale` names, by key, with why. Read defensively: a newer server's
 * unknown reason still marks the item, with the generic sentence.
 */
export function staleItems(error: ApiError): StaleItem[] {
  if (error.code !== 'check-in-stale') {
    return [];
  }
  const listed = error.params['items'];
  if (!Array.isArray(listed)) {
    return [];
  }
  const items: StaleItem[] = [];
  for (const entry of listed.slice(0, 1000)) {
    if (typeof entry !== 'object' || entry === null) {
      continue;
    }
    const record = entry as Record<string, unknown>;
    const reason = typeof record['reason'] === 'string' ? record['reason'] : 'unknown';
    const itemKey = typeof record['itemKey'] === 'string' ? record['itemKey'] : null;
    items.push({ itemKey, reason });
  }
  return items;
}

const STALE_KEYS: Readonly<Record<string, MessageKey>> = {
  'subject-changed': 'checkIn.stale.subject-changed',
  'subject-gone': 'checkIn.stale.subject-gone',
  'target-archived': 'checkIn.stale.target-archived',
  'already-recorded': 'checkIn.stale.already-recorded',
  'bill-inactive': 'checkIn.stale.bill-inactive',
  'transaction-attached-elsewhere': 'checkIn.stale.transaction-attached-elsewhere',
  'goal-archived': 'checkIn.stale.goal-archived',
  'debt-paid-off': 'checkIn.stale.debt-paid-off',
  'limit-reached': 'checkIn.stale.limit-reached',
  'surplus-changed': 'checkIn.stale.surplus-changed',
  'basis-changed': 'checkIn.stale.basis-changed',
};

export function staleSaid(reason: string): Said {
  return { key: STALE_KEYS[reason] ?? 'checkIn.stale.unknown' };
}

// ----------------------------------------------------------------------------------- summary

/**
 * What a completed check-in wrote, each with a link to where it lives: *"Filed 3 charges. Marked
 * Internet paid and remembered the mapping. Recorded $63 to Emergency fund. Next week: $480."*
 */
export function summary(week: Week, f: Formatters): SummaryLine[] {
  const lines: SummaryLine[] = [];
  const filed = week.outcome.filter(
    (outcome) => outcome.kind === 'CATEGORIZE' && outcome.choice === 'CATEGORY',
  );
  if (filed.length > 0) {
    const byRule = filed.filter((outcome) => outcome.byRule).length;
    lines.push({
      said:
        filed.length === 1
          ? { key: 'checkIn.summary.filedOne' }
          : { key: 'checkIn.summary.filed', params: { count: filed.length } },
      link: ['/transactions'],
    });
    if (byRule > 0) {
      lines.push({
        said: { key: 'checkIn.summary.byRule', params: { count: byRule } },
        link: null,
      });
    }
    const remembered = filed.filter((outcome) => outcome.remember).length;
    if (remembered > 0) {
      lines.push({
        said:
          remembered === 1
            ? { key: 'checkIn.summary.rememberedOne' }
            : { key: 'checkIn.summary.remembered', params: { count: remembered } },
        link: ['/categories'],
      });
    }
  }
  for (const outcome of week.outcome) {
    const line = outcomeLine(outcome, f);
    if (line !== null) {
      lines.push(line);
    }
  }
  if (week.snapshot) {
    lines.push({
      said: {
        key: 'checkIn.summary.nextWeek',
        params: { amount: f.money(week.snapshot.nextWeekTarget) },
      },
      link: null,
    });
  }
  return lines;
}

function outcomeLine(outcome: Outcome, f: Formatters): SummaryLine | null {
  const bill = outcome.billName ?? '';
  switch (outcome.choice) {
    case 'MARK_PAID':
      return { said: { key: 'checkIn.summary.markedPaid', params: { bill } }, link: ['/bills'] };
    case 'SKIP':
      return { said: { key: 'checkIn.summary.skipped', params: { bill } }, link: ['/bills'] };
    case 'PREPAID':
      return { said: { key: 'checkIn.summary.prepaid', params: { bill } }, link: ['/bills'] };
    case 'ATTACH':
      return {
        said: {
          key: outcome.remember ? 'checkIn.summary.attachedRemembered' : 'checkIn.summary.attached',
          params: { bill },
        },
        link: ['/bills'],
      };
    case 'GOAL':
      return {
        said: {
          key: 'checkIn.summary.goal',
          params: {
            amount: outcome.amount ? f.money(outcome.amount) : '',
            goal: outcome.goalName ?? '',
          },
        },
        link: outcome.goalId ? ['/goals', outcome.goalId] : ['/goals'],
      };
    case 'DEBT':
      return {
        said: {
          key: 'checkIn.summary.debt',
          params: {
            amount: outcome.amount ? f.money(outcome.amount) : '',
            debt: outcome.debtName ?? '',
          },
        },
        link: outcome.debtId ? ['/debt-plan', outcome.debtId] : ['/debt-plan'],
      };
    case 'ROLL_OVER':
      return {
        said: {
          key: 'checkIn.summary.rolledOver',
          params: { amount: outcome.amount ? f.money(outcome.amount) : '' },
        },
        link: null,
      };
    default:
      return null;
  }
}
