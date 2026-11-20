import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MessageKey } from '../../../core/i18n/messages';
import { DateOnlyPipe, MoneyPipe } from '../../../core/i18n/format.pipes';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ConfirmDialogComponent } from '../../../shared/components/confirm-dialog.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import { Category } from '../../categories/data/category.models';
import { CategoriesService } from '../../categories/data/categories.service';
import { EnvelopeDialogComponent } from '../components/envelope-dialog.component';
import { QuickAddComponent } from '../components/quick-add.component';
import {
  EntryFormValue,
  EnvelopeFormValue,
  buildEntryCreate,
  buildEntryPatch,
  buildEnvelopeCreate,
  buildEnvelopePatch,
  patchIsEmpty,
} from '../data/envelope-patch';
import { currentPeriod, periodAsDate, shiftPeriod } from '../data/envelope-period';
import { Envelope, EnvelopeEntry, entrySourceKey } from '../data/envelope.models';
import { EnvelopesService } from '../data/envelopes.service';

interface EnvelopeDialogTarget {
  envelope: Envelope | null;
}

interface EntryDialogTarget {
  envelope: Envelope;
  entry: EnvelopeEntry | null;
}

type PendingAction =
  | { kind: 'archive' | 'delete'; envelope: Envelope }
  | { kind: 'deleteEntry'; envelope: Envelope; entry: EnvelopeEntry };

/**
 * What a household decided to spend, and what is left of it.
 *
 * Smart: it fetches, coordinates and holds the state. Four behaviours here are the feature's rules
 * rather than UI taste:
 *
 * - **`remaining` is the headline on every card**, and it is rendered negative when an envelope is
 *   overspent. Nothing clamps it — "you are £11.20 over" is the information somebody needs.
 * - **Logging spend is one click from the list**, and the sheet it opens asks for an amount and
 *   nothing else. This is the action somebody takes eighty times a month.
 * - **The log says where every row came from.** A figure typed and a figure taken from a
 *   transaction are different kinds of claim and are never presented identically.
 * - **The totals are safe to add** where account balances are not (ADR-0022): every envelope in a
 *   household shares its base currency, so nothing is being converted. The server computes them
 *   anyway; this page does no arithmetic on money at all.
 */
