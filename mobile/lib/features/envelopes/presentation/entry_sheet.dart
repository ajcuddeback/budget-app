import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/envelopes/data/envelopes.dart';
import 'package:budget_owl/features/envelopes/domain/envelope.dart';
import 'package:budget_owl/features/envelopes/domain/requests.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/widgets/validation.dart';
import 'package:budget_owl/shared/widgets/write_failure.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Opens quick add — or, with [editing], the correction of one entry — on the root navigator.
/// Returns the notice to show, or null when nothing was saved.
Future<String?> showEntrySheet(
  BuildContext context, {
  required Envelope envelope,
  EnvelopeEntry? editing,
}) => Navigator.of(context, rootNavigator: true).push<String>(
  MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) => EntrySheet(envelope: envelope, editing: editing),
  ),
);

/// **Quick add**: the action somebody takes eighty times a month, one-handed, in a queue
/// (envelopes.md, Logging an entry).
///
/// - **It opens on the amount, with the decimal keypad up**, and an amount alone is a valid entry.
///   Everything else says it is optional; nothing stands between somebody and a logged figure.
/// - **The amount is text**, never a number field: what is typed is sent as typed (ADR-0006).
/// - An entry taken from a transaction keeps the transaction's figure; the server says so.
class EntrySheet extends ConsumerStatefulWidget {
  const EntrySheet({required this.envelope, this.editing, super.key});

  final Envelope envelope;
  final EnvelopeEntry? editing;

  @override
  ConsumerState<EntrySheet> createState() => _EntrySheetState();
}

class _EntrySheetState extends ConsumerState<EntrySheet> {
  final _form = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _payee = TextEditingController();
  final _note = TextEditingController();
  late CalendarDate _date;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    final editing = widget.editing;
    if (editing == null) {
      _date = CalendarDate.of(ref.read(clockProvider)());
    } else {
      _amount.text = entryAmountText(editing);
      _payee.text = editing.payeeName ?? '';
      _note.text = editing.note ?? '';
      _date = editing.date;
    }
  }

  @override
  void dispose() {
    for (final controller in [_amount, _payee, _note]) {
      controller.dispose();
    }
    super.dispose();
  }

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _date.forPicker,
      firstDate: DateTime(1970),
      lastDate: DateTime(2100),
    );
    if (picked != null) setState(() => _date = CalendarDate.of(picked));
  }

  Future<void> _save() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final l = context.l10n;
    final form = EntryForm(
      amount: _amount.text,
      date: _date,
      payeeName: _payee.text,
      note: _note.text,
    );
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final writer = ref.read(envelopesWriterProvider);
      final editing = widget.editing;
      if (editing == null) {
        await writer.logEntry(widget.envelope, form);
        if (mounted) Navigator.of(context).pop(l.envelopes_logged);
      } else {
        final sent = await writer.updateEntry(widget.envelope, editing, form);
        if (mounted) Navigator.of(context).pop(sent ? l.envelopes_entryUpdated : null);
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
    const gap = SizedBox(height: SpaceTokens.s4);

    return Scaffold(
      appBar: AppBar(
        title: Text(
          editing == null ? l.envelopes_logTitle(widget.envelope.name) : l.envelopes_editEntryTitle,
          maxLines: 2,
        ),
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
                controller: _amount,
                autofocus: editing == null,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                textDirection: TextDirection.ltr,
                autocorrect: false,
                enableSuggestions: false,
                decoration: InputDecoration(
                  labelText: l.envelopes_amount,
                  helperText: l.envelopes_amountHint(widget.envelope.currency),
                ),
                validator: (v) =>
                    Rules.required(l, v ?? '') ??
                    (isEnvelopeAmount(v ?? '') ? null : l.validation_positiveAmount),
              ),
              gap,
              TextFormField(
                controller: _payee,
                maxLength: 80,
                autocorrect: false,
                decoration: InputDecoration(
                  labelText: l.envelopes_payee,
                  helperText: l.envelopes_optional,
                ),
              ),
              gap,
              InkWell(
                onTap: _pickDate,
                child: InputDecorator(
                  decoration: InputDecoration(
                    labelText: l.envelopes_date,
                    suffixIcon: const Icon(Icons.calendar_today_outlined),
                  ),
                  child: Text(context.date(_date)),
                ),
              ),
              gap,
              TextFormField(
                controller: _note,
                maxLength: 500,
                decoration: InputDecoration(
                  labelText: l.envelopes_note,
                  helperText: l.envelopes_optional,
                ),
              ),
              gap,
              FilledButton(
                onPressed: _busy || !online ? null : _save,
                child: Text(_busy ? l.common_saving : l.envelopes_logIt),
              ),
              const OfflineNote(),
            ],
          ),
        ),
      ),
    );
  }
}
