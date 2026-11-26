import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { DateOnlyPipe, MoneyPipe } from '../../../core/i18n/format.pipes';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MessageKey, MessageParams } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ConfirmDialogComponent } from '../../../shared/components/confirm-dialog.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import { CategoriesService } from '../../categories/data/categories.service';
import { Category } from '../../categories/data/category.models';
import {
  AnswerBody,
  AnswerChoice,
  CheckInStatus,
  BillChoice,
  Choice,
  IncomeLine,
  Item,
  Money,
  OccurrenceLine,
  StaleItem,
  Step,
  Week,
} from '../data/check-in.models';
import { CheckInService, WEEK_PATTERN } from '../data/check-in.service';
import {
  Formatters,
  Said,
  accountSaid,
  answeredBy,
  choiceEffect,
  choiceLabel,
  chosenCategory,
  dueNextWeekSaid,
  incomeSaid,
  isNegative,
  isPositive,
  itemReason,
  magnitude,
  nextStepKey,
  nextWeekFigure,
  occurrenceStatus,
  prepaidSaid,
  progress,
  replacesRule,
  shownSteps,
  spentSaid,
  staleItems,
  staleSaid,
  stepHeading,
  stepNameKey,
  stepStatus,
  streakSaid,
  summary,
  weekNumber,
  weekResult,
  withVersion,
} from '../data/check-in-text';

const STATUS_CHIP: Readonly<Record<CheckInStatus, MessageKey>> = {
  NOT_STARTED: 'checkIn.statusChip.NOT_STARTED',
  DRAFT: 'checkIn.statusChip.DRAFT',
  COMPLETED: 'checkIn.statusChip.COMPLETED',
};

const CHOICE_NAME: Readonly<Record<AnswerChoice, MessageKey>> = {
  MARK_PAID: 'checkIn.choiceName.MARK_PAID',
  SKIP: 'checkIn.choiceName.SKIP',
  PREPAID: 'checkIn.choiceName.PREPAID',
  CATEGORY: 'checkIn.choiceName.CATEGORY',
  ATTACH: 'checkIn.choiceName.ATTACH',
  NOT_A_BILL: 'checkIn.choiceName.NOT_A_BILL',
  LEAVE: 'checkIn.choiceName.LEAVE',
  DEBT: 'checkIn.choiceName.DEBT',
  GOAL: 'checkIn.choiceName.GOAL',
  ROLL_OVER: 'checkIn.choiceName.ROLL_OVER',
};

/** A category as the picker offers it: active only, labelled with its parent. */
interface CategoryOption {
  readonly id: string;
  readonly label: Said;
}

/** What "Different bill" has chosen so far, per item, before it is sent. */
interface BillPick {
  readonly billId: string;
  readonly dueDate: string;
  readonly remember: boolean;
}

/**
 * `/check-in` and `/check-in/2026-W34` — the weekly check-in (`weekly-check-in.md`, UI).
 *
 * - **One page on a wide screen, one step per screen on a phone.** Both are this template: the
 *   sections are always in the DOM in the same order with the same fields, and the stylesheet shows
 *   only the current one below 40rem, with a progress bar and a footer naming the next step. The
 *   numbers cannot differ between viewports because there is only one copy of them.
 * - **Every answer saves as it is given**, one request each, and the server answers with the whole
 *   week. The figure at the bottom is replaced from that answer, never computed here, and sits in an
 *   `aria-live` region so a change is announced.
 * - **Nothing financial is kept in the browser.** The only state here is which screen is showing
 *   and what a half-made "Different bill" choice holds.
 * - **A `VIEWER` sees every item, answer and figure and no control**, and the server refuses the
 *   writes regardless.
 * - **A stale refusal re-reads and marks each item with its reason.** Nothing still valid is
 *   cleared.
 */
