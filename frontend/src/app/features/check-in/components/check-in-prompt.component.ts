import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { Week } from '../data/check-in.models';
import { CheckInService } from '../data/check-in.service';
import { Said } from '../data/check-in-text';

/**
 * The home card's content as a prompt at the top of the ledger (`weekly-check-in.md`, UI, "Where
 * it is reached"): *"Check-in · Due Sunday. 3 charges waiting on a category. · Start now"*.
 *
 * **A convenience, never a gate.** It asks for the week containing the server's today; a refusal
 * of any kind (no household, a network failure) simply shows nothing, and the ledger under it is
 * untouched. Hidden on a completed week.
 */
@Component({
  selector: 'app-check-in-prompt',
  imports: [RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (shown(); as week) {
      <aside class="card prompt" [attr.aria-label]="'checkIn.prompt.label' | t">
        <div class="prompt-text">
          <p class="card-title">{{ 'checkIn.prompt.label' | t }}</p>
          <p class="card-note">
            {{ 'checkIn.prompt.due' | t }} {{ waiting().key | t: waiting().params }}
          </p>
        </div>
        <div>
          <a class="btn btn-primary" routerLink="/check-in">
            {{ (week.status === 'DRAFT' ? 'checkIn.prompt.continue' : 'checkIn.prompt.start') | t }}
          </a>
        </div>
      </aside>
    }
  `,
  styles: `
    .prompt {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: var(--space-3);
    }

    .prompt-text p {
      margin: 0;
    }
  `,
})
export class CheckInPromptComponent {
  private readonly service = inject(CheckInService);
  private readonly week = signal<Week | null>(null);

  protected readonly shown = computed(() => {
    const week = this.week();
    return week && week.status !== 'COMPLETED' ? week : null;
  });

  /** How many rows still want a category: the server's counts, never re-derived here. */
  protected readonly waiting = computed<Said>(() => {
    const step = this.week()?.steps.find((candidate) => candidate.step === 'CATEGORIZE');
    const count = step ? Math.max(0, step.questions - step.answeredCount) : 0;
    return count === 0
      ? { key: 'checkIn.prompt.quiet' }
      : count === 1
        ? { key: 'checkIn.prompt.waitingOne' }
        : { key: 'checkIn.prompt.waiting', params: { count } };
  });

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.week.set(await firstValueFrom(this.service.current()));
    } catch {
      this.week.set(null);
    }
  }
}
