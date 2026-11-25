import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import {
  DATE_FORMAT_LABELS,
  DELIMITER_LABELS,
  GROUPING_LABELS,
  ImportSettings,
  ROLE_LABELS,
} from '../data/import-data';
import {
  COLUMN_ROLES,
  ColumnRole,
  DATE_FORMATS,
  DELIMITERS,
  GROUPING_SEPARATORS,
  ImportFormat,
  ImportPreview,
} from '../data/import.models';

type MoneyOut = 'negative' | 'positive' | 'columns';

/**
 * How to read the file: the CSV mapping, and the charset or date format when the server asks.
 *
 * Presentational. It shows the settings it is given and reports each change as a whole new
 * {@link ImportSettings}; **nothing it shows is applied until the person presses Update preview**
 * (rule 5). Re-running the preview on every change would also spend the rate limit on keystrokes.
 *
 * Every cell from the file is rendered as text inside `<bdi dir="auto">`. The table of first
 * records scrolls inside its own box, so a wide file never scrolls the page sideways.
 */
@Component({
  selector: 'app-import-mapping',
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './import-mapping.component.html',
  styleUrl: './import-mapping.component.css',
})
export class ImportMappingComponent {
  readonly preview = input.required<ImportPreview>();
  readonly format = input.required<ImportFormat>();
  readonly settings = input.required<ImportSettings>();
  /** Whether the settings shown came from the account's saved mapping. */
  readonly fromSaved = input(false);
  readonly busy = input(false);

  readonly settingsChange = output<ImportSettings>();
  readonly updateRequested = output<void>();

  protected readonly roles = COLUMN_ROLES;
  protected readonly roleLabels = ROLE_LABELS;
  protected readonly delimiters = DELIMITERS;
  protected readonly delimiterLabels = DELIMITER_LABELS;
  protected readonly groupings = GROUPING_SEPARATORS;
  protected readonly groupingLabels = GROUPING_LABELS;
  protected readonly dateLabels = DATE_FORMAT_LABELS;

  protected readonly csv = computed(() => (this.format() === 'CSV' ? this.preview().csv : null));

  protected readonly asksCharset = computed(
    () => this.preview().needs.includes('CHARSET') || this.preview().charsetCandidates.length > 0,
  );

  protected readonly asksDate = computed(() => this.preview().needs.includes('DATE_FORMAT'));

  /** OFX dates are fixed by the standard; the others may be chosen. */
  protected readonly showsDate = computed(() => this.format() !== 'OFX');

  /** When the server narrowed it down, only those; otherwise the whole allowlist. */
  protected readonly dateChoices = computed<readonly string[]>(() => {
    const candidates = this.preview().dateFormatCandidates;
    return this.asksDate() && candidates.length > 0 ? candidates : DATE_FORMATS;
  });

  protected readonly charsets = computed(() => this.preview().charsetsAllowed);

  protected readonly samplesFor = computed(() => {
    const samples = new Map<string, readonly string[]>();
    for (const candidate of this.preview().charsetCandidates) {
      samples.set(candidate.charset, candidate.samples);
    }
    return samples;
  });

  protected readonly headerChanged = computed(
    () => this.fromSaved() && this.preview().csv?.mappingMatchesSaved === false,
  );

  protected readonly moneyOut = computed<MoneyOut>(() => {
    const settings = this.settings();
    if (settings.invertSign) {
      return 'positive';
    }
    return settings.columns.some(
      (role) => role === 'DEBIT' || role === 'CREDIT' || role === 'DIRECTION',
    )
      ? 'columns'
      : 'negative';
  });

  protected readonly usesDirection = computed(() => this.settings().columns.includes('DIRECTION'));

  protected change(patch: Partial<ImportSettings>): void {
    this.settingsChange.emit({ ...this.settings(), ...patch });
  }

  protected setRole(index: number, value: string): void {
    const columns = [...this.settings().columns];
    columns[index] = value === '' ? '' : (value as ColumnRole);
    this.change({ columns });
  }

  protected setMoneyOut(value: string): void {
    this.change({ invertSign: value === 'positive' });
  }

  protected setHeaderRow(value: string): void {
    const parsed = /^\d{1,2}$/.test(value) ? Number.parseInt(value, 10) : 0;
    this.change({ headerRow: Math.min(parsed, 20) });
  }

  protected label(index: number): string | null {
    const labels = this.csv()?.headerLabels ?? [];
    const label = labels[index];
    return label !== undefined && label.trim() !== '' ? label : null;
  }
}