@Component({
  selector: 'app-check-in-page',
  imports: [
    RouterLink,
    TranslatePipe,
    MoneyPipe,
    DateOnlyPipe,
    ErrorAlertComponent,
    NoHouseholdComponent,
    ConfirmDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './check-in.page.html',
  styleUrl: './check-in.page.css',
})
export class CheckInPage {
  private readonly service = inject(CheckInService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);

  protected readonly week = signal<Week | null>(null);
  private readonly categories = signal<readonly Category[]>([]);

  /** The item being saved, or `finish` / `start` / `discard`. One write at a time. */
  protected readonly busy = signal<string | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly notice = signal<Said | null>(null);
  protected readonly stale = signal<readonly StaleItem[]>([]);
  /** Another week's unfinished draft, from `409 check-in-draft-open`. */
  protected readonly draftOpen = signal<string | null>(null);
  protected readonly confirmingDiscard = signal(false);

  /** Which step a phone shows. A desktop shows them all and ignores it. */
  protected readonly screen = signal(0);
  protected readonly picks = signal<Readonly<Record<string, BillPick>>>({});
  /** The debt-plan prompt's tick, per item, before the answer it belongs to is given. */
  private readonly prompts = signal<Readonly<Record<string, boolean>>>({});

  /** The week the route asked for, or `null` for "the current one". */
  private requested: string | null = null;

  protected readonly canWrite = computed(() => {
    const role = this.auth.membership()?.role;
    return role === 'OWNER' || role === 'MEMBER';
  });

  protected readonly steps = computed<readonly Step[]>(() => {
    const week = this.week();
    return week ? shownSteps(week) : [];
  });

  protected readonly status = computed(() => this.week()?.status ?? 'NOT_STARTED');
  protected readonly statusChipKey = computed<MessageKey>(() => STATUS_CHIP[this.status()]);
  protected readonly editable = computed(() => this.canWrite() && this.status() === 'DRAFT');
  protected readonly number = computed(() => weekNumber(this.week()?.week ?? ''));
  protected readonly progress = computed(() => {
    const week = this.week();
    return week ? progress(week) : { answered: 0, shown: 0 };
  });

  protected readonly staleByKey = computed(() => {
    const byKey = new Map<string, string>();
    for (const item of this.stale()) {
      if (item.itemKey !== null && !byKey.has(item.itemKey)) {
        byKey.set(item.itemKey, item.reason);
      }
    }
    return byKey;
  });

  /** Reasons no single item carries, such as `basis-changed`. */
  protected readonly staleGeneral = computed(() => {
    const reasons = new Set<string>();
    for (const item of this.stale()) {
      if (item.itemKey === null) {
        reasons.add(item.reason);
      }
    }
    return [...reasons];
  });

  protected readonly categoryOptions = computed<readonly CategoryOption[]>(() =>
    this.categories()
      .filter((category) => !category.archived)
      .map((category): CategoryOption => ({
        id: category.id,
        label: category.parentName
          ? {
              key: 'checkIn.categorize.categoryPath',
              params: { parent: category.parentName, name: category.name },
            }
          : { key: 'checkIn.accountNoDigits', params: { name: category.name } },
      })),
  );

  protected readonly summaryLines = computed(() => {
    const week = this.week();
    return week && week.status === 'COMPLETED' ? summary(week, this.formatters) : [];
  });

  protected readonly formatters: Formatters = {
    money: (money: Money) => this.i18n.formatMoney(money.amount, money.currency),
    date: (iso: string) => this.i18n.formatDate(iso),
    month: (period: string) => this.i18n.formatMonth(period),
    list: (parts: readonly string[]) => {
      try {
        return new Intl.ListFormat(this.i18n.locale(), { type: 'conjunction' }).format(parts);
      } catch {
        return parts.join(', ');
      }
    },
    t: (key: MessageKey, params?: MessageParams) => this.i18n.t(key, params),
  };

  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe((params) => {
        this.requested = params.get('week');
        this.screen.set(0);
        this.stale.set([]);
        this.notice.set(null);
        this.draftOpen.set(null);
        void this.load();
      });
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      if (this.requested !== null && !WEEK_PATTERN.test(this.requested)) {
        // Never sent: a path segment that is not a week is not worth a request to refuse.
        throw new ApiError(404, 'not-found');
      }
      const [week, categories] = await Promise.all([
        this.read(),
        firstValueFrom(this.categoriesService.list(false)),
      ]);
      this.week.set(week);
      this.categories.set(categories.content);
      this.noHousehold.set(false);
    } catch (thrown) {
      const error = asApiError(thrown);
      if (error.status === 403 && error.code !== 'read-only-role') {
        this.noHousehold.set(true);
      } else {
        this.loadError.set(error);
      }
    } finally {
      this.loading.set(false);
    }
  }

  private read(): Promise<Week> {
    return firstValueFrom(
      this.requested === null ? this.service.current() : this.service.get(this.requested),
    );
  }

  private async reread(): Promise<void> {
    try {
      this.week.set(await this.read());
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
    }
  }

  // ------------------------------------------------------------------------------- writes

  protected async start(): Promise<void> {
    const week = this.week();
    if (!week || !this.canWrite()) {
      return;
    }
    this.begin('start');
    try {
      this.week.set(await firstValueFrom(this.service.start(week.week)));
    } catch (thrown) {
      const error = asApiError(thrown);
      const open = error.params['week'];
      if (
        error.code === 'check-in-draft-open' &&
        typeof open === 'string' &&
        WEEK_PATTERN.test(open)
      ) {
        this.draftOpen.set(open);
      } else {
        this.actionError.set(error);
      }
    } finally {
      this.busy.set(null);
    }
  }

  /** Discards the other week's draft that is blocking this one, then starts this one. */
  protected async discardOpenDraft(): Promise<void> {
    const open = this.draftOpen();
    if (open === null || !this.canWrite()) {
      return;
    }
    this.begin('discard');
    try {
      await firstValueFrom(this.service.discard(open));
      this.draftOpen.set(null);
      this.notice.set({ key: 'checkIn.discarded' });
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
    } finally {
      this.busy.set(null);
    }
  }

  protected askToDiscard(): void {
    this.confirmingDiscard.set(true);
  }

  protected cancelDiscard(): void {
    this.confirmingDiscard.set(false);
  }

  protected async discard(): Promise<void> {
    const week = this.week();
    if (!week || !this.canWrite()) {
      return;
    }
    this.begin('discard');
    try {
      await firstValueFrom(this.service.discard(week.week));
      this.confirmingDiscard.set(false);
      this.notice.set({ key: 'checkIn.discarded' });
      this.stale.set([]);
      this.screen.set(0);
      await this.reread();
    } catch (thrown) {
      this.confirmingDiscard.set(false);
      this.actionError.set(asApiError(thrown));
    } finally {
      this.busy.set(null);
    }
  }

  protected async answer(item: Item, body: AnswerBody): Promise<void> {
    const week = this.week();
    if (!week || !this.editable()) {
      return;
    }
    this.begin(item.itemKey);
    try {
      this.week.set(
        await firstValueFrom(this.service.answer(week.week, item.itemKey, withVersion(item, body))),
      );
      this.forget(item.itemKey);
    } catch (thrown) {
      await this.refused(asApiError(thrown));
    } finally {
      this.busy.set(null);
    }
  }

  protected async clear(item: Item): Promise<void> {
    const week = this.week();
    if (!week || !this.editable() || item.answer === null) {
      return;
    }
    this.begin(item.itemKey);
    try {
      await firstValueFrom(this.service.clearAnswer(week.week, item.itemKey));
      this.forget(item.itemKey);
      await this.reread();
    } catch (thrown) {
      await this.refused(asApiError(thrown));
    } finally {
      this.busy.set(null);
    }
  }

  protected async finish(): Promise<void> {
    const week = this.week();
    if (!week || !this.editable() || week.version === null || week.basis === null) {
      return;
    }
    this.begin('finish');
    try {
      this.week.set(
        await firstValueFrom(this.service.complete(week.week, week.version, week.basis)),
      );
      this.stale.set([]);
      this.screen.set(0);
    } catch (thrown) {
      const error = asApiError(thrown);
      if (error.code === 'check-in-stale') {
        this.stale.set(staleItems(error));
        await this.reread();
        this.showFirstStale();
      } else {
        await this.refused(error);
      }
    } finally {
      this.busy.set(null);
    }
  }

  /**
   * A refusal that a re-read answers: someone else answered first, finished the week, or the draft
   * moved. Anything else is shown as it came.
   */
  private async refused(error: ApiError): Promise<void> {
    if (error.code === 'stale-version') {
      this.notice.set({ key: 'checkIn.answerConflict' });
      await this.reread();
    } else if (error.code === 'check-in-completed') {
      this.actionError.set(error);
      await this.reread();
    } else {
      this.actionError.set(error);
    }
  }

  private begin(what: string): void {
    this.busy.set(what);
    this.actionError.set(null);
    this.notice.set(null);
  }

  /** An answered item is no longer stale, and its half-made choices are spent. */
  private forget(itemKey: string): void {
    this.stale.update((items) => items.filter((item) => item.itemKey !== itemKey));
    this.picks.update((picks) =>
      Object.fromEntries(Object.entries(picks).filter(([key]) => key !== itemKey)),
    );
  }

  private showFirstStale(): void {
    const first = this.stale().find((item) => item.itemKey !== null)?.itemKey ?? null;
    const steps = this.steps();
    const index =
      first === null
        ? steps.length - 1
        : steps.findIndex((step) => step.items.some((item) => item.itemKey === first));
    this.screen.set(Math.max(0, index));
    if (first !== null) {
      queueMicrotask(() => document.getElementById(this.itemId(first))?.scrollIntoView?.());
    }
  }

  // --------------------------------------------------------------------- answering helpers

  protected prepaid(item: Item): void {
    void this.answer(item, { choice: 'PREPAID' });
  }

  protected leave(item: Item): void {
    void this.answer(item, { choice: 'LEAVE' });
  }

  protected skip(item: Item): void {
    void this.answer(item, { choice: 'SKIP' });
  }

  protected notABill(item: Item): void {
    void this.answer(item, { choice: 'NOT_A_BILL' });
  }

  protected markPaid(item: Item): void {
    void this.answer(item, { choice: 'MARK_PAID', recordDebtPayment: this.prompted(item) });
  }

  /** The debt-plan prompt: never assumed, and part of the answer it is ticked with. */
  protected prompted(item: Item): boolean {
    return this.prompts()[item.itemKey] ?? item.answer?.recordDebtPayment ?? false;
  }

  protected togglePrompt(item: Item, checked: boolean): void {
    this.prompts.update((prompts) => ({ ...prompts, [item.itemKey]: checked }));
    const answer = item.answer;
    if (answer?.choice === 'MARK_PAID') {
      void this.answer(item, { choice: 'MARK_PAID', recordDebtPayment: checked });
    } else if (answer?.choice === 'ATTACH' && answer.billId && answer.dueDate) {
      void this.answer(item, {
        choice: 'ATTACH',
        billId: answer.billId,
        dueDate: answer.dueDate,
        remember: answer.remember,
        recordDebtPayment: checked,
      });
    }
  }

  protected chooseCategory(item: Item, categoryId: string): void {
    if (categoryId === '') {
      void this.clear(item);
      return;
    }
    void this.answer(item, {
      choice: 'CATEGORY',
      categoryId,
      remember: item.answer?.remember ?? false,
    });
  }

  protected toggleRemember(item: Item, remember: boolean): void {
    const categoryId = chosenCategory(item);
    if (categoryId !== null) {
      void this.answer(item, { choice: 'CATEGORY', categoryId, remember });
    }
  }

  /** "Map it" or "Attach": the bill and date the server suggested. */
  protected attachSuggested(item: Item): void {
    const billId = item.suggestedBillId ?? item.billId;
    const dueDate = item.suggestedDueDate ?? item.dueDate;
    if (billId === null || dueDate === null) {
      this.openPicker(item);
      return;
    }
    void this.answer(item, {
      choice: 'ATTACH',
      billId,
      dueDate,
      // "Map it" creates the mapping; a rule that already exists needs no second one.
      remember: item.reason === 'RECURRING',
      recordDebtPayment: this.prompted(item),
    });
  }

  protected openPicker(item: Item): void {
    const choices = this.billChoices();
    const first = choices[0];
    this.picks.update((picks) => ({
      ...picks,
      [item.itemKey]: {
        billId: first?.billId ?? '',
        dueDate: first?.dueDates[0] ?? '',
        remember: item.reason === 'RECURRING',
      },
    }));
  }

  protected pickBill(item: Item, billId: string): void {
    const choice = this.billChoices().find((bill) => bill.billId === billId);
    this.picks.update((picks) => ({
      ...picks,
      [item.itemKey]: {
        billId,
        dueDate: choice?.dueDates[0] ?? '',
        remember: picks[item.itemKey]?.remember ?? false,
      },
    }));
  }

  protected pickDate(item: Item, dueDate: string): void {
    this.picks.update((picks) => {
      const pick = picks[item.itemKey];
      return pick ? { ...picks, [item.itemKey]: { ...pick, dueDate } } : picks;
    });
  }

  protected pickRemember(item: Item, remember: boolean): void {
    this.picks.update((picks) => {
      const pick = picks[item.itemKey];
      return pick ? { ...picks, [item.itemKey]: { ...pick, remember } } : picks;
    });
  }

  protected attachPicked(item: Item): void {
    const pick = this.picks()[item.itemKey];
    if (!pick || pick.billId === '' || pick.dueDate === '') {
      return;
    }
    void this.answer(item, {
      choice: 'ATTACH',
      billId: pick.billId,
      dueDate: pick.dueDate,
      remember: pick.remember,
      // The prompt belongs to the bill the server named; a different bill carries none.
      recordDebtPayment: pick.billId === item.billId && this.prompted(item),
    });
  }

  protected allocate(choice: Choice): void {
    const step = this.steps().find((candidate) => candidate.step === 'NEXT_WEEK');
    const item = step?.items.find((candidate) => candidate.kind === 'ALLOCATION');
    const surplus = step?.figures?.surplus;
    if (!item || !surplus) {
      return;
    }
    // The amount is the server's own surplus, sent back so it can tell the person saw the same
    // figure. It is never typed and never worked out here.
    const amount = surplus.amount;
    if (choice.choice === 'DEBT' && choice.debtId) {
      void this.answer(item, { choice: 'DEBT', debtId: choice.debtId, amount });
    } else if (choice.choice === 'GOAL' && choice.goalId) {
      void this.answer(item, { choice: 'GOAL', goalId: choice.goalId, amount });
    } else if (choice.choice === 'ROLL_OVER') {
      void this.answer(item, { choice: 'ROLL_OVER', amount });
    }
  }

  // ------------------------------------------------------------------------- navigation

  protected go(index: number): void {
    const last = this.steps().length - 1;
    this.screen.set(Math.min(Math.max(index, 0), Math.max(last, 0)));
    queueMicrotask(() => document.getElementById('check-in-top')?.scrollIntoView?.());
  }

  // ------------------------------------------------------------------------- template reads

  protected choiceNameKey(choice: AnswerChoice): MessageKey {
    return CHOICE_NAME[choice];
  }

  protected attachedOf(item: Item): Said | null {
    const answer = item.answer;
    if (answer?.choice !== 'ATTACH' || !answer.billName || !answer.dueDate) {
      return null;
    }
    return {
      key: 'checkIn.charge.attachedTo',
      params: { bill: answer.billName, date: this.i18n.formatDate(answer.dueDate) },
    };
  }

  protected itemId(itemKey: string): string {
    return 'item-' + itemKey.replace(/[^a-zA-Z0-9-]/g, '-');
  }

  protected stepId(step: Step): string {
    return 'step-' + step.step.toLowerCase().replace('_', '-');
  }

  protected stepName(step: Step): MessageKey {
    return stepNameKey(step.step);
  }

  protected nextKey(index: number): MessageKey | null {
    const next = this.steps()[index + 1];
    return next ? nextStepKey(next.step) : null;
  }

  protected heading(step: Step): Said {
    return stepHeading(step, this.formatters);
  }

  protected statusOf(step: Step): Said {
    return stepStatus(step);
  }

  protected reasonOf(item: Item): Said | null {
    return itemReason(item, this.formatters);
  }

  protected prepaidOf(item: Item): Said {
    return prepaidSaid(item);
  }

  protected incomeOf(line: IncomeLine): Said[] {
    return incomeSaid(line, this.formatters);
  }

  protected accountOf(item: Item): Said | null {
    return accountSaid(item.accountName, item.accountLastFour);
  }

  protected answeredByOf(item: Item): Said | null {
    return answeredBy(item);
  }

  protected occurrenceStatusOf(line: OccurrenceLine): Said {
    return occurrenceStatus(line, this.formatters);
  }

  protected chosenCategoryOf(item: Item): string {
    return chosenCategory(item) ?? '';
  }

  protected chosenCategoryName(item: Item): string | null {
    if (item.answer) {
      return item.answer.choice === 'CATEGORY' ? item.answer.categoryName : null;
    }
    return item.preAnswer?.categoryName ?? null;
  }

  protected replacesRuleOf(item: Item): Said | null {
    return item.answer?.remember ? replacesRule(item, chosenCategory(item)) : null;
  }

  protected byRule(item: Item): boolean {
    return item.answer ? item.answer.byRule : item.preAnswer !== null;
  }

  protected chose(item: Item, choice: string): boolean {
    return (item.answer?.choice ?? item.preAnswer?.choice ?? null) === choice;
  }

  protected pickOf(item: Item): BillPick | null {
    return this.picks()[item.itemKey] ?? null;
  }

  protected datesFor(billId: string): readonly string[] {
    return this.billChoices().find((bill) => bill.billId === billId)?.dueDates ?? [];
  }

  protected billChoices(): readonly BillChoice[] {
    return this.steps().find((step) => step.step === 'BILLS')?.billChoices ?? [];
  }

  protected staleOf(item: Item): Said | null {
    const reason = this.staleByKey().get(item.itemKey);
    return reason === undefined ? null : staleSaid(reason);
  }

  protected staleGeneralSaid(reason: string): Said {
    return staleSaid(reason);
  }

  protected allocationItem(step: Step): Item | null {
    return step.items.find((item) => item.kind === 'ALLOCATION') ?? null;
  }

  protected allocated(step: Step, choice: Choice): boolean {
    const answer = this.allocationItem(step)?.answer;
    if (!answer || answer.choice !== choice.choice) {
      return false;
    }
    return choice.choice === 'DEBT'
      ? answer.debtId === choice.debtId
      : choice.choice === 'GOAL'
        ? answer.goalId === choice.goalId
        : true;
  }

  protected choiceValue(choice: Choice): string {
    return `${choice.choice}:${choice.debtId ?? choice.goalId ?? ''}`;
  }

  protected choiceLabelOf(choice: Choice): Said {
    return choiceLabel(choice);
  }

  protected choiceEffectOf(step: Step, choice: Choice): Said[] {
    return step.figures ? choiceEffect(choice, step.figures, this.formatters) : [];
  }

  protected offersAllocation(step: Step): boolean {
    const surplus = step.figures?.surplus ?? null;
    return surplus !== null && isPositive(surplus) && this.allocationItem(step) !== null;
  }

  protected figureOf(step: Step): { amount: Money; perDay: Money } | null {
    if (!step.figures) {
      return null;
    }
    const answer = this.allocationItem(step)?.answer;
    return nextWeekFigure(step.figures, answer?.choice === 'ROLL_OVER');
  }

  protected negative(money: Money): boolean {
    return isNegative(money);
  }

  protected resultOf(step: Step): Said {
    return weekResult(step.figures, this.formatters);
  }

  protected spentOf(step: Step): Said | null {
    const spending = step.weekSpending ?? step.figures?.weekSpending ?? null;
    const soFar = this.steps().find((candidate) => candidate.figures)?.figures?.soFar ?? false;
    return spending ? spentSaid(spending, soFar, this.formatters) : null;
  }

  protected targetLine(step: Step): Said | null {
    const figures = step.figures;
    if (!figures) {
      return null;
    }
    return figures.weekTarget
      ? {
          key: 'checkIn.next.target',
          params: {
            target: this.formatters.money(figures.weekTarget),
            spent: this.formatters.money(figures.weekSpending),
          },
        }
      : { key: 'checkIn.next.noTarget' };
  }

  protected dueNextWeekOf(step: Step): Said | null {
    return dueNextWeekSaid(step.dueNextWeek ?? [], this.formatters);
  }

  protected streakOf(step: Step): Said | null {
    return streakSaid(step.streak, this.formatters);
  }

  protected chargeAmount(item: Item): Money | null {
    return item.amount ? magnitude(item.amount) : null;
  }

  protected placedOf(step: Step): Said {
    const total = step.items.length;
    const placed = step.items.filter(
      (item) => item.answer !== null || item.preAnswer !== null,
    ).length;
    return { key: 'checkIn.categorize.placed', params: { placed, total } };
  }

  protected rangeOf(week: Week): string {
    return this.i18n.t('checkIn.range', {
      from: this.i18n.formatDate(week.weekStart),
      to: this.i18n.formatDate(week.weekEnd),
    });
  }
}