@Component({
  selector: 'app-envelopes-page',
  imports: [
    TranslatePipe,
    MoneyPipe,
    DateOnlyPipe,
    ErrorAlertComponent,
    NoHouseholdComponent,
    EnvelopeDialogComponent,
    QuickAddComponent,
    ConfirmDialogComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './envelopes.page.html',
  styleUrl: './envelopes.page.css',
})
export class EnvelopesPage {
  private readonly service = inject(EnvelopesService);
  private readonly categoriesService = inject(CategoriesService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);

  protected readonly period = signal(currentPeriod());
  private readonly envelopes = signal<readonly Envelope[]>([]);
  protected readonly categories = signal<readonly Category[]>([]);
  protected readonly totals = signal<{
    allocated: string;
    spent: string;
    remaining: string;
    currency: string;
  } | null>(null);

  protected readonly showArchived = signal(false);

  /** The envelope whose log is open, and the log itself. One at a time: this is a list, not a grid. */
  protected readonly openLogFor = signal<string | null>(null);
  protected readonly logEntries = signal<readonly EnvelopeEntry[]>([]);
  protected readonly logLoading = signal(false);

  protected readonly canWrite = computed(() => {
    const role = this.auth.membership()?.role;
    return role === 'OWNER' || role === 'MEMBER';
  });

  protected readonly active = computed(() => this.envelopes().filter((e) => !e.archived));
  protected readonly archived = computed(() => this.envelopes().filter((e) => e.archived));
  protected readonly currency = computed(
    () => this.totals()?.currency ?? this.auth.membership()?.baseCurrency ?? '',
  );
  /**
   * The month, named for the viewer's locale — "August 2026", never "2026-08".
   *
   * Formatted here rather than through a pipe because no existing pipe takes a month style, and
   * the period is the one date on this screen that is a month rather than a day.
   */
  protected readonly periodName = computed(() =>
    new Intl.DateTimeFormat(this.i18n.locale(), { month: 'long', year: 'numeric' }).format(
      periodAsDate(this.period()),
    ),
  );
  protected readonly sourceKey = entrySourceKey;

  protected readonly envelopeDialog = signal<EnvelopeDialogTarget | null>(null);
  protected readonly entryDialog = signal<EntryDialogTarget | null>(null);
  protected readonly dialogBusy = signal(false);
  protected readonly dialogError = signal<ApiError | null>(null);

  protected readonly busyId = signal<string | null>(null);
  protected readonly actionError = signal<ApiError | null>(null);
  protected readonly actionErrorSubject = signal<string | null>(null);
  protected readonly notice = signal<MessageKey | null>(null);
  protected readonly pending = signal<PendingAction | null>(null);

  /** Today as `YYYY-MM-DD`, in the viewer's own calendar — a date is a calendar question. */
  protected readonly today = computed(() => {
    const now = new Date();
    return `${now.getFullYear()}-${`${now.getMonth() + 1}`.padStart(2, '0')}-${`${now.getDate()}`.padStart(2, '0')}`;
  });

  protected readonly confirmText = computed(() => {
    const action = this.pending();
    if (!action) {
      return null;
    }
    if (action.kind === 'archive') {
      return {
        heading: this.i18n.t('envelopes.archiveHeading', { name: action.envelope.name }),
        message: this.i18n.t('envelopes.archiveMessage'),
        action: this.i18n.t('envelopes.archive'),
        tone: 'ordinary' as const,
      };
    }
    if (action.kind === 'delete') {
      return {
        heading: this.i18n.t('envelopes.deleteHeading', { name: action.envelope.name }),
        // Says what goes and what stays. The transactions are the thing somebody is afraid of.
        message: this.i18n.t('envelopes.deleteMessage', { count: action.envelope.entryCount }),
        action: this.i18n.t('common.delete'),
        tone: 'destructive' as const,
      };
    }
    return {
      heading: this.i18n.t('envelopes.deleteEntryHeading'),
      message: this.i18n.t('envelopes.deleteEntryMessage', { name: action.envelope.name }),
      action: this.i18n.t('common.delete'),
      tone: 'destructive' as const,
    };
  });

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set(null);
    await this.auth.ensureLoaded();
    if (!this.auth.membership()) {
      this.noHousehold.set(true);
      this.loading.set(false);
      return;
    }
    try {
      // Which month to open on is decided before anything is fetched for it. The current month is
      // right whenever it has envelopes; when it does not and earlier months do, landing on an
      // empty screen looks broken to somebody who knows they set envelopes up. So the most recent
      // month that has any wins, and the header names it plainly.
      const periods = await firstValueFrom(this.service.periods());
      if (periods.length > 0 && !periods.includes(this.period())) {
        this.period.set(periods[0]);
      }

      const [page, categories] = await Promise.all([
        firstValueFrom(this.service.list(this.period())),
        firstValueFrom(this.categoriesService.list()),
      ]);
      this.envelopes.set(page.content);
      this.categories.set(categories.content);
      this.totals.set({
        allocated: page.totalAllocated,
        spent: page.totalSpent,
        remaining: page.totalRemaining,
        currency: page.currency,
      });
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

  /** Re-reads this period's envelopes, leaving the categories already in hand. */
  private async reload(): Promise<void> {
    try {
      const page = await firstValueFrom(this.service.list(this.period()));
      this.envelopes.set(page.content);
      this.totals.set({
        allocated: page.totalAllocated,
        spent: page.totalSpent,
        remaining: page.totalRemaining,
        currency: page.currency,
      });
      const open = this.openLogFor();
      if (open) {
        await this.openLog(open, true);
      }
    } catch (thrown) {
      this.loadError.set(asApiError(thrown));
    }
  }

  // ------------------------------------------------------------------------------ the period

  protected async shift(step: number): Promise<void> {
    this.period.set(shiftPeriod(this.period(), step));
    this.openLogFor.set(null);
    await this.reload();
  }

  protected async goToPeriod(value: string): Promise<void> {
    this.period.set(value);
    this.openLogFor.set(null);
    await this.reload();
  }

  protected toggleArchived(): void {
    this.showArchived.update((shown) => !shown);
  }

  // --------------------------------------------------------------------------------- the log

  protected async toggleLog(envelope: Envelope): Promise<void> {
    if (this.openLogFor() === envelope.id) {
      this.openLogFor.set(null);
      this.logEntries.set([]);
      return;
    }
    await this.openLog(envelope.id, false);
  }

  private async openLog(envelopeId: string, quietly: boolean): Promise<void> {
    this.openLogFor.set(envelopeId);
    if (!quietly) {
      this.logLoading.set(true);
    }
    try {
      this.logEntries.set((await firstValueFrom(this.service.log(envelopeId))).content);
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
      this.openLogFor.set(null);
    } finally {
      this.logLoading.set(false);
    }
  }

  // ---------------------------------------------------------------------------------- writing

  protected openAdd(): void {
    this.resetDialogState();
    this.envelopeDialog.set({ envelope: null });
  }

  protected openEdit(envelope: Envelope): void {
    this.resetDialogState();
    this.envelopeDialog.set({ envelope });
  }

  protected closeEnvelopeDialog(): void {
    this.envelopeDialog.set(null);
    this.resetDialogState();
  }

  protected async submitEnvelope(form: EnvelopeFormValue): Promise<void> {
    const target = this.envelopeDialog();
    if (!target) {
      return;
    }
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      if (target.envelope === null) {
        await firstValueFrom(this.service.create(buildEnvelopeCreate(form)));
        this.notice.set('envelopes.created');
        // A new envelope may be for another month; follow it there rather than leaving the
        // person looking at a list it is not in.
        if (form.period !== this.period()) {
          this.period.set(form.period);
        }
      } else {
        const patch = buildEnvelopePatch(target.envelope, form);
        if (patchIsEmpty(patch)) {
          this.closeEnvelopeDialog();
          return;
        }
        await firstValueFrom(this.service.update(target.envelope.id, patch));
        this.notice.set('envelopes.updated');
      }
      this.envelopeDialog.set(null);
      await this.reload();
    } catch (thrown) {
      this.dialogError.set(asApiError(thrown));
    } finally {
      this.dialogBusy.set(false);
    }
  }

  protected openQuickAdd(envelope: Envelope): void {
    this.resetDialogState();
    this.entryDialog.set({ envelope, entry: null });
  }

  protected openEditEntry(envelope: Envelope, entry: EnvelopeEntry): void {
    this.resetDialogState();
    this.entryDialog.set({ envelope, entry });
  }

  protected closeEntryDialog(): void {
    this.entryDialog.set(null);
    this.resetDialogState();
  }

  protected async submitEntry(form: EntryFormValue): Promise<void> {
    const target = this.entryDialog();
    if (!target) {
      return;
    }
    this.dialogBusy.set(true);
    this.dialogError.set(null);
    try {
      if (target.entry === null) {
        await firstValueFrom(this.service.logEntry(target.envelope.id, buildEntryCreate(form)));
        this.notice.set('envelopes.logged');
      } else {
        const patch = buildEntryPatch(target.entry, form);
        if (patchIsEmpty(patch)) {
          this.closeEntryDialog();
          return;
        }
        await firstValueFrom(this.service.updateEntry(target.envelope.id, target.entry.id, patch));
        this.notice.set('envelopes.entryUpdated');
      }
      this.entryDialog.set(null);
      await this.reload();
    } catch (thrown) {
      this.dialogError.set(asApiError(thrown));
    } finally {
      this.dialogBusy.set(false);
    }
  }

  protected askToArchive(envelope: Envelope): void {
    this.pending.set({ kind: 'archive', envelope });
  }

  protected askToDelete(envelope: Envelope): void {
    this.pending.set({ kind: 'delete', envelope });
  }

  protected askToDeleteEntry(envelope: Envelope, entry: EnvelopeEntry): void {
    this.pending.set({ kind: 'deleteEntry', envelope, entry });
  }

  protected cancelPending(): void {
    this.pending.set(null);
  }

  protected async confirmPending(): Promise<void> {
    const action = this.pending();
    if (!action) {
      return;
    }
    this.pending.set(null);
    switch (action.kind) {
      case 'archive':
        await this.run(
          action.envelope.id,
          action.envelope.name,
          () => this.service.archive(action.envelope.id),
          'envelopes.archived',
        );
        break;
      case 'delete':
        await this.run(
          action.envelope.id,
          action.envelope.name,
          () => this.service.remove(action.envelope.id),
          'envelopes.deleted',
        );
        break;
      case 'deleteEntry':
        await this.run(
          action.entry.id,
          action.envelope.name,
          () => this.service.removeEntry(action.envelope.id, action.entry.id),
          'envelopes.entryDeleted',
        );
        break;
    }
  }

  protected async unarchive(envelope: Envelope): Promise<void> {
    await this.run(
      envelope.id,
      envelope.name,
      () => this.service.unarchive(envelope.id),
      'envelopes.unarchived',
    );
  }

  /**
   * Runs one row-level request, keeping the failure beside the thing it was about.
   *
   * A refused write must not empty the screen: the state is reloaded only on success, and on
   * failure the list is exactly as it was with an explanation naming the envelope.
   */
  private async run(
    id: string,
    subject: string,
    request: () => Observable<unknown>,
    said: MessageKey,
  ): Promise<void> {
    this.busyId.set(id);
    this.actionError.set(null);
    this.actionErrorSubject.set(null);
    this.notice.set(null);
    try {
      await firstValueFrom(request());
      this.notice.set(said);
      await this.reload();
    } catch (thrown) {
      this.actionError.set(asApiError(thrown));
      this.actionErrorSubject.set(subject);
    } finally {
      this.busyId.set(null);
    }
  }

  /**
   * Which label the log toggle wears.
   *
   * Two keys rather than "1 entries": the message catalogue has no plural machinery, and the
   * accounts screen settled this the same way.
   */
  protected showLogKey(envelope: Envelope): MessageKey {
    if (this.openLogFor() === envelope.id) {
      return 'envelopes.hideLog';
    }
    return envelope.entryCount === 1 ? 'envelopes.showLogOne' : 'envelopes.showLog';
  }

  /** Whether an envelope is overspent, read from the sign the server sent. */
  protected overspent(envelope: Envelope): boolean {
    return envelope.remaining.trimStart().startsWith('-');
  }

  /**
   * How full the bar is, as a percentage.
   *
   * **The only place this client puts money through a `Number`, and deliberately so.** ADR-0006
   * forbids it for values that are stored, displayed or compared; this produces neither — it is a
   * bar width in whole percent, and a rounding error of a thousandth of a pixel is not a defect.
   * Every figure a person reads comes from the server as a string and stays one.
   *
   * Clamped to 100 for the bar's width only. The figure beside it is not clamped: an overspent
   * envelope shows its real negative remaining, and the bar is marked overspent so a full bar
   * cannot be mistaken for "exactly on plan".
   */
  protected spentPercent(envelope: Envelope): number {
    const amount = Number(envelope.amount);
    const spent = Number(envelope.spent);
    if (!Number.isFinite(amount) || amount <= 0) {
      return spent > 0 ? 100 : 0;
    }
    return Math.min(100, Math.round((spent / amount) * 100));
  }

  private resetDialogState(): void {
    this.dialogBusy.set(false);
    this.dialogError.set(null);
    this.notice.set(null);
    this.actionError.set(null);
    this.actionErrorSubject.set(null);
  }
}
