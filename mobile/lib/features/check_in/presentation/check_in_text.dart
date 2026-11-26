import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/features/check_in/domain/check_in.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

/// Every sentence the check-in shows, made here once from the web's own keys, as
/// `check-in-text.ts` makes them (and tested beside it).
///
/// **Nothing here does money arithmetic**: an amount is inspected for its sign, compared exactly,
/// or shown as the server sent it. **A payee is never put inside a sentence**: it arrives from a
/// bank file and is shown in its own text beside the sentence (file-import.md, the bidi control).
final class CheckInWords {
  CheckInWords(BuildContext context) : l = context.l10n, locale = context.localeName;

  CheckInWords.of(this.l, this.locale);

  final AppLocalizations l;
  final String locale;

  String money(Money value) => formatMoney(value, locale);

  /// The same amount without its sign: a charge reads "$126", not "−$126".
  String magnitude(Money value) => money(Money(value.amount.abs(), value.currency));

  String date(CalendarDate value) => value.medium(locale);

  String count(int value) => NumberFormat.decimalPattern(locale).format(value);

  /// A moment the server recorded, as the date it fell on here.
  String instant(DateTime at) => DateFormat.yMMMd(locale).format(at.toLocal());

  /// `2027-04` → "Apr 2027".
  String month(String period) {
    final match = RegExp(r'^(\d{4})-(\d{2})$').firstMatch(period);
    if (match == null) return period;
    return DateFormat.yMMM(locale)
        .format(DateTime.utc(int.parse(match.group(1)!), int.parse(match.group(2)!)));
  }

  // ------------------------------------------------------------------------------ the week

  String status(CheckInStatus status) => switch (status) {
    CheckInStatus.notStarted => l.checkIn_statusChip_NOT_STARTED,
    CheckInStatus.draft => l.checkIn_statusChip_DRAFT,
    CheckInStatus.completed => l.checkIn_statusChip_COMPLETED,
  };

  String stepName(StepName step) => switch (step) {
    StepName.whatsNew => l.checkIn_step_WHATS_NEW,
    StepName.categorize => l.checkIn_step_CATEGORIZE,
    StepName.bills => l.checkIn_step_BILLS,
    StepName.nextWeek => l.checkIn_step_NEXT_WEEK,
  };

  /// What a screen's footer says comes next: "Next: categorize".
  String nextStep(StepName step) => switch (step) {
    StepName.whatsNew => l.checkIn_nextStep_WHATS_NEW,
    StepName.categorize => l.checkIn_nextStep_CATEGORIZE,
    StepName.bills => l.checkIn_nextStep_BILLS,
    StepName.nextWeek => l.checkIn_nextStep_NEXT_WEEK,
  };

  /// "Both handled", "1 waiting on you", "Nothing to answer".
  String stepStatus(CheckInStep step) {
    if (step.questions == 0) return l.checkIn_status_nothingToAnswer;
    if (step.answeredCount >= step.questions) {
      return step.questions == 2 ? l.checkIn_status_bothHandled : l.checkIn_status_allHandled;
    }
    final waiting = step.questions - step.answeredCount;
    return waiting == 1 ? l.checkIn_status_waitingOne : l.checkIn_status_waiting(count(waiting));
  }

  /// Each screen's own heading, a message with parameters (the canvas's four screens).
  String heading(CheckInStep step) => switch (step.step) {
    StepName.whatsNew =>
      step.questions == 0
          ? l.checkIn_whatsNew_headingQuiet
          : step.questions == 1
          ? l.checkIn_whatsNew_headingOne
          : l.checkIn_whatsNew_heading(count(step.questions)),
    StepName.categorize =>
      step.questions == 0
          ? l.checkIn_categorize_headingPlaced
          : step.questions == 1
          ? l.checkIn_categorize_headingOne
          : l.checkIn_categorize_heading(count(step.questions)),
    StepName.bills => l.checkIn_bills_heading,
    StepName.nextWeek => weekResult(step.figures),
  };

