import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/categories/data/categories.dart';
import 'package:budget_owl/features/envelopes/data/envelopes.dart';
import 'package:budget_owl/features/envelopes/domain/envelope.dart';
import 'package:budget_owl/features/envelopes/domain/requests.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/widgets/validation.dart';
import 'package:budget_owl/shared/widgets/write_failure.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// What a saved envelope sheet reports: the notice, and the month it was saved for.
typedef EnvelopeSaved = ({String notice, String period});

/// Opens the add or edit sheet on the root navigator. Null when nothing was saved.
Future<EnvelopeSaved?> showEnvelopeSheet(
  BuildContext context, {
  required String period,
  required String currency,
  required List<Category> categories,
  Envelope? editing,
}) => Navigator.of(context, rootNavigator: true).push<EnvelopeSaved>(
  MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) =>
        EnvelopeSheet(period: period, currency: currency, categories: categories, editing: editing),
  ),
);

/// Adding an envelope or correcting one — the web's dialog.
///
/// - **The month is fixed once set**, and the sheet says so rather than hiding it: the server
///   takes no `period` on an edit, because moving an envelope would carry its entries with it.
/// - **"No category" is first, and named**: an envelope with no category is ordinary.
/// - **The amount is text**, unsigned, sent as typed (ADR-0006).
class EnvelopeSheet extends ConsumerStatefulWidget {
  const EnvelopeSheet({
    required this.period,
    required this.currency,
    required this.categories,
    this.editing,
    super.key,
  });

  final String period;
  final String currency;
  final List<Category> categories;
  final Envelope? editing;

  @override
  ConsumerState<EnvelopeSheet> createState() => _EnvelopeSheetState();
}

class _EnvelopeSheetState extends ConsumerState<EnvelopeSheet> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _amount = TextEditingController();
  late String _period;
  late String _categoryId;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    final editing = widget.editing;
    _name.text = editing?.name ?? '';
    _amount.text = editing == null ? '' : envelopeAmountText(editing);
    _period = editing?.period ?? widget.period;
    _categoryId = editing?.categoryId ?? '';
  }

  @override
  void dispose() {
    _name.dispose();
    _amount.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final form = EnvelopeForm(
      name: _name.text,
      period: _period,
      amount: _amount.text,
      categoryId: _categoryId,
    );
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final writer = ref.read(envelopesWriterProvider);
      final editing = widget.editing;
      if (editing == null) {
        await writer.create(form);
        if (mounted) Navigator.of(context).pop((notice: l.envelopes_created, period: _period));
      } else {
        final sent = await writer.update(editing, form);
        if (mounted) {
          Navigator.of(context).pop(sent ? (notice: l.envelopes_updated, period: _period) : null);
        }
      }
    } on AppError catch (error) {
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) setState(() => _error = writeFailureText(context, ref, error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final editing = widget.editing;
    final online = canWriteNow(ref);
    final error = _error;
    final choosable = pickableCategories(widget.categories);
    String trail(Category c) =>
        c.parentName == null ? c.name : l.transactions_categoryTrail(c.parentName!, c.name);
    const gap = SizedBox(height: SpaceTokens.s4);

    return Scaffold(
      appBar: AppBar(
        title: Text(editing == null ? l.envelopes_addTitle : l.envelopes_editTitle, maxLines: 2),
        leading: IconButton(
          icon: const Icon(Icons.close),
          tooltip: l.common_cancel,
          onPressed: _busy ? null : () => Navigator.of(context).pop(),
        ),
      ),
      body: SafeArea(
        child: Form(
          key: _form,
          child: ListView(
            padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
            children: [
              if (error != null) ...[NoticeBanner(text: error), gap],
              TextFormField(
                controller: _name,
                maxLength: 60,
                decoration: InputDecoration(
                  labelText: l.envelopes_name,
                  helperText: l.envelopes_nameHint,
                  helperMaxLines: 10,
                ),
                validator: (v) =>
                    Rules.required(l, v ?? '') ??
                    ((v ?? '').trim().length > 60 ? l.validation_maxLength('60') : null),
              ),
              gap,
              InputDecorator(
                decoration: InputDecoration(
                  labelText: l.envelopes_month,
                  enabled: editing == null,
                  helperText: editing == null ? null : l.envelopes_monthFixedHint,
                  helperMaxLines: 10,
                ),
                child: Row(
                  children: [
                    if (editing == null)
                      IconButton(
                        tooltip: l.envelopes_previousMonth,
                        icon: const Icon(Icons.chevron_left),
                        onPressed: () => setState(() => _period = shiftPeriod(_period, -1)),
                      ),
                    Expanded(
                      child: Text(
                        periodName(_period, context.localeName),
                        textAlign: editing == null ? TextAlign.center : TextAlign.start,
                      ),
                    ),
                    if (editing == null)
                      IconButton(
                        tooltip: l.envelopes_nextMonth,
                        icon: const Icon(Icons.chevron_right),
                        onPressed: () => setState(() => _period = shiftPeriod(_period, 1)),
                      ),
                  ],
                ),
              ),
              gap,
              TextFormField(
                controller: _amount,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                textDirection: TextDirection.ltr,
                autocorrect: false,
                enableSuggestions: false,
                decoration: InputDecoration(
                  labelText: l.envelopes_amount,
                  helperText: l.envelopes_amountHint(widget.currency),
                ),
                validator: (v) =>
                    Rules.required(l, v ?? '') ??
                    (isEnvelopeAmount(v ?? '') ? null : l.validation_positiveAmount),
              ),
              gap,
              DropdownButtonFormField<String>(
                initialValue: _categoryId,
                isExpanded: true,
                isDense: false,
                decoration: InputDecoration(labelText: l.envelopes_category),
                items: [
                  DropdownMenuItem(value: '', child: Text(l.envelopes_noCategory)),
                  for (final category in choosable)
                    DropdownMenuItem(value: category.id, child: Text(trail(category))),
                  // A category since archived stays shown on the envelope that has it.
                  if (_categoryId.isNotEmpty && choosable.every((c) => c.id != _categoryId))
                    DropdownMenuItem(value: _categoryId, child: Text(editing?.categoryName ?? '')),
                ],
                onChanged: (value) => setState(() => _categoryId = value ?? ''),
              ),
              gap,
              FilledButton(
                onPressed: _busy || !online ? null : _save,
                child: Text(_busy ? l.common_saving : l.common_save),
              ),
              const OfflineNote(),
            ],
          ),
        ),
      ),
    );
  }
}
