import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { AuthService } from '../../../core/auth.service';
import { I18nService } from '../../../core/i18n/i18n.service';
import { MessageKey } from '../../../core/i18n/messages';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { DateTimePipe } from '../../../core/i18n/format.pipes';
import { ConfirmDialogComponent } from '../../../shared/components/confirm-dialog.component';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { NoHouseholdComponent } from '../../../shared/components/no-household.component';
import { Account } from '../../accounts/data/account.models';
import { AccountsService } from '../../accounts/data/accounts.service';
import { ImportMappingComponent } from '../components/import-mapping.component';
import { ImportPreviewComponent } from '../components/import-preview.component';
import {
  ImportSettings,
  MAX_FILE_BYTES,
  commitRequestFor,
  decisionsFor,
  emptySettings,
  encodeBase64,
  formatFromName,
  optionsFor,
  rowsToImport,
  settingsFrom,
  suspectedRows,
} from '../data/import-data';
import { ImportBatch, ImportFormat, ImportPreview, ImportRequest } from '../data/import.models';
import { ImportsService } from '../data/imports.service';

/** The file, in memory only. Never written to any browser storage (rule 11, ADR-0038). */
interface ChosenFile {
  base64: string;
  size: number;
}

/** What the stale-preview notice says: how many rows somebody else imported, when we can tell. */
interface StaleNotice {
  newlyDuplicate: number;
}

const FORMATS: readonly ImportFormat[] = ['OFX', 'QIF', 'CSV'];

const FORMAT_LABELS: Readonly<Record<ImportFormat, MessageKey>> = {
  CSV: 'import.format.CSV',
  OFX: 'import.format.OFX',
  QIF: 'import.format.QIF',
};

/**
 * Import a statement: choose the file and the account, say how to read it, look at what will be
 * added, confirm (docs/features/file-import.md, "UI").
 *
 * Smart: it holds the file, runs the preview and the commit, and keeps the history. The two
 * components below render a preview and the mapping and report what the person chose.
 *
 * Rules that are the feature's and not UI taste:
 *
 * - **The preview is the confirmation** (rule 1). It writes nothing; the commit sends the same file
 *   again with the digest the preview returned, and {@link commitRequestFor} builds it from **the
 *   request that was previewed**, never from the form as it is now. A setting changed since then
 *   marks the preview out of date and the confirm button waits for Update preview.
 * - **The file stays in memory.** It is read with `FileReader` into a signal on this page and goes
 *   to the server as base64 inside JSON. It is never put in `localStorage`, `sessionStorage`,
 *   IndexedDB or a cache, and its name is never sent (decision 4).
 * - **Nothing here parses an amount** (ADR-0006). The counts are integers; every figure is the
 *   server's string, formatted for display.
 * - **The role decides what is offered; the server decides what is allowed.** A `VIEWER` gets a
 *   plain sentence instead of a form, and would be refused `403 read-only-role` before the server
 *   decoded a byte of the file.
 */
