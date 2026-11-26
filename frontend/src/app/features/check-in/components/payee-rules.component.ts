import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ApiError, asApiError } from '../../../core/api-error';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { ErrorAlertComponent } from '../../../shared/components/error-alert.component';
import { PayeeRule } from '../data/check-in.models';
import { CheckInService } from '../data/check-in.service';

/**
 * The categories page's **Payee rules** disclosure (`weekly-check-in.md`, UI, "Rules"):
 * *"SQ \*BLUE BOTTLE → Dining · Remove"*.
 *
 * Rules are only ever created by completing a check-in, so this lists and removes and nothing
 * else. A `VIEWER` sees the list with no Remove, and the server refuses the delete regardless.
 * The payee arrives from a bank file and renders in its own `dir="auto"` element.
 */
@Component({
  selector: 'app-payee-rules',
  imports: [TranslatePipe, ErrorAlertComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card" aria-labelledby="payee-rules-heading">
      <h2 class="card-title" id="payee-rules-heading">{{ 'checkIn.rules.payeeHeading' | t }}</h2>
      <p class="card-note">{{ 'checkIn.rules.payeeBody' | t }}</p>
      <app-error-alert [error]="error()" />
      @if (notice()) {
        <div class="alert alert-success" role="status">
          <p>{{ 'checkIn.rules.removed' | t }}</p>
        </div>
      }
      @if (rules().length === 0) {
        <p class="card-note">{{ 'checkIn.rules.payeeEmpty' | t }}</p>
      } @else {
        <div>
          <button
            type="button"
            class="btn btn-ghost"
            [attr.aria-expanded]="open()"
            aria-controls="payee-rules-list"
            (click)="toggle()"
          >
            @if (open()) {
              {{ 'checkIn.rules.hide' | t }}
            } @else {
              {{ 'checkIn.rules.show' | t: { count: total() } }}
            }
          </button>
        </div>
        @if (open()) {
          <ul class="rows" id="payee-rules-list">
            @for (rule of rules(); track rule.id) {
              <li class="row">
                <div class="row-main">
                  <span class="row-title" dir="auto">{{ rule.payeeName }}</span>
                  <span class="row-sub">
                    {{ 'checkIn.rules.files' | t: { category: rule.categoryName } }}
                  </span>
                  @if (rule.categoryArchived) {
                    <span class="row-sub">{{ 'checkIn.rules.archived' | t }}</span>
                  }
                </div>
                @if (canWrite()) {
                  <div class="row-actions">
                    <button
                      type="button"
                      class="btn btn-secondary btn-small"
                      [disabled]="busyId() !== null"
                      (click)="remove(rule)"
                    >
                      {{ 'checkIn.rules.remove' | t }}
                      <span class="visually-hidden" dir="auto">{{ rule.payeeName }}</span>
                    </button>
                  </div>
                }
              </li>
            }
          </ul>
          @if (total() > rules().length) {
            <p class="card-note">
              {{ 'checkIn.rules.more' | t: { count: total() - rules().length } }}
            </p>
          }
        }
      }
    </section>
  `,
})
export class PayeeRulesComponent {
  private readonly service = inject(CheckInService);

  readonly canWrite = input(false);

  protected readonly rules = signal<readonly PayeeRule[]>([]);
  protected readonly total = signal(0);
  protected readonly open = signal(false);
  protected readonly busyId = signal<string | null>(null);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly notice = signal(false);

  constructor() {
    void this.load();
  }

  protected toggle(): void {
    this.open.update((open) => !open);
  }

  protected async remove(rule: PayeeRule): Promise<void> {
    if (!this.canWrite()) {
      return;
    }
    this.busyId.set(rule.id);
    this.error.set(null);
    this.notice.set(false);
    try {
      await firstValueFrom(this.service.removePayeeRule(rule.id));
      this.notice.set(true);
      await this.load();
    } catch (thrown) {
      this.error.set(asApiError(thrown));
    } finally {
      this.busyId.set(null);
    }
  }

  private async load(): Promise<void> {
    try {
      const page = await firstValueFrom(this.service.payeeRules());
      this.rules.set(page.content);
      this.total.set(page.totalElements);
    } catch (thrown) {
      this.error.set(asApiError(thrown));
    }
  }
}
