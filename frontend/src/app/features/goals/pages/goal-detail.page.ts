import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { map } from 'rxjs/operators';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { DateOnlyPipe, MoneyPipe, PeriodPipe } from '../../../core/i18n/format.pipes';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { compareAmounts } from '../../../core/money';
import { ConfirmDialogComponent } from '../../../shared/components/confirm-dialog.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { GoalProgressComponent } from '../../../shared/components/goal-progress.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import { Account } from '../../accounts/data/account.models';
import { AccountsService } from '../../accounts/data/accounts.service';
import { ContributionDialogComponent } from '../components/contribution-dialog.component';
import { GoalDialogComponent } from '../components/goal-dialog.component';
import { PlanDialogComponent, PlanFormValue } from '../components/plan-dialog.component';
import {
  Contribution,
  Goal,
  PlanStep,
  isPreviewAnswer,
  requiredReasonKey,
} from '../data/goal.models';
import {
  ContributionFormValue,
  Direction,
  GoalFormValue,
  Said,
  buildContribution,
  buildEditPreview,
  buildGoalPatch,
  patchIsEmpty,
  projectionSaid,
  type PlanShape,
} from '../data/goal-patch';
import { GoalsService } from '../data/goals.service';

/** Which confirmation is open, if any. */
type Confirming =
  | { readonly kind: 'archive' }
  | { readonly kind: 'delete' }
  | { readonly kind: 'remove'; readonly contribution: Contribution };

/**
 * `/goals/{id}` — one goal: how far along it is, the plan, and the history.
 *
 * - **The route takes the id, never a slug.** A slug leaks the name into browser history and server
 *   logs, and a goal's name is the most disclosing field this feature has.
 * - **The linked account's balance is shown labelled as the account's**, beside the goal's own
 *   figure, and nothing reconciles them: one account often holds several goals.
 * - **The starting amount is the history's first line**, labelled as what was already saved, and is
 *   not a contribution row.
 * - **A withdrawal says so in words**, not only with a minus sign.
 * - **Every preview is the server's**; a late answer is dropped and a failed one never blocks a save.
 * - **A `VIEWER` sees every figure and region and no control.**
 */
