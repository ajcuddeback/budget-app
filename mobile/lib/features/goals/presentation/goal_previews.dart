import 'dart:async';

import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/debt/presentation/debt_formats.dart';
import 'package:budget_owl/features/goals/domain/goal.dart';
import 'package:budget_owl/features/goals/domain/requests.dart';
import 'package:budget_owl/shared/said.dart';
import 'package:budget_owl/shared/widgets/plan_widgets.dart';
import 'package:budget_owl/shared/year_month.dart';
import 'package:intl/intl.dart' show DateFormat;
import 'package:material_ui/material_ui.dart';

/// How long typing settles before the server is asked: "3500" is one question.
const Duration goalPreviewSettles = Duration(milliseconds: 350);

/// The three rules every goal preview keeps (goals.md): **a late answer is dropped** (answers to a
/// stream of questions arrive in any order), **a half-typed amount asks nothing**, and **a failed
/// preview never blocks saving** — it says so. The phone computes no projection, not even as a
/// fallback.
final class PreviewAsker extends ChangeNotifier {
  Timer? _settle;
  int _request = 0;
  bool _disposed = false;

  ProjectionFacts? facts;
  PlanShape shape = PlanShape.oneAmount;
  bool unavailable = false;

  /// Asks after typing settles.
  void settle(VoidCallback ask) {
    _settle?.cancel();
    _settle = Timer(goalPreviewSettles, ask);
  }

  /// Asks now: [request] null means there is nothing worth asking yet.
  Future<void> ask(
    State<StatefulWidget> owner,
    Future<ProjectionFacts> Function()? request,
    PlanShape plan,
  ) async {
    final mine = ++_request;
    if (request == null) {
      _show(null, unavailable: false, plan: plan);
      return;
    }
    try {
      final answer = await request();
      if (_disposed || !owner.mounted || mine != _request) return;
      _show(answer, unavailable: false, plan: plan);
    } on AppError {
      if (_disposed || !owner.mounted || mine != _request) return;
      _show(null, unavailable: true, plan: plan);
    }
  }

  void _show(ProjectionFacts? answer, {required bool unavailable, required PlanShape plan}) {
    if (_disposed) return;
    facts = answer;
    shape = plan;
    this.unavailable = unavailable;
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _settle?.cancel();
    // An answer arriving after the sheet closed belongs to nobody.
    _request++;
    super.dispose();
  }
}

/// The server's preview in words, announced as it changes, or why there is none.
class PreviewSentences extends StatelessWidget {
  const PreviewSentences({required this.asker, this.replaced, super.key});

  final PreviewAsker asker;

  /// A sentence shown instead of the preview, when the choice makes one meaningless.
  final String? replaced;

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: asker,
    builder: (context, _) {
      final f = formatsOf(context);
      final facts = asker.facts;
      final said = facts == null
          ? const <Said>[]
          : projectionSaid(
              facts,
              money: f.money,
              month: f.month,
              count: f.count,
              plan: asker.shape,
            );
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Semantics(
            liveRegion: true,
            container: true,
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (replaced != null)
                  Sentence(replaced!)
                else
                  for (final s in said)
                    Padding(
                      padding: const EdgeInsetsDirectional.only(bottom: SpaceTokens.s1),
                      child: Sentence(context.said(s)),
                    ),
              ],
            ),
          ),
          if (asker.unavailable && replaced == null)
            CardNote(context.l10n.goals_previewUnavailable),
        ],
      );
    },
  );
}

/// A month chosen from a month and a year — the web's month input. Returns the `YYYY-MM` chosen,
/// an empty string to clear it, or null when nothing changed.
Future<String?> pickMonth(
  BuildContext context, {
  required String? initial,
  required YearMonth from,
}) {
  final start = YearMonth.tryParse(initial) ?? from;
  final firstYear = start.year < from.year ? start.year : from.year;
  return showDialog<String>(
    context: context,
    builder: (dialogContext) {
      var month = start.month;
      var year = start.year;
      final l = dialogContext.l10n;
      final locale = dialogContext.localeName;
      return StatefulBuilder(
        builder: (context, setState) => AlertDialog(
          title: Text(l.goals_field_month),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DropdownButtonFormField<int>(
                initialValue: month,
                isExpanded: true,
                decoration: InputDecoration(labelText: l.bills_month),
                items: [
                  for (var m = 1; m <= 12; m++)
                    DropdownMenuItem(
                      value: m,
                      child: Text(DateFormat.MMMM(locale).format(DateTime.utc(2026, m))),
                    ),
                ],
                onChanged: (m) => setState(() => month = m ?? month),
              ),
              const SizedBox(height: SpaceTokens.s3),
              DropdownButtonFormField<int>(
                initialValue: year,
                isExpanded: true,
                items: [
                  for (var y = firstYear; y <= from.year + 30; y++)
                    DropdownMenuItem(
                      value: y,
                      child: Text(DateFormat.y(locale).format(DateTime.utc(y))),
                    ),
                ],
                onChanged: (y) => setState(() => year = y ?? year),
              ),
            ],
          ),
          actions: [
            if (initial != null)
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(''),
                child: Text(l.goals_detail_noMonth),
              ),
            TextButton(
              onPressed: () => Navigator.of(dialogContext).pop(),
              child: Text(l.common_cancel),
            ),
            FilledButton(
              onPressed: () => Navigator.of(dialogContext).pop(YearMonth(year, month).wire),
              child: Text(l.common_done),
            ),
          ],
        ),
      );
    },
  );
}
