import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/bills/domain/bill.dart';
import 'package:budget_owl/features/bills/domain/requests.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

/// "Every other Wednesday, counting from Jul 31, 2026" — a schedule in words (the web's
/// `scheduleSaid`).
///
/// One translated sentence with named parameters, never fragments joined: a language that puts the
/// weekday last has one string to change. The weekday, the month and the date come from intl.
String scheduleSaid(AppLocalizations l, Schedule schedule, String locale) {
  final numbers = NumberFormat.decimalPattern(locale);
  switch (schedule.kind) {
    case ScheduleKind.monthly:
      final day = schedule.dayOfMonth;
      return l.bills_said_MONTHLY(day == null ? '' : numbers.format(day));
    case ScheduleKind.yearly:
      final month = schedule.month;
      final day = schedule.dayOfMonth;
      return l.bills_said_YEARLY(
        month == null || day == null ? '' : monthDayName(month, day, locale),
      );
    case ScheduleKind.weekly:
      return l.bills_said_WEEKLY(_weekday(schedule.dayOfWeek, locale));
    case ScheduleKind.fortnightly:
      final anchor = schedule.anchorDate;
      return l.bills_said_FORTNIGHTLY(
        _weekday(schedule.dayOfWeek, locale),
        anchor == null ? '' : anchor.medium(locale),
      );
    case ScheduleKind.irregular:
      return l.bills_said_IRREGULAR;
    case ScheduleKind.unknown:
      return l.bills_kind_unknown;
  }
}

String _weekday(DayOfWeek? day, String locale) => day == null ? '' : weekdayName(day, locale);

/// "Wednesday", from intl: 1 January 2024 was a Monday, so the ISO number is the day of the month.
String weekdayName(DayOfWeek day, String locale) =>
    DateFormat.EEEE(locale).format(DateTime.utc(2024, 1, day.iso));

/// "August", from intl.
String monthName(int month, String locale) =>
    DateFormat.MMMM(locale).format(DateTime.utc(2026, month));

/// "March 1", clamped as the server clamps it: the 31st in a 30-day month is its last day.
String monthDayName(int month, int day, String locale) {
  final last = DateTime.utc(2026, month + 1, 0).day;
  return DateFormat.MMMMd(locale).format(DateTime.utc(2026, month, day > last ? last : day));
}

/// The one schedule editor bills and income share: a kind, and **only** the parameters that kind
/// uses. Changing the kind clears the parameters that stopped applying ([ScheduleForm.withKind]),
/// because sending one is a `400`. Its heading is the caller's: "When it is due" for a bill, "When
/// it arrives" for income.
class ScheduleFields extends StatelessWidget {
  const ScheduleFields({
    required this.heading,
    required this.value,
    required this.onChanged,
    super.key,
  });

  final String heading;
  final ScheduleForm value;
  final ValueChanged<ScheduleForm> onChanged;

  Future<void> _pickAnchor(BuildContext context) async {
    final picked = await showDatePicker(
      context: context,
      initialDate: (value.anchorDate ?? CalendarDate.of(DateTime.now())).forPicker,
      firstDate: DateTime(1970),
      lastDate: DateTime(2100),
    );
    if (picked != null) onChanged(value.copyWith(anchorDate: () => CalendarDate.of(picked)));
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final locale = context.localeName;
    final uses = value.kind.parameters;
    String? required<T>(T? chosen) => chosen == null ? l.validation_required : null;
    const gap = SizedBox(height: SpaceTokens.s4);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Semantics(
          header: true,
          child: Text(heading, style: Theme.of(context).textTheme.titleSmall),
        ),
        const SizedBox(height: SpaceTokens.s2),
        DropdownButtonFormField<ScheduleKind>(
          initialValue: value.kind == ScheduleKind.unknown ? null : value.kind,
          isExpanded: true,
          decoration: InputDecoration(labelText: l.bills_howOften),
          items: [
            for (final kind in ScheduleKind.choosable)
              DropdownMenuItem(value: kind, child: Text(context.message(kind.messageKey))),
          ],
          onChanged: (kind) {
            if (kind != null) onChanged(value.withKind(kind));
          },
          validator: required,
        ),
        if (uses.contains(ScheduleParameter.month)) ...[
          gap,
          DropdownButtonFormField<int>(
            key: ValueKey('month-${value.kind.name}'),
            initialValue: value.month,
            isExpanded: true,
            hint: Text(l.bills_choose),
            decoration: InputDecoration(labelText: l.bills_month),
            items: [
              for (var m = 1; m <= 12; m++)
                DropdownMenuItem(value: m, child: Text(monthName(m, locale))),
            ],
            onChanged: (m) => onChanged(value.copyWith(month: () => m)),
            validator: required,
          ),
        ],
        if (uses.contains(ScheduleParameter.dayOfMonth)) ...[
          gap,
          DropdownButtonFormField<int>(
            key: ValueKey('day-${value.kind.name}'),
            initialValue: value.dayOfMonth,
            isExpanded: true,
            hint: Text(l.bills_choose),
            decoration: InputDecoration(
              labelText: l.bills_dayOfMonth,
              helperText: l.bills_dayOfMonthHint,
              helperMaxLines: 3,
            ),
            items: [
              for (var d = 1; d <= 31; d++)
                DropdownMenuItem(
                  value: d,
                  child: Text(NumberFormat.decimalPattern(locale).format(d)),
                ),
            ],
            onChanged: (d) => onChanged(value.copyWith(dayOfMonth: () => d)),
            validator: required,
          ),
        ],
        if (uses.contains(ScheduleParameter.dayOfWeek)) ...[
          gap,
          DropdownButtonFormField<DayOfWeek>(
            key: ValueKey('weekday-${value.kind.name}'),
            initialValue: value.dayOfWeek,
            isExpanded: true,
            hint: Text(l.bills_choose),
            decoration: InputDecoration(labelText: l.bills_dayOfWeek),
            items: [
              for (final day in DayOfWeek.values)
                DropdownMenuItem(value: day, child: Text(weekdayName(day, locale))),
            ],
            onChanged: (d) => onChanged(value.copyWith(dayOfWeek: () => d)),
            validator: required,
          ),
        ],
        if (uses.contains(ScheduleParameter.anchorDate)) ...[
          gap,
          FormField<CalendarDate>(
            key: ValueKey('anchor-${value.anchorDate?.iso}'),
            initialValue: value.anchorDate,
            validator: (_) => required(value.anchorDate),
            builder: (field) => InkWell(
              onTap: () => _pickAnchor(context),
              child: InputDecorator(
                decoration: InputDecoration(
                  labelText: l.bills_anchorDate,
                  helperText: l.bills_anchorDateHint,
                  helperMaxLines: 3,
                  errorText: field.errorText,
                  suffixIcon: const Icon(Icons.calendar_today_outlined),
                ),
                child: Text(
                  value.anchorDate == null ? l.bills_choose : context.date(value.anchorDate!),
                ),
              ),
            ),
          ),
        ],
        if (uses.isEmpty) ...[
          const SizedBox(height: SpaceTokens.s2),
          Text(l.bills_irregularHint, style: Theme.of(context).textTheme.bodySmall),
        ],
      ],
    );
  }
}