  /// "You came in $63 under", "You went $40 over", "Right on target", or a first week's line.
  String weekResult(Figures? figures) {
    final surplus = figures?.surplus;
    if (surplus == null) return l.checkIn_next_headingFirst;
    if (surplus.amount.sign > 0) return l.checkIn_next_headingUnder(money(surplus));
    if (surplus.amount.sign < 0) return l.checkIn_next_headingOver(magnitude(surplus));
    return l.checkIn_next_headingOnTarget;
  }

  String choiceName(AnswerChoice choice) => switch (choice) {
    AnswerChoice.markPaid => l.checkIn_choiceName_MARK_PAID,
    AnswerChoice.skip => l.checkIn_choiceName_SKIP,
    AnswerChoice.prepaid => l.checkIn_choiceName_PREPAID,
    AnswerChoice.category => l.checkIn_choiceName_CATEGORY,
    AnswerChoice.attach => l.checkIn_choiceName_ATTACH,
    AnswerChoice.notABill => l.checkIn_choiceName_NOT_A_BILL,
    AnswerChoice.leave => l.checkIn_choiceName_LEAVE,
    AnswerChoice.debt => l.checkIn_choiceName_DEBT,
    AnswerChoice.goal => l.checkIn_choiceName_GOAL,
    AnswerChoice.rollOver => l.checkIn_choiceName_ROLL_OVER,
  };

  // ----------------------------------------------------------------------------------- items

  /// "Chase •4412", or the name alone when the account has no digits recorded.
  String? account(String? name, String? lastFour) {
    if (name == null) return null;
    return lastFour == null ? l.checkIn_accountNoDigits(name) : l.checkIn_account(name, lastFour);
  }

  /// "Aug 18, 2026 · Chase •4412".
  String itemLine(Item item) => [
    if (item.date != null) date(item.date!),
    ?account(item.accountName, item.accountLastFour),
  ].join(' · ');

  /// Step 1's income line, information only: landed with its comparison, or not seen yet.
  List<String> income(IncomeLine line) {
    if (!line.landed || line.actual == null || line.date == null) {
      return [l.checkIn_income_notYet(line.name, date(line.expectedDate))];
    }
    return [
      l.checkIn_income_landed(line.name, date(line.date!), money(line.actual!)),
      if (line.asExpected)
        l.checkIn_income_asExpected
      else
        l.checkIn_income_expected(money(line.expected)),
    ];
  }

  /// Why an item is being asked about, as one sentence.
  String? reason(Item item) {
    switch (item.kind) {
      case ItemKind.unusualCharge:
        final usual = item.usual;
        if (usual == null) return null;
        return item.billName == null
            ? l.checkIn_odd_usually(money(usual))
            : l.checkIn_odd_usuallyBill(item.billName!, money(usual));
      case ItemKind.overdueBill:
        return l.checkIn_overdue_reason(
          item.billName ?? '',
          item.dueDate == null ? '' : date(item.dueDate!),
          item.expected == null ? '' : money(item.expected!),
        );
      case ItemKind.billCharge:
        return _chargeReason(item);
      case ItemKind.categorize || ItemKind.allocation:
        return null;
    }
  }

  String? _chargeReason(Item item) {
    final amount = item.amount == null ? '' : magnitude(item.amount!);
    switch (item.reason) {
      case ChargeReason.ruleMatch:
        return l.checkIn_charge_matched(item.billName ?? '');
      case ChargeReason.ruleOutsideTolerance:
        return l.checkIn_charge_outside(
          amount,
          item.billName ?? '',
          item.expected == null ? '' : money(item.expected!),
        );
      case ChargeReason.recurring:
        final where = account(item.accountName, item.accountLastFour) ?? '';
        return item.suggestedBillId != null && item.billName != null
            ? l.checkIn_charge_recurring(amount, where, item.billName!)
            : l.checkIn_charge_recurringPick(amount, where);
      case null:
        return null;
    }
  }

  /// The prepaid offer, or why there is none.
  String prepaid(Item item) {
    final multiple = item.multiple;
    final bill = item.billName;
    if (multiple == null || bill == null) return l.checkIn_odd_noOffer;
    return (item.skips ?? 0) == 1
        ? l.checkIn_odd_prepaidOne(count(multiple), bill)
        : l.checkIn_odd_prepaid(count(multiple), bill, count(item.skips ?? 0));
  }

