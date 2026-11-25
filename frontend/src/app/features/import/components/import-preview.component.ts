import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { DateOnlyPipe, MoneyPipe } from '../../../core/i18n/format.pipes';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { rowProblemKey, sameAmount } from '../data/import-data';
import { ImportAttention, ImportPreview } from '../data/import.models';

/**
 * What a preview says will happen: the counts, the money, the rows that need a look, and the first
 * rows to be added.
 *
 * Presentational. It renders the server's figures and reports which suspected rows the person
 * ticked; it decides nothing. Three rules are visible here:
 *
 * - **No amount is parsed.** Money in, money out and both balances are the server's arithmetic,
 *   handed to the money pipe as strings (ADR-0006). The one comparison — does the bank's balance
 *   match ours — is done on the text by {@link sameAmount}, never as numbers.
 * - **Every payee and cell from the file is text, isolated.** Interpolated, never bound as HTML, and
 *   inside `<bdi>` so a right-to-left override in somebody's statement cannot reorder the words
 *   around it.
 * - **Suspected rows are excluded unless ticked** (decision 3). Each checkbox's label names the
 *   row — date, payee, amount — so a screen reader hears which one it is.
 */
@Component({
  selector: 'app-import-preview',
  imports: [TranslatePipe, MoneyPipe, DateOnlyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './import-preview.component.html',
  styleUrl: './import-preview.component.css',
})
export class ImportPreviewComponent {
  readonly preview = input.required<ImportPreview>();
  /** The suspected rows the person has ticked. */
  readonly included = input.required<ReadonlySet<number>>();
  /** `name •1234`, already composed by the page. */
  readonly accountLabel = input.required<string>();

  readonly toggled = output<number>();
  readonly includeAllRequested = output<void>();
  readonly excludeAllRequested = output<void>();

  protected readonly rowProblemKey = rowProblemKey;

  protected readonly currency = computed(() => this.preview().account.currency);

  protected readonly suspected = computed(() => this.byKind('SUSPECTED_DUPLICATE'));
  protected readonly duplicates = computed(() => this.byKind('DUPLICATE'));
  protected readonly unreadable = computed(() => this.byKind('UNREADABLE'));

  /** For OFX: what the bank says the balance is, and whether ours will agree after the import. */
  protected readonly bank = computed(() => {
    const preview = this.preview();
    const bankBalance = preview.statement?.bankClosingBalance ?? null;
    if (bankBalance === null || preview.balanceAfter === null) {
      return null;
    }
    return {
      bank: bankBalance,
      after: preview.balanceAfter,
      matches: sameAmount(bankBalance, preview.balanceAfter),
    };
  });

  protected outgoing(amount: string | null): boolean {
    return (amount ?? '').trimStart().startsWith('-');
  }

  private byKind(kind: ImportAttention['kind']): ImportAttention[] {
    return this.preview().attention.filter((item) => item.kind === kind);
  }
}
