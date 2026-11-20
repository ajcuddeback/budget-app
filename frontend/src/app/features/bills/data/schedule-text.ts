import { MessageKey, MessageParams } from '../../../core/i18n/messages';
import { Schedule, isoWeekday, scheduleSaidKey } from './schedule.models';

/**
 * The part of `I18nService` a schedule sentence needs.
 *
 * Narrowed to four methods so this stays a function of its arguments and can be tested with the
 * real service — or with any locale — rather than through a component.
 */
export interface ScheduleWords {
  t(key: MessageKey | string, params?: MessageParams): string;
  formatDate(iso: string | null | undefined): string;
  formatMonthDay(month: number, day: number): string;
  formatWeekday(isoDay: number): string;
}

/**
 * A schedule in words: "Every other Wednesday, from 5 August 2026".
 *
 * **Assembled from a translated sentence with named parameters**, never by joining fragments: a
 * language that puts the weekday last, or that needs a different word order for "every other",
 * has one string to change. The weekday and the date come from `Intl`, so neither is a word a
 * translator has to supply (ADR-0023).
 */
export function scheduleSaid(schedule: Schedule, words: ScheduleWords): string {
  const key = scheduleSaidKey(schedule.kind);
  switch (schedule.kind) {
    case 'MONTHLY':
      return words.t(key, { day: schedule.dayOfMonth ?? '' });
    case 'YEARLY':
      return words.t(key, {
        date:
          schedule.month === null || schedule.dayOfMonth === null
            ? ''
            : words.formatMonthDay(schedule.month, schedule.dayOfMonth),
      });
    case 'WEEKLY':
      return words.t(key, { weekday: weekday(schedule, words) });
    case 'FORTNIGHTLY':
      return words.t(key, {
        weekday: weekday(schedule, words),
        from: words.formatDate(schedule.anchorDate),
      });
    default:
      // IRREGULAR, and anything a newer server invents: a sentence with no parameters in it.
      return words.t(key);
  }
}

function weekday(schedule: Schedule, words: ScheduleWords): string {
  return schedule.dayOfWeek === null ? '' : words.formatWeekday(isoWeekday(schedule.dayOfWeek));
}