@Component({
  selector: 'app-goal-detail-page',
  imports: [
    RouterLink,
    TranslatePipe,
    MoneyPipe,
    DateOnlyPipe,
    PeriodPipe,
    ErrorAlertComponent,
    NoHouseholdComponent,
    ConfirmDialogComponent,
    GoalProgressComponent,
    GoalDialogComponent,
    PlanDialogComponent,
    ContributionDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './goal-detail.page.html',
  styleUrl: './goal-detail.page.css',
})
export class GoalDetailPage {
  private readonly goalsService = inject(GoalsService);
  private readonly accountsService = inject(AccountsService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);
  private readonly router = inject(Router);

  private readonly id = toSignal(
    inject(ActivatedRoute).paramMap.pipe(map((params) => params.get('id') ?? '')),
    { initialValue: '' },
  );

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);

  protected readonly goal = signal<Goal | null>(null);
  protected readonly steps = signal<readonly PlanStep[]>([]);
  protected readonly contributions = signal<readonly Contribution[]>([]);
  private readonly contributionTotal = signal(0);
  protected readonly accounts = signal<readonly Account[]>([]);

  protected readonly contributing = signal<Direction | null>(null);
  protected readonly editing = signal(false);
  protected readonly planning = signal(false);
  protected readonly confirming = signal<Confirming | null>(null);
  protected readonly dialogBusy = signal(false);
  protected readonly dialogError = signal<ApiError | null>(null);
  protected readonly editReset = signal(0);
  protected readonly previewSaid = signal<readonly Said[]>([]);
  protected readonly previewUnavailable = signal(false);
  protected readonly notice = signal<Said | null>(null);

  /** Which preview answer is current: typing asks a stream of questions, answered in any order. */
  private previewRequest = 0;

  protected readonly canWrite = computed(() => {
    const role = this.auth.membership()?.role;
    return role === 'OWNER' || role === 'MEMBER';
  });

  protected readonly baseCurrency = computed(() => this.auth.membership()?.baseCurrency ?? '');

  /** Today as `YYYY-MM-DD`, in the viewer's own calendar — a date is a calendar question. */
  protected readonly today = computed(() => {
    const now = new Date();
    return `${now.getFullYear()}-${`${now.getMonth() + 1}`.padStart(2, '0')}-${`${now.getDate()}`.padStart(2, '0')}`;
  });

  protected readonly account = computed(() => {
    const goal = this.goal();
    return goal?.accountId
      ? (this.accounts().find((account) => account.id === goal.accountId) ?? null)
      : null;
  });

  /** "$2,610.00 to go", or how far over a reached goal is — the server's `remaining`, stated. */
  protected readonly remainingSaid = computed<Said | null>(() => {
    const goal = this.goal();
    if (!goal) {
      return null;
    }
    const order = compareAmounts(goal.remaining, '0');
    if (order === 1) {
      return {
        key: 'goals.detail.toGo',
        params: { amount: this.i18n.formatMoney(goal.remaining, goal.currency) },
      };
    }
    if (order === -1) {
      // Over the target: the remaining figure is negative, and its magnitude is how far over. Said
      // by dropping the sign from the server's own string — no arithmetic.
      return {
        key: 'goals.detail.over',
        params: {
          amount: this.i18n.formatMoney(goal.remaining.replace(/^-/, ''), goal.currency),
        },
      };
    }
    return { key: 'goals.detail.exactly' };
  });

  protected readonly projection = computed<readonly Said[]>(() => {
    const goal = this.goal();
    return goal ? projectionSaid(goal, this.formatters, this.planShape()) : [];
  });

  /** The required figure's own sentence when it is absent — beside the plan, never a blank. */
  protected readonly requiredAbsent = computed<Said | null>(() => {
    const goal = this.goal();
    const reason = goal?.projection.requiredReason ?? null;
    if (!goal || reason === null || goal.reached) {
      return null;
    }
    return {
      key: requiredReasonKey(reason),
      params: { target: goal.targetMonth ? this.i18n.formatMonth(goal.targetMonth) : '' },
    };
  });

  /** The plan's steps from this month on: what is in force, and anything scheduled after it. */
  protected readonly futureSteps = computed(() => {
    const goal = this.goal();
    return goal ? this.steps().filter((step) => step.fromPeriod > goal.thisPeriod.period) : [];
  });

  /** One amount throughout, or a later step the walk also uses. See `PlanShape`. */
  private readonly planShape = computed<PlanShape>(() =>
    this.futureSteps().length === 0 ? 'one-amount' : 'may-change',
  );

  protected readonly hasStart = computed(() => {
    const goal = this.goal();
    return goal !== null && compareAmounts(goal.startingAmount, '0') !== 0;
  });

  protected readonly morePages = computed(
    () => this.contributionTotal() > this.contributions().length,
  );

  protected readonly confirmText = computed<{
    heading: MessageKey;
    message: MessageKey;
    action: MessageKey;
    tone: 'destructive' | 'ordinary';
  } | null>(() => {
    const confirming = this.confirming();
    if (confirming === null) {
      return null;
    }
    switch (confirming.kind) {
      case 'archive':
        return {
          heading: 'goals.archive.heading',
          message: 'goals.archive.body',
          action: 'goals.archive.confirm',
          tone: 'ordinary',
        };
      case 'delete':
        return {
          heading: 'goals.delete.heading',
          message: 'goals.delete.body',
          action: 'goals.delete.confirm',
          tone: 'destructive',
        };
      case 'remove':
        return {
          heading: 'goals.removeContribution.heading',
          message: 'goals.removeContribution.body',
          action: 'goals.removeContribution.confirm',
          tone: 'destructive',
        };
    }
  });

  constructor() {
    effect(() => {
      const id = this.id();
      if (id !== '') {
        void this.load(id);
      }
    });
  }

  protected async load(id = this.id()): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      await this.read(id);
      this.noHousehold.set(false);
    } catch (thrown) {
      const error = asApiError(thrown);
      if (error.status === 403) {
        this.noHousehold.set(true);
      } else {
        this.loadError.set(error);
      }
    } finally {
      this.loading.set(false);
    }
  }

  private async read(id: string): Promise<void> {
    const [goal, steps, contributions, accounts] = await Promise.all([
      firstValueFrom(this.goalsService.get(id)),
      firstValueFrom(this.goalsService.plan(id)),
      firstValueFrom(this.goalsService.contributions(id)),
      firstValueFrom(this.accountsService.list()),
    ]);
    this.goal.set(goal);
    this.steps.set(steps.content);
    this.contributions.set(contributions.content);
    this.contributionTotal.set(contributions.totalElements);
    this.accounts.set(accounts.content);
  }

  protected async showMore(): Promise<void> {
    const goal = this.goal();
    if (!goal) {
      return;
    }
    const next = Math.floor(this.contributions().length / 50);
    try {
      const page = await firstValueFrom(this.goalsService.contributions(goal.id, next));
      this.contributions.update((shown) => [...shown, ...page.content]);
      this.contributionTotal.set(page.totalElements);
    } catch (thrown) {
      this.loadError.set(asApiError(thrown));
    }
  }

  // ------------------------------------------------------------------------------ dialogs

  protected openContribute(direction: Direction): void {
    this.resetDialogs();
    this.contributing.set(direction);
  }

  protected openEdit(): void {
    this.resetDialogs();
    this.editReset.update((key) => key + 1);
    this.editing.set(true);
  }

  protected openPlan(): void {
    this.resetDialogs();
    this.planning.set(true);
  }

  protected openConfirm(confirming: Confirming): void {
    this.resetDialogs();
    this.confirming.set(confirming);
  }

  protected closeDialogs(): void {
    this.resetDialogs();
  }

  protected async submitContribution(form: ContributionFormValue): Promise<void> {
    const goal = this.goal();
    const direction = this.contributing();
    if (!goal || direction === null) {
      return;
    }
    await this.run(
      () =>
        firstValueFrom(this.goalsService.contribute(goal.id, buildContribution(form, direction))),
      { key: direction === 'in' ? 'goals.contribute.recordedIn' : 'goals.contribute.recordedOut' },
    );
  }

  protected async submitEdit(form: GoalFormValue): Promise<void> {
    const goal = this.goal();
    if (!goal) {
      return;
    }
    const patch = buildGoalPatch(goal, form);
    if (patchIsEmpty(patch)) {
      this.closeDialogs();
      return;
    }
    await this.run(() => firstValueFrom(this.goalsService.update(goal.id, patch)), {
      key: 'goals.updated',
    });
  }

  protected async submitPlan(form: PlanFormValue): Promise<void> {
    const goal = this.goal();
    if (!goal) {
      return;
    }
    await this.run(
      () =>
        firstValueFrom(
          this.goalsService.setPlan(goal.id, form.fromPeriod, {
            monthlyAmount: form.monthlyAmount,
            version: goal.version,
          }),
        ),
      { key: 'goals.plan.saved', params: { month: this.i18n.formatMonth(form.fromPeriod) } },
    );
  }

  protected async confirm(): Promise<void> {
    const goal = this.goal();
    const confirming = this.confirming();
    if (!goal || confirming === null) {
      return;
    }
    switch (confirming.kind) {
      case 'archive':
        await this.run(() => firstValueFrom(this.goalsService.archive(goal.id)), {
          key: 'goals.archive.done',
        });
        return;
      case 'delete':
        this.dialogBusy.set(true);
        try {
          await firstValueFrom(this.goalsService.remove(goal.id));
          await this.router.navigateByUrl('/goals');
        } catch (thrown) {
          this.dialogError.set(asApiError(thrown));
        } finally {
          this.dialogBusy.set(false);
        }
        return;
      case 'remove':
        await this.run(
          () =>
            firstValueFrom(
              this.goalsService.removeContribution(goal.id, confirming.contribution.id),
            ),
          { key: 'goals.removeContribution.done' },
        );
        return;
    }
  }

  protected async unarchive(): Promise<void> {
    const goal = this.goal();
    if (!goal) {
      return;
    }
    await this.run(() => firstValueFrom(this.goalsService.unarchive(goal.id)), {
      key: 'goals.unarchived',
    });
  }

  // ------------------------------------------------------------------------------ previews

  /** The edit dialog's preview: the goal with the typed target, month and start substituted. */
  protected async previewEdit(form: GoalFormValue): Promise<void> {
    const goal = this.goal();
    // An edit keeps the plan's steps, so the sentence is the stored plan's shape.
    await this.ask(
      goal === null ? null : buildEditPreview(goal, form),
      form.targetMonth || null,
      this.planShape(),
    );
  }

  /** The plan dialog's preview: the goal with this plan from this month. */
  protected async previewPlan(amount: string): Promise<void> {
    // From this month, the new amount replaces every later step: one amount throughout.
    await this.ask(
      amount === '' ? null : { monthlyAmount: amount },
      this.goal()?.targetMonth,
      'one-amount',
    );
  }

  /**
   * Asks the server, and drops an answer that arrives after a newer question. **A convenience,
   * never a gate**: nothing here works the projection out as a fallback.
   */
  private async ask(
    body: Parameters<GoalsService['preview']>[1] | null,
    targetMonth: string | null | undefined,
    plan: PlanShape,
  ): Promise<void> {
    const goal = this.goal();
    const request = ++this.previewRequest;
    if (!goal || body === null) {
      this.previewSaid.set([]);
      this.previewUnavailable.set(false);
      return;
    }
    try {
      const answer: unknown = await firstValueFrom(this.goalsService.preview(goal.id, body));
      if (request !== this.previewRequest) {
        return;
      }
      if (isPreviewAnswer(answer)) {
        this.previewSaid.set(
          projectionSaid(
            {
              currency: answer.currency,
              targetMonth: targetMonth ?? null,
              monthlyContribution: answer.monthlyContribution,
              reached: answer.reached,
              overdue: answer.overdue,
              projection: answer.projection,
            },
            this.formatters,
            plan,
          ),
        );
        this.previewUnavailable.set(false);
      } else {
        this.previewSaid.set([]);
        this.previewUnavailable.set(true);
      }
    } catch {
      if (request !== this.previewRequest) {
        return;
      }
      this.previewSaid.set([]);
      this.previewUnavailable.set(true);
    }
  }

  // ----------------------------------------------------------------------------- internals

  /**
   * One write, with its failure kept beside the dialog that caused it. The page is re-read only on
   * success, so a refused write never empties the screen.
   */
  private async run(request: () => Promise<unknown>, said: Said): Promise<void> {
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      await request();
      this.resetDialogs();
      this.notice.set(said);
      await this.read(this.id());
    } catch (thrown) {
      this.dialogError.set(asApiError(thrown));
    } finally {
      this.dialogBusy.set(false);
    }
  }

  private resetDialogs(): void {
    this.contributing.set(null);
    this.editing.set(false);
    this.planning.set(false);
    this.confirming.set(null);
    this.dialogBusy.set(false);
    this.dialogError.set(null);
    this.previewRequest += 1;
    this.previewSaid.set([]);
    this.previewUnavailable.set(false);
  }

  private readonly formatters = {
    money: (amount: string, currency: string) => this.i18n.formatMoney(amount, currency),
    month: (period: string) => this.i18n.formatMonth(period),
  };

  protected isWithdrawal(contribution: Contribution): boolean {
    return compareAmounts(contribution.amount, '0') === -1;
  }
}