@Component({
  selector: 'app-import-page',
  imports: [
    RouterLink,
    TranslatePipe,
    DateTimePipe,
    ErrorAlertComponent,
    NoHouseholdComponent,
    ConfirmDialogComponent,
    ImportMappingComponent,
    ImportPreviewComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './import.page.html',
  styleUrl: './import.page.css',
})
export class ImportPage {
  private readonly imports = inject(ImportsService);
  private readonly accountsService = inject(AccountsService);
  private readonly auth = inject(AuthService);
  private readonly i18n = inject(I18nService);

  /** `?accountId=` from an account row's Import action. Only preselects; it is checked below. */
  private readonly wantedAccountId = inject(ActivatedRoute).snapshot.queryParamMap.get('accountId');

  protected readonly formats = FORMATS;
  protected readonly formatLabels = FORMAT_LABELS;

  protected readonly loading = signal(true);
  protected readonly loadError = signal<ApiError | null>(null);
  protected readonly noHousehold = signal(false);
  protected readonly accounts = signal<readonly Account[]>([]);

  protected readonly canWrite = computed(() => {
    const role = this.auth.membership()?.role;
    return role === 'OWNER' || role === 'MEMBER';
  });

  /** Only an account that can still take a transaction is offered, as the transfer sheet does. */
  protected readonly openAccounts = computed(() =>
    this.accounts().filter((account) => !account.archived),
  );

  protected readonly selectedAccountId = signal('');
  protected readonly file = signal<ChosenFile | null>(null);
  protected readonly fileProblem = signal<MessageKey | null>(null);
  protected readonly format = signal<ImportFormat | ''>('');
  protected readonly dragging = signal(false);

  protected readonly settings = signal<ImportSettings>(emptySettings());
  /** Whether a CSV mapping has been confirmed with Update preview, so it is sent. */
  private readonly mapped = signal(false);
  protected readonly fromSaved = signal(false);

  protected readonly preview = signal<ImportPreview | null>(null);
  /** The exact request the preview on screen came from. The commit resends it. */
  private readonly previewed = signal<ImportRequest | null>(null);
  /** A setting changed since the preview on screen, so it no longer describes what would happen. */
  protected readonly outOfDate = signal(false);

  protected readonly included = signal<ReadonlySet<number>>(new Set());
  private readonly includeAllByDefault = signal(false);
  protected readonly saveMapping = signal(false);

  protected readonly busy = signal<'preview' | 'commit' | null>(null);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly stale = signal<StaleNotice | null>(null);
  /** The sentence in the live region: what is happening, for anybody who cannot see the spinner. */
  protected readonly status = signal<MessageKey | null>(null);

  protected readonly done = signal<ImportBatch | null>(null);
  protected readonly undoTarget = signal<ImportBatch | null>(null);
  protected readonly undoBusy = signal(false);
  protected readonly undoError = signal<ApiError | null>(null);
  protected readonly undone = signal<number | null>(null);

  protected readonly history = signal<readonly ImportBatch[]>([]);

  protected readonly selectedAccount = computed(
    () => this.openAccounts().find((account) => account.id === this.selectedAccountId()) ?? null,
  );

  protected readonly canPreview = computed(
    () =>
      this.selectedAccount() !== null &&
      this.file() !== null &&
      this.format() !== '' &&
      this.busy() === null,
  );

  /** Step 2 is shown for every CSV, and for any file the server had a question about. */
  protected readonly showsMapping = computed(() => {
    const preview = this.preview();
    return preview !== null && (preview.format === 'CSV' || preview.needs.length > 0);
  });

  /** A preview that answers everything and still describes what is on screen. */
  protected readonly ready = computed(() => {
    const preview = this.preview();
    return (
      preview !== null &&
      preview.previewDigest !== null &&
      preview.needs.length === 0 &&
      preview.counts !== null &&
      !this.outOfDate()
    );
  });

  protected readonly toImport = computed(() => {
    const preview = this.preview();
    if (!preview?.counts) {
      return 0;
    }
    return rowsToImport(preview.counts.new, preview.attention, this.included());
  });

  protected readonly accountLabel = computed(() => {
    const account = this.preview()?.account ?? this.selectedAccount();
    return account ? this.labelOf(account) : '';
  });

  protected readonly fileSize = computed(() => {
    const chosen = this.file();
    if (!chosen) {
      return '';
    }
    return new Intl.NumberFormat(this.i18n.locale(), {
      style: 'unit',
      unit: 'kilobyte',
      maximumFractionDigits: 0,
    }).format(Math.max(1, Math.ceil(chosen.size / 1024)));
  });

  protected readonly undoText = computed(() => {
    const target = this.undoTarget();
    if (!target) {
      return null;
    }
    return {
      heading: this.i18n.t('import.undoHeading'),
      // Names the count and the account, and the links that go with the rows (decision 5).
      message: this.i18n.t('import.undoMessage', {
        count: target.removable,
        account: target.accountName,
      }),
      action: this.i18n.t('import.undo'),
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
    if (!this.canWrite()) {
      // Nothing to fetch: a viewer gets a sentence, not a form that would be refused.
      this.loading.set(false);
      return;
    }
    try {
      const accounts = await firstValueFrom(this.accountsService.list());
      this.accounts.set(accounts.content);
      const wanted = this.wantedAccountId;
      if (wanted && this.openAccounts().some((account) => account.id === wanted)) {
        this.selectedAccountId.set(wanted);
      }
      await this.loadHistory();
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

  private async loadHistory(): Promise<void> {
    try {
      this.history.set((await firstValueFrom(this.imports.list())).content);
    } catch {
      // The history is a convenience beside the import itself; an import still works without it.
      this.history.set([]);
    }
  }

  // ------------------------------------------------------------------------------ step 1

  protected chooseAccount(id: string): void {
    this.selectedAccountId.set(id);
    this.resetPreview();
  }

  protected chooseFormat(value: string): void {
    this.format.set(FORMATS.includes(value as ImportFormat) ? (value as ImportFormat) : '');
    this.settings.set(emptySettings());
    this.resetPreview();
  }

  protected fileChosen(event: Event): void {
    const element = event.target as HTMLInputElement;
    void this.take(element.files?.item(0) ?? null);
  }

  protected dragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  protected dragLeave(): void {
    this.dragging.set(false);
  }

  protected dropped(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    void this.take(event.dataTransfer?.files.item(0) ?? null);
  }

  /**
   * Reads the chosen file into memory, as bytes, and encodes them.
   *
   * The size check is a courtesy; the server's is the control (rule 3). The bytes are not decoded
   * as text or parsed: what reaches the server is what the bank wrote.
   */
  private async take(chosen: File | null): Promise<void> {
    this.file.set(null);
    this.fileProblem.set(null);
    this.settings.set(emptySettings());
    this.done.set(null);
    this.undone.set(null);
    this.resetPreview();
    if (!chosen) {
      return;
    }
    if (chosen.size > MAX_FILE_BYTES) {
      this.fileProblem.set('import.tooLarge');
      return;
    }
    const suggested = formatFromName(chosen.name);
    if (suggested) {
      this.format.set(suggested);
    }
    try {
      const bytes = new Uint8Array(await chosen.arrayBuffer());
      this.file.set({ base64: encodeBase64(bytes), size: bytes.length });
    } catch {
      this.fileProblem.set('import.readFailed');
    }
  }

  // ------------------------------------------------------------------------------ preview

  protected async runPreview(): Promise<void> {
    const account = this.selectedAccount();
    const chosen = this.file();
    const format = this.format();
    if (!account || !chosen || format === '') {
      return;
    }
    const request: ImportRequest = {
      accountId: account.id,
      format,
      file: chosen.base64,
    };
    const options = optionsFor(format, this.settings(), this.mapped());
    if (options) {
      request.options = options;
    }
    await this.previewWith(request);
  }

  /** Update preview: the settings on screen are now the ones to read the file with. */
  protected async updatePreview(): Promise<void> {
    if (this.format() === 'CSV') {
      this.mapped.set(true);
    }
    this.stale.set(null);
    await this.runPreview();
  }

  private async previewWith(request: ImportRequest, after?: ImportPreview): Promise<void> {
    this.busy.set('preview');
    this.error.set(null);
    this.status.set('import.working');
    try {
      const preview = await firstValueFrom(this.imports.preview(request));
      await this.adopt(preview);
      this.preview.set(preview);
      this.previewed.set(request);
      this.outOfDate.set(false);
      this.included.set(new Set());
      this.includeAllByDefault.set(false);
      this.status.set('import.ready');
      if (after?.counts && preview.counts) {
        this.stale.set({
          newlyDuplicate: Math.max(0, preview.counts.duplicate - after.counts.duplicate),
        });
      }
    } catch (thrown) {
      this.error.set(asApiError(thrown));
      this.status.set(null);
    } finally {
      this.busy.set(null);
    }
  }

  /**
   * Fills the mapping step from what the server found, the first time it is shown.
   *
   * The account's saved mapping first, else the server's suggestion — **shown, not applied**: the
   * person still presses Update preview (rule 5). A charset the server suggests is preselected in
   * its select on the same terms.
   */
  private async adopt(preview: ImportPreview): Promise<void> {
    if (preview.csv && !this.mapped()) {
      // A saved mapping that cannot be fetched is a convenience lost, not a failed preview: the
      // server's suggestion is shown instead.
      const saved = await firstValueFrom(this.imports.mapping(preview.account.id)).catch(
        () => null,
      );
      this.settings.set(settingsFrom(preview.csv, saved, this.settings()));
      this.fromSaved.set(saved !== null);
    }
    if (this.settings().charset === '' && preview.suggestedCharset) {
      this.settings.update((current) => ({ ...current, charset: preview.suggestedCharset ?? '' }));
    }
  }

  protected settingsChanged(next: ImportSettings): void {
    this.settings.set(next);
    if (this.preview()) {
      this.outOfDate.set(true);
    }
  }

  protected toggle(row: number): void {
    const next = new Set(this.included());
    if (next.has(row)) {
      next.delete(row);
    } else {
      next.add(row);
    }
    this.included.set(next);
  }

  protected includeAll(): void {
    const preview = this.preview();
    this.included.set(new Set(preview ? suspectedRows(preview.attention) : []));
    this.includeAllByDefault.set(true);
  }

  protected excludeAll(): void {
    this.included.set(new Set());
    this.includeAllByDefault.set(false);
  }

  // ------------------------------------------------------------------------------ commit

  protected async commit(): Promise<void> {
    const preview = this.preview();
    const previewed = this.previewed();
    if (!this.ready() || !preview?.previewDigest || !previewed) {
      return;
    }
    const request = commitRequestFor(
      previewed,
      preview.previewDigest,
      decisionsFor(preview.attention, this.included(), this.includeAllByDefault()),
      this.saveMapping(),
    );
    this.busy.set('commit');
    this.error.set(null);
    this.stale.set(null);
    this.status.set('import.committing');
    try {
      const batch = await firstValueFrom(this.imports.commit(request));
      this.done.set(batch);
      this.status.set(null);
      this.forgetFile();
      await this.loadHistory();
    } catch (thrown) {
      const error = asApiError(thrown);
      this.busy.set(null);
      if (error.status === 409 && error.code === 'import-preview-stale') {
        // Somebody imported or typed rows in between. Nothing was written; preview again with the
        // same request and say what moved, rather than committing something nobody looked at.
        await this.previewWith(previewed, preview);
        return;
      }
      this.error.set(error);
      this.status.set(null);
    } finally {
      this.busy.set(null);
    }
  }

  // ------------------------------------------------------------------------------ undo

  protected askToUndo(batch: ImportBatch): void {
    this.undoError.set(null);
    this.undoTarget.set(batch);
  }

  protected cancelUndo(): void {
    this.undoTarget.set(null);
  }

  protected async confirmUndo(): Promise<void> {
    const target = this.undoTarget();
    if (!target) {
      return;
    }
    this.undoBusy.set(true);
    this.undoError.set(null);
    try {
      await firstValueFrom(this.imports.undo(target.id));
      this.undone.set(target.removable);
      if (this.done()?.id === target.id) {
        this.done.set(null);
      }
      await this.loadHistory();
    } catch (thrown) {
      this.undoError.set(asApiError(thrown));
    } finally {
      this.undoBusy.set(false);
      this.undoTarget.set(null);
    }
  }

  protected startOver(): void {
    this.forgetFile();
    this.format.set('');
    this.done.set(null);
    this.undone.set(null);
    this.error.set(null);
  }

  protected labelOf(account: { name: string; lastFour: string | null }): string {
    return account.lastFour
      ? this.i18n.t('accounts.nameWithDigits', { name: account.name, digits: account.lastFour })
      : account.name;
  }

  /** Drops the file and everything read from it. After a commit nothing of it is kept here either. */
  private forgetFile(): void {
    this.file.set(null);
    this.settings.set(emptySettings());
    this.resetPreview();
  }

  private resetPreview(): void {
    this.preview.set(null);
    this.previewed.set(null);
    this.outOfDate.set(false);
    this.mapped.set(false);
    this.fromSaved.set(false);
    this.included.set(new Set());
    this.includeAllByDefault.set(false);
    this.saveMapping.set(false);
    this.error.set(null);
    this.stale.set(null);
    this.status.set(null);
  }
}
