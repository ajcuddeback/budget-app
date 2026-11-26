import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/transactions/data/transactions_repository.dart';
import 'package:budget_owl/features/transactions/domain/requests.dart';
import 'package:budget_owl/features/transactions/domain/transaction.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:decimal/decimal.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

Future<String?> showTransferSheet(
  BuildContext context, {
  required List<Account> accounts,
  Transfer? editing,
}) => Navigator.of(context).push<String>(
  MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) => TransferSheet(accounts: accounts, editing: editing),
  ),
);

/// Moving money between two of the household's own accounts (transfers.md, UI).
///
/// **Not income and not spending**: no category picker and no payee field anywhere, and the sheet
/// says why in a sentence. The same account twice is refused as it is chosen. Across currencies a
/// second figure appears — what arrived, which only the statement knows — and within one it is
/// neither shown nor sent. An edit has **no account pickers** and says that moving a transfer to
/// other accounts means deleting it and making it again; it sends only what changed.
class TransferSheet extends ConsumerStatefulWidget {
  const TransferSheet({required this.accounts, this.editing, super.key});

  final List<Account> accounts;
  final Transfer? editing;

  @override
  ConsumerState<TransferSheet> createState() => _TransferSheetState();
}

class _TransferSheetState extends ConsumerState<TransferSheet> {
  final _form = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _received = TextEditingController();
  final _note = TextEditingController();
  String? _from;
  String? _to;
  late CalendarDate _date;
  AppError? _error;
  bool _busy = false;

  List<Account> get _open => widget.accounts.where((a) => !a.archived).toList();

  @override
  void initState() {
    super.initState();
    final editing = widget.editing;
    if (editing != null) {
      _from = editing.fromAccountId;
      _to = editing.toAccountId;
      _date = editing.fromDate;
      _amount.text = magnitudeOf(editing.sentWire);
      if (editing.crossCurrency) _received.text = magnitudeOf(editing.receivedWire);
      _note.text = editing.fromNote ?? '';
    } else {
      _date = CalendarDate.of(ref.read(clockProvider)());
    }
  }

  @override
  void dispose() {
    _amount.dispose();
    _received.dispose();
    _note.dispose();
    super.dispose();
  }

  Account? _find(String? id) {
    for (final a in widget.accounts) {
      if (a.id == id) return a;
    }
    return null;
  }