  /// "Paid", "Skipped", "Overdue" or "Due Aug 24, 2026" — never a count of days, which would
  /// need the phone's clock.
  String occurrence(OccurrenceLine line) => switch (line.status) {
    OccurrenceStatus.paid => l.checkIn_bills_paid,
    OccurrenceStatus.skipped => l.checkIn_bills_skipped,
    OccurrenceStatus.due =>
      line.overdue ? l.checkIn_bills_overdue : l.checkIn_bills_due(date(line.dueDate)),
  };

  /// "Replaces your rule filing it under Shopping", only when the rule would change.
  String? replacesRule(Item item) {
    if (item.answer?.remember != true) return null;
    final rule = item.ruleCategoryId;
    if (rule == null || item.ruleCategoryName == null) return null;
    if (item.chosenCategoryId == rule) return null;
    return l.checkIn_categorize_replacesRule(item.ruleCategoryName!);
  }

  /// Who gave an answer, for a household where two people answer one draft.
  String? answeredBy(Item item) {
    final answer = item.answer;
    if (answer == null || answer.byRule) return null;
    final name = answer.answeredBy?.name;
    return name == null ? null : l.checkIn_answeredBy(name);
  }

  String? attachedTo(Item item) {
    final answer = item.answer;
    if (answer?.choice != AnswerChoice.attach || answer?.billName == null) return null;
    if (answer!.dueDate == null) return null;
    return l.checkIn_charge_attachedTo(answer.billName!, date(answer.dueDate!));
  }

  // ---------------------------------------------------------------------------------- step 4

  String choiceLabel(Choice choice) => switch (choice.choice) {
    AnswerChoice.debt => l.checkIn_choice_debt(choice.debtName ?? ''),
    AnswerChoice.goal => l.checkIn_choice_goal(choice.goalName ?? ''),
    _ => l.checkIn_choice_rollOverLabel,
  };

  /// The lines under each choice: what it does, from the server's own preview figures.
  List<String> choiceEffect(Choice choice, Figures figures) {
    final surplus = figures.surplus == null ? '' : money(figures.surplus!);
    switch (choice.choice) {
      case AnswerChoice.debt:
        final debt = choice.debtName ?? '';
        final now = choice.payoffMonthNow;
        final withIt = choice.payoffMonthWith;
        final moved = now != null && withIt != null
            ? now == withIt
                  ? l.checkIn_choice_debtSameMonth(debt)
                  : l.checkIn_choice_debtMoves(debt, month(now), month(withIt))
            : l.checkIn_choice_debtNoDate(debt);
        return [moved, l.checkIn_choice_debtNeverMoves(surplus, debt)];
      case AnswerChoice.goal:
        final goal = choice.goalName ?? '';
        return [
          l.checkIn_choice_goalMovesPhone(
            goal,
            choice.savedNow == null ? '' : money(choice.savedNow!),
            choice.savedWith == null ? '' : money(choice.savedWith!),
            choice.target == null ? '' : money(choice.target!),
          ),
          l.checkIn_choice_goalNeverMoves(surplus, goal),
        ];
      case AnswerChoice.rollOver:
        return [
          l.checkIn_choice_rollOver(figures.rolledOver == null ? '' : money(figures.rolledOver!)),
        ];
      case _:
        return const [];
    }
  }

  String? targetLine(Figures? figures) {
    if (figures == null) return null;
    final target = figures.weekTarget;
    return target == null
        ? l.checkIn_next_noTarget
        : l.checkIn_next_target(money(target), money(figures.weekSpending));
  }

  /// "Spent $487 so far this week".
  String spent(Money spending, {required bool soFar}) =>
      soFar ? l.checkIn_spentSoFar(money(spending)) : l.checkIn_spent(money(spending));

  /// "That's after Car payment on Aug 24, …, which it already counts." An explanation only.
  String? dueNextWeek(List<DueLine> lines) {
    if (lines.isEmpty) return null;
    final parts = [
      for (final line in lines) l.checkIn_next_dueOn(line.billName, date(line.dueDate)),
    ];
    return l.checkIn_next_dueNextWeek(parts.join(', '));
  }

