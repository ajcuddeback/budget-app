import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { DateTimePipe } from '../../../core/i18n/format.pipes';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { InstanceInfo } from '../data/admin.models';

/**
 * "Is my server alright" — version, when it was set up, who administers it, how many accounts
 * exist. Presentational.
 *
 * The count is of accounts. **Nothing on this screen is a figure of anybody's money** (ADR-0026):
 * an operator opening the console to change a login setting should not be shown somebody's rent.
 */
@Component({
  selector: 'app-instance-facts',
  imports: [TranslatePipe, DateTimePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './instance-facts.component.html',
  styleUrl: './instance-facts.component.css',
})
export class InstanceFactsComponent {
  readonly instance = input.required<InstanceInfo>();
}