  /// Whether a second figure is needed — decided from the accounts already on screen, never
  /// guessed. Two accounts that cannot both be found are not cross-currency.
  bool get _crossCurrency {
    final editing = widget.editing;
    if (editing != null) return editing.crossCurrency;
    final from = _find(_from);
    final to = _find(_to);
    return from != null && to != null && from.currency != to.currency;
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
    final amount = parseAmountInput(_amount.text);
    final received = _crossCurrency ? parseAmountInput(_received.text) : null;
    if (amount is! ValidAmount || _from == null || _to == null) return;
    final form = TransferForm(
      fromAccountId: _from!,
      toAccountId: _to!,
      date: _date,
      amount: amount,
      amountReceived: received is ValidAmount ? received : null,
      note: _note.text,
    );
    final l = context.l10n;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final writer = ref.read(transactionsWriterProvider);
      final editing = widget.editing;
      if (editing == null) {
        await writer.createTransfer(form, crossCurrency: _crossCurrency);
        if (mounted) Navigator.of(context).pop(l.transfers_created);
      } else {
        final sent = await writer.updateTransfer(editing, form);
        if (mounted) Navigator.of(context).pop(sent ? l.transfers_updated : null);
      }
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
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final editing = widget.editing;
    final online = canWriteNow(ref);
    final from = _find(_from);
    final to = _find(_to);
    final error = _error;
    const gap = SizedBox(height: SpaceTokens.s4);
    TextStyle? hint() => text.bodySmall?.copyWith(color: c.muted);

    String? amountRule(String? value) =>
        parseAmountInput(value ?? '') is ValidAmount ? null : l.validation_positiveAmount;

    return Scaffold(
      appBar: AppBar(
        title: Text(editing == null ? l.transfers_addTitle : l.transfers_editTitle),
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
              if (error != null) ...[NoticeBanner(text: context.errorMessage(error)), gap],
              Text(
                editing == null ? l.transfers_noCategoryNote : l.transfers_editBothHalves,
                style: text.bodyMedium,
              ),
              gap,
              if (editing == null) ...[
                DropdownButtonFormField<String>(
                  initialValue: _from,
                  isExpanded: true,
                  decoration: InputDecoration(labelText: l.transfers_from),
                  hint: Text(l.transfers_chooseAccount),
                  items: [
                    for (final a in _open)
                      DropdownMenuItem(value: a.id, child: Text('${a.label} (${a.currency})')),
                  ],
                  onChanged: (v) => setState(() => _from = v),
                  validator: (v) => v == null ? l.validation_required : null,
                ),
                gap,
                DropdownButtonFormField<String>(
                  initialValue: _to,
                  isExpanded: true,
                  // Refused as it is chosen, not on submit.
                  autovalidateMode: AutovalidateMode.always,
                  decoration: InputDecoration(labelText: l.transfers_to),
                  hint: Text(l.transfers_chooseAccount),
                  items: [
                    for (final a in _open)
                      DropdownMenuItem(value: a.id, child: Text('${a.label} (${a.currency})')),
                  ],
                  onChanged: (v) => setState(() => _to = v),
                  validator: (v) => v != null && v == _from ? l.validation_sameAccount : null,
                ),
              ] else ...[
                InputDecorator(
                  decoration: InputDecoration(
                    labelText: l.transfers_accountsLabel,
                    helperText: l.transfers_accountsFixedHint,
                  ),
                  child: Text(
                    l.transfers_accountsFixed(editing.fromAccountName, editing.toAccountName),
                  ),
                ),
              ],
              gap,
              TextFormField(
                controller: _amount,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                autocorrect: false,
                decoration: InputDecoration(
                  labelText: l.transfers_amount,
                  helperText: from == null
                      ? l.transfers_amountHintNoAccount
                      : l.transfers_amountHint(from.currency),
                ),
                validator: amountRule,
              ),
              // Announced when it appears: the second figure is a new question.
              Semantics(
                liveRegion: true,
                child: _crossCurrency
                    ? Padding(
                        padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s4),
                        child: TextFormField(
                          controller: _received,
                          keyboardType: const TextInputType.numberWithOptions(decimal: true),
                          autocorrect: false,
                          decoration: InputDecoration(
                            labelText: l.transfers_amountReceived,
                            helperText: l.transfers_amountReceivedHint(
                              to?.currency ?? editing?.received.currency ?? '',
                            ),
                          ),
                          validator: amountRule,
                        ),
                      )
                    : const SizedBox.shrink(),
              ),
              if (editing != null && editing.impliedRate != null) ...[
                gap,
                Text(
                  l.transfers_impliedRate(
                    editing.sent.currency,
                    _rate(editing.impliedRate!, context.localeName),
                    editing.received.currency,
                  ),
                  style: text.bodyMedium,
                ),
                Text(l.transfers_impliedRateNote, style: hint()),
              ],
              gap,
              InkWell(
                onTap: _pickDate,
                child: InputDecorator(
                  decoration: InputDecoration(
                    labelText: l.transfers_date,
                    suffixIcon: const Icon(Icons.calendar_today_outlined),
                  ),
                  child: Text(context.date(_date)),
                ),
              ),
              if (editing != null && editing.fromDate != editing.toDate)
                Padding(
                  padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
                  child: Text(
                    l.transfers_legDates(context.date(editing.fromDate), context.date(editing.toDate)),
                    style: hint(),
                  ),
                ),
              gap,
              TextFormField(
                controller: _note,
                maxLength: 500,
                decoration: InputDecoration(
                  labelText: l.transfers_note,
                  helperText: l.transfers_noteHint,
                ),
              ),
              if (editing != null && editing.fromNote != editing.toNote)
                Text(l.transfers_legNotes, style: hint()),
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

/// The implied rate for reading: the server's decimal string with the locale's separator and no
/// trailing zeros. Never through a `double` — it is a quotient of two exact figures.
String _rate(String wire, String locale) {
  final value = Decimal.tryParse(wire);
  if (value == null) return wire;
  final separator = NumberFormat.decimalPattern(locale).symbols.DECIMAL_SEP;
  return value.toString().replaceFirst('.', separator);
}
