import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { GoalProgressComponent } from '../../../shared/components/goal-progress.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import { Account } from '../../accounts/data/account.models';
import { AccountsService } from '../../accounts/data/accounts.service';
import { GoalDialogComponent } from '../components/goal-dialog.component';
import { Goal, isPreviewAnswer } from '../data/goal.models';
import {
  GoalFormValue,
  Said,
  buildCreateGoal,
  buildDraftPreview,
  projectionSaid,
} from '../data/goal-patch';
import { GoalsService } from '../data/goals.service';

/** One goal on the list, with its projection already in words. */
interface GoalRow {
  readonly goal: Goal;
  readonly said: readonly Said[];
}

/** Goals in one currency. There is only one group unless the household saves in several. */
interface CurrencyGroup {
  readonly currency: string;
  readonly rows: readonly GoalRow[];
}

/**
 * `/goals` — what the household is saving for, and when each gets there.
 *
 * - **Grouped by currency only when there is more than one**, the accounts rule, and **never
 *   totalled across goals**: there is no "total saved" figure, because a sum of savings goals is a
 *   number with nothing to act on.
 * - **Each row is the home card's goal row** plus its projection in words. Every reason code has a
 *   sentence and none is a blank.
 * - **The empty state says what a goal is for**, and never draws a $0 of $0 bar.
 * - **Archived goals sit behind a toggle**, muted, in their own section — and the page says they
 *   are no longer part of this month's plan.
 * - **A `VIEWER` sees every figure and no control.**
 */
@Component({
  selector: 'app-goals-page',
  imports: [
    RouterLink,
    TranslatePipe,
    ErrorAlertComponent,
    NoHouseholdComponent,
    GoalProgressComponent,
    GoalDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './goals.page.html',
  styleUrl: './goals.page.css',
})
export class GoalsPage {
  private readonly goalsService = inject(GoalsService);
  private readonly accountsService = inject(AccountsService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);

  private readonly goals = signal<readonly Goal[]>([]);
  protected readonly accounts = signal<readonly Account[]>([]);
  protected readonly showArchived = signal(false);

  protected readonly adding = signal(false);
  protected readonly dialogBusy = signal(false);
  protected readonly dialogError = signal<ApiError | null>(null);
  protected readonly previewSaid = signal<readonly Said[]>([]);
  protected readonly previewUnavailable = signal(false);
  protected readonly notice = signal<Said | null>(null);

  /** Which preview answer is current. A slow answer to an older question is dropped. */
  private previewRequest = 0;

  protected readonly canWrite = computed(() => {
    const role = this.auth.membership()?.role;
    return role === 'OWNER' || role === 'MEMBER';
  });

  protected readonly baseCurrency = computed(() => this.auth.membership()?.baseCurrency ?? '');

  private readonly active = computed(() => this.goals().filter((goal) => !goal.archived));

  protected readonly archived = computed<readonly GoalRow[]>(() =>
    this.goals()
      .filter((goal) => goal.archived)
      .map((goal) => this.row(goal)),
  );

  protected readonly empty = computed(() => this.active().length === 0);

  /**
   * The active goals, grouped by currency in the server's own order within each group. One group
   * gets no heading: a currency label over a list that is all one currency is noise.
   */
  protected readonly groups = computed<readonly CurrencyGroup[]>(() => {
    const byCurrency = new Map<string, GoalRow[]>();
    for (const goal of this.active()) {
      const rows = byCurrency.get(goal.currency) ?? [];
      rows.push(this.row(goal));
      byCurrency.set(goal.currency, rows);
    }
    return [...byCurrency.entries()].map(([currency, rows]) => ({ currency, rows }));
  });

  protected readonly grouped = computed(() => this.groups().length > 1);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    try {
      await this.read();
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

  private async read(): Promise<void> {
    const [goals, accounts] = await Promise.all([
      firstValueFrom(this.goalsService.list(true, 0, 200)),
      firstValueFrom(this.accountsService.list()),
    ]);
    this.goals.set(goals.content);
    this.accounts.set(accounts.content);
  }

  protected toggleArchived(): void {
    this.showArchived.update((shown) => !shown);
  }

  protected openAdd(): void {
    this.resetDialog();
    this.adding.set(true);
  }

  protected closeDialog(): void {
    this.resetDialog();
  }

  /**
   * Asks the server what this goal would report. **A convenience, never a gate**: a refused or
   * failed preview says so and saving still works, and nothing here works the figure out instead.
   */
  protected async askPreview(form: GoalFormValue): Promise<void> {
    const request = ++this.previewRequest;
    const body = buildDraftPreview(form);
    if (body === null) {
      this.previewSaid.set([]);
      this.previewUnavailable.set(false);
      return;
    }
    try {
      const answer: unknown = await firstValueFrom(this.goalsService.previewDraft(body));
      if (request !== this.previewRequest) {
        return;
      }
      if (isPreviewAnswer(answer)) {
        this.previewSaid.set(
          projectionSaid(
            {
              currency: answer.currency,
              targetMonth: body.targetMonth ?? null,
              monthlyContribution: answer.monthlyContribution,
              reached: answer.reached,
              overdue: answer.overdue,
              projection: answer.projection,
            },
            this.formatters,
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

  protected async submitGoal(form: GoalFormValue): Promise<void> {
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      const created = await firstValueFrom(this.goalsService.create(buildCreateGoal(form)));
      this.resetDialog();
      this.notice.set({ key: 'goals.created', params: { name: created.name } });
      await this.read();
    } catch (thrown) {
      this.dialogError.set(asApiError(thrown));
    } finally {
      this.dialogBusy.set(false);
    }
  }

  private resetDialog(): void {
    this.adding.set(false);
    this.dialogBusy.set(false);
    this.dialogError.set(null);
    // A preview belongs to the dialog that asked for it.
    this.previewRequest += 1;
    this.previewSaid.set([]);
    this.previewUnavailable.set(false);
  }

  private readonly formatters = {
    money: (amount: string, currency: string) => this.i18n.formatMoney(amount, currency),
    month: (period: string) => this.i18n.formatMonth(period),
  };

  private row(goal: Goal): GoalRow {
    return { goal, said: projectionSaid(goal, this.formatters) };
  }
}