  /// Shown only for a run of two or more; names the months only when both exist and differ.
  String? streak(Streak? streak) {
    if (streak == null || streak.weeks < 2) return null;
    final from = streak.debtFreeFrom;
    final to = streak.debtFreeTo;
    if (from != null && to != null && from != to) {
      return l.checkIn_next_streakMoved(count(streak.weeks), month(from), month(to));
    }
    return l.checkIn_next_streak(count(streak.weeks));
  }

  // ----------------------------------------------------------------------------------- stale

  String stale(String reason) => switch (reason) {
    'subject-changed' => l.checkIn_stale_subject_changed,
    'subject-gone' => l.checkIn_stale_subject_gone,
    'target-archived' => l.checkIn_stale_target_archived,
    'already-recorded' => l.checkIn_stale_already_recorded,
    'bill-inactive' => l.checkIn_stale_bill_inactive,
    'transaction-attached-elsewhere' => l.checkIn_stale_transaction_attached_elsewhere,
    'goal-archived' => l.checkIn_stale_goal_archived,
    'debt-paid-off' => l.checkIn_stale_debt_paid_off,
    'limit-reached' => l.checkIn_stale_limit_reached,
    'surplus-changed' => l.checkIn_stale_surplus_changed,
    'basis-changed' => l.checkIn_stale_basis_changed,
    _ => l.checkIn_stale_unknown,
  };

  // --------------------------------------------------------------------------------- summary

  /// What a completed check-in wrote, each with where it lives: *"Filed 3 charges. Marked Internet
  /// paid and remembered the mapping. Recorded $63 to Emergency fund. Next week: $480."*
  List<SummaryLine> summary(Week week) {
    final lines = <SummaryLine>[];
    final filed = week.outcome
        .where((o) => o.kind == ItemKind.categorize && o.choice == AnswerChoice.category)
        .toList();
    if (filed.isNotEmpty) {
      lines.add(
        SummaryLine(
          filed.length == 1
              ? l.checkIn_summary_filedOne
              : l.checkIn_summary_filed(count(filed.length)),
          '/transactions',
        ),
      );
      final byRule = filed.where((o) => o.byRule).length;
      if (byRule > 0) lines.add(SummaryLine(l.checkIn_summary_byRule(count(byRule)), null));
      final remembered = filed.where((o) => o.remember).length;
      if (remembered > 0) {
        lines.add(
          SummaryLine(
            remembered == 1
                ? l.checkIn_summary_rememberedOne
                : l.checkIn_summary_remembered(count(remembered)),
            '/more/categories',
          ),
        );
      }
    }
    for (final outcome in week.outcome) {
      final bill = outcome.billName ?? '';
      final amount = outcome.amount == null ? '' : money(outcome.amount!);
      final line = switch (outcome.choice) {
        AnswerChoice.markPaid => SummaryLine(l.checkIn_summary_markedPaid(bill), '/bills'),
        AnswerChoice.skip => SummaryLine(l.checkIn_summary_skipped(bill), '/bills'),
        AnswerChoice.prepaid => SummaryLine(l.checkIn_summary_prepaid(bill), '/bills'),
        AnswerChoice.attach => SummaryLine(
          outcome.remember
              ? l.checkIn_summary_attachedRemembered(bill)
              : l.checkIn_summary_attached(bill),
          '/bills',
        ),
        AnswerChoice.goal => SummaryLine(
          l.checkIn_summary_goal(amount, outcome.goalName ?? ''),
          '/more/goals',
        ),
        AnswerChoice.debt => SummaryLine(
          l.checkIn_summary_debt(amount, outcome.debtName ?? ''),
          '/more/debt-plan',
        ),
        AnswerChoice.rollOver => SummaryLine(l.checkIn_summary_rolledOver(amount), null),
        _ => null,
      };
      if (line != null) lines.add(line);
    }
    final snapshot = week.snapshot;
    if (snapshot != null) {
      lines.add(SummaryLine(l.checkIn_summary_nextWeek(money(snapshot.nextWeekTarget)), null));
    }
    return lines;
  }
}

/// A summary sentence and the tab or More entry where the record it describes lives.
final class SummaryLine {
  const SummaryLine(this.text, this.route);

  final String text;
  final String? route;
}
