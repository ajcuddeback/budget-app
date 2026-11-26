import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/transactions/data/transactions_repository.dart';
import 'package:budget_owl/features/transactions/domain/transaction.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

Future<String?> showPairSheet(
  BuildContext context, {
  required Transaction row,
  required List<Transaction> ledger,
}) => Navigator.of(context, rootNavigator: true).push<String>(
  MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) => PairSheet(row: row, ledger: ledger),
  ),
);

/// "This is a transfer…": choose the other half from the ledger (transfers.md, rule 12).
///
/// **Nothing is suggested or preselected.** The candidates are the ledger's rows in other accounts
/// that are not already legs, in ledger order, and the sheet says — before the button — that both
/// rows lose their category and payee and stop counting as income or spending. Both versions are
/// sent, so an edit elsewhere is a `409` rather than a silent overwrite.
class PairSheet extends ConsumerStatefulWidget {
  const PairSheet({required this.row, required this.ledger, super.key});

  final Transaction row;
  final List<Transaction> ledger;

  @override
  ConsumerState<PairSheet> createState() => _PairSheetState();
}

class _PairSheetState extends ConsumerState<PairSheet> {
  Transaction? _chosen;
  AppError? _error;
  bool _busy = false;

  List<Transaction> get _candidates =>
      widget.ledger.where((t) => t.accountId != widget.row.accountId && !t.transfer).toList();

  Future<void> _pair() async {
    final other = _chosen;
    if (other == null) return;
    final l = context.l10n;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(transactionsWriterProvider).pair(widget.row, other);
      if (mounted) Navigator.of(context).pop(l.pairing_paired);
    } on AppError catch (error) {
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final online = canWriteNow(ref);
    final row = widget.row;
    final candidates = _candidates;
    final error = _error;
    String describe(Transaction t) =>
        l.pairing_candidate(context.date(t.date), t.accountName, context.money(t.amount));

    return Scaffold(
      appBar: AppBar(
        title: Text(l.pairing_title, maxLines: 2),
        leading: IconButton(
          icon: const Icon(Icons.close),
          tooltip: l.common_cancel,
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
        ),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
          children: [
            Text(l.pairing_intro, style: text.bodyMedium),
            const SizedBox(height: SpaceTokens.s3),
            Text(l.pairing_thisRow, style: text.labelLarge),
            Text(describe(row), style: text.bodyMedium),
            const SizedBox(height: SpaceTokens.s3),
            Text(l.pairing_candidatesLabel, style: text.labelLarge),
            if (candidates.isEmpty) Text(l.pairing_none, style: text.bodyMedium),
            RadioGroup<String>(
              groupValue: _chosen?.id,
              onChanged: (id) =>
                  setState(() => _chosen = candidates.where((t) => t.id == id).firstOrNull),
              child: Column(
                children: [
                  for (final t in candidates)
                    RadioListTile<String>(
                      value: t.id,
                      title: Text(describe(t)),
                      subtitle: t.payeeName == null ? null : Text(t.payeeName!),
                    ),
                ],
              ),
            ),
            const SizedBox(height: SpaceTokens.s3),
            NoticeBanner(text: l.pairing_loss),
            if (error != null) ...[
              const SizedBox(height: SpaceTokens.s3),
              NoticeBanner(text: context.errorMessage(error)),
            ],
            const SizedBox(height: SpaceTokens.s4),
            FilledButton(
              onPressed: _busy || !online || _chosen == null ? null : _pair,
              child: Text(l.pairing_confirm),
            ),
            if (_chosen == null)
              Padding(
                padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
                child: Text(l.pairing_chooseFirst, style: text.bodySmall),
              ),
            const OfflineNote(),
          ],
        ),
      ),
    );
  }
}
