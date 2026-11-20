import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { ReactiveFormsModule } from '@angular/forms';
import { merge, of, switchMap } from 'rxjs';
import { MonthNamePipe, WeekdayNamePipe } from '../../../core/i18n/format.pipes';
import { TranslatePipe } from '../../../core/i18n/t.pipe';
import { FieldErrorComponent } from '../../../shared/components/field-error.component';
import { ScheduleFormGroup } from '../data/schedule-form';
import {
  DAYS_OF_MONTH,
  DAYS_OF_WEEK,
  MONTHS_OF_YEAR,
  SCHEDULE_KINDS,
  isoWeekday,
  scheduleKindKey,
  usesParameter,
} from '../data/schedule.models';

/**
 * The schedule editor: five kinds, and **only the parameters each one uses**.
 *
 * Presentational — it takes a form group and renders it, with no injected service of its own. Two
 * things here are the feature's rules rather than UI taste:
 *
 * - **A kind shows its own parameters and nothing else.** Sending a parameter a kind has no use
 *   for is a `400`: the server refuses it rather than ignoring it, because ignoring it loses an
 *   edit. The owning dialog clears the values; this decides which fields exist, and both read the
 *   same table (`SCHEDULE_PARAMETERS`) so they cannot disagree.
 * - **`IRREGULAR` shows no parameters at all**, and says why. A bill nobody can predict is still a
 *   bill they want listed; it projects nothing, so there is nothing to parameterise (ADR-0032).
 */
@Component({
  selector: 'app-schedule-fields',
  imports: [
    ReactiveFormsModule,
    TranslatePipe,
    MonthNamePipe,
    WeekdayNamePipe,
    FieldErrorComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './schedule-fields.component.html',
})
export class ScheduleFieldsComponent {
  readonly form = input.required<ScheduleFormGroup>();
  /** Prefix for every `id`, so two editors on one page keep their labels apart. */
  readonly idPrefix = input.required<string>();

  protected readonly kinds = SCHEDULE_KINDS;
  protected readonly daysOfMonth = DAYS_OF_MONTH;
  protected readonly months = MONTHS_OF_YEAR;
  protected readonly weekdays = DAYS_OF_WEEK;
  protected readonly kindKey = scheduleKindKey;
  protected readonly isoWeekday = isoWeekday;

  // A form control is not a signal, so which fields to show is read from its event stream — the
  // same way FieldErrorComponent watches a control's validity.
  private readonly changes = toSignal(
    toObservable(this.form).pipe(
      switchMap((form) => merge(of(null), form.controls.kind.valueChanges)),
    ),
  );

  protected readonly kind = computed(() => {
    this.changes();
    return this.form().controls.kind.value;
  });

  protected readonly showsDayOfMonth = computed(() => usesParameter(this.kind(), 'dayOfMonth'));
  protected readonly showsMonth = computed(() => usesParameter(this.kind(), 'month'));
  protected readonly showsDayOfWeek = computed(() => usesParameter(this.kind(), 'dayOfWeek'));
  protected readonly showsAnchorDate = computed(() => usesParameter(this.kind(), 'anchorDate'));
  /** `IRREGULAR`: nothing to parameterise, so the editor explains itself instead. */
  protected readonly showsNothing = computed(() => this.kind() === 'IRREGULAR');
}
