import { FormControl, FormGroup, NonNullableFormBuilder, Validators } from '@angular/forms';
import {
  ScheduleFormValue,
  clearUnusedParameters,
  newScheduleForm,
  scheduleFormFrom,
} from './bill-patch';
import { Schedule, ScheduleKind, ScheduleParameter, usesParameter } from './schedule.models';

/**
 * The typed group the schedule editor drives.
 *
 * Spelled out rather than inferred, so the editor can take one as an `input()` without reaching
 * for `FormGroup<any>` — forms are this app's untrusted-input edge and are typed throughout.
 */
export type ScheduleFormGroup = FormGroup<{
  kind: FormControl<ScheduleKind>;
  dayOfMonth: FormControl<string>;
  month: FormControl<string>;
  dayOfWeek: FormControl<string>;
  anchorDate: FormControl<string>;
}>;

const PARAMETERS: readonly ScheduleParameter[] = ['dayOfMonth', 'month', 'dayOfWeek', 'anchorDate'];

/** A schedule group for something new. Monthly, which is what most bills and most wages are. */
export function buildScheduleGroup(fb: NonNullableFormBuilder): ScheduleFormGroup {
  const group: ScheduleFormGroup = fb.group({
    kind: fb.control<ScheduleKind>('MONTHLY'),
    dayOfMonth: fb.control(''),
    month: fb.control(''),
    dayOfWeek: fb.control(''),
    anchorDate: fb.control(''),
  });
  applyScheduleKind(group, 'MONTHLY');
  return group;
}

/**
 * Points a group at a kind: **clears the parameters that stopped applying** and requires the ones
 * that now apply.
 *
 * Both halves matter. Sending a parameter a kind has no use for is a `400` — the server refuses
 * it rather than ignoring it, because ignoring it loses an edit — so a form that merely *hid* the
 * day of the month after a switch to weekly would be refused on save, with nothing on screen to
 * explain it. And a missing one is refused too, so "a weekly schedule needs a weekday" is a
 * required field here rather than a server round trip.
 */
export function applyScheduleKind(group: ScheduleFormGroup, kind: ScheduleKind): void {
  group.patchValue(clearUnusedParameters(group.getRawValue(), kind), { emitEvent: false });
  for (const parameter of PARAMETERS) {
    const control = group.controls[parameter];
    control.setValidators(usesParameter(kind, parameter) ? [Validators.required] : []);
    control.updateValueAndValidity({ emitEvent: false });
  }
}

/**
 * Fills a group from the schedule the server reported, for an edit.
 *
 * The reset **emits**, deliberately: the editor works out which fields to show from the kind
 * control's own event stream, and a silent fill would open an edit of a weekly bill showing a day
 * of the month.
 */
export function resetScheduleGroup(group: ScheduleFormGroup, value: ScheduleFormValue): void {
  group.reset(value);
  applyScheduleKind(group, value.kind);
}

/** The schedule of a thing being edited, or the default for a new one. */
export function scheduleValueOf(schedule: Schedule | null): ScheduleFormValue {
  return schedule === null ? newScheduleForm() : scheduleFormFrom(schedule);
}
