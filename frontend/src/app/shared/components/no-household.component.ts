import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TranslatePipe } from '../../core/i18n/t.pipe';

/**
 * A signed-in user who has no membership — an OIDC-provisioned user nobody has invited yet, or
 * someone who left. Not an error: they are exactly who they should be, and the way forward is an
 * invitation (docs/features/authentication-and-households.md).
 */
@Component({
  selector: 'app-no-household',
  imports: [TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="card" aria-labelledby="no-household-heading">
      <h2 class="card-title" id="no-household-heading">{{ 'noHousehold.title' | t }}</h2>
      <p class="card-note">{{ 'noHousehold.body' | t }}</p>
    </section>
  `,
})
export class NoHouseholdComponent {}
