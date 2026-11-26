import 'dart:async';

import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/money/money.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/categories/data/categories.dart';
import 'package:budget_owl/features/transactions/data/transactions_repository.dart';
import 'package:budget_owl/features/transactions/domain/requests.dart';
import 'package:budget_owl/features/transactions/domain/transaction.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/widgets/write_failure.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Opens the entry sheet: [editing] null records a new transaction.
///
/// Returns the notice to show when something was saved, or null when nothing was.
Future<String?> showTransactionSheet(
  BuildContext context, {
  required List<Account> accounts,
  required List<Category> categories,
  Transaction? editing,
  String? defaultAccountId,
}) => Navigator.of(context, rootNavigator: true).push<String>(
  MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) => TransactionSheet(
      accounts: accounts,
      categories: categories,
      editing: editing,
      defaultAccountId: defaultAccountId,
    ),
  ),
);

/// Recording or correcting one transaction (transactions.md, UI).
///
/// - **The amount is typed unsigned and the direction is a separate choice**, "Money out" first
///   because it is the common case. The field is text with a decimal keyboard, never a number
///   field: what is typed is sent as typed, as a string (ADR-0006).
/// - **"Needs a category" is the first category**, and a choice — recording is never gated on
///   tidying up.
/// - **An edit cannot move the transaction to another account**, and says so instead of hiding it.
/// - **An edit sends only what changed**; an untouched sheet sends nothing.
/// - Archived accounts are not offered for a new entry, and archived categories not at all.
class TransactionSheet extends ConsumerStatefulWidget {
  const TransactionSheet({
    required this.accounts,
    required this.categories,
    this.editing,
    this.defaultAccountId,
    super.key,
  });

  final List<Account> accounts;
  final List<Category> categories;
  final Transaction? editing;
  final String? defaultAccountId;

  @override
  ConsumerState<TransactionSheet> createState() => _TransactionSheetState();
}

class _TransactionSheetState extends ConsumerState<TransactionSheet> {
  final _form = GlobalKey<FormState>();
  final _amount = TextEditingController();
  final _payee = TextEditingController();
  final _note = TextEditingController();
  late String? _accountId;
  late CalendarDate _date;
  late Direction _direction;
  String? _categoryId;
  TransactionStatus _status = TransactionStatus.cleared;
  List<String> _suggestions = const [];
  Timer? _debounce;
  String? _error;
  bool _busy = false;

  Transaction? get _editing => widget.editing;

  List<Account> get _openAccounts => widget.accounts.where((a) => !a.archived).toList();

  @override
  void initState() {
    super.initState();
    final editing = _editing;
    if (editing != null) {
      final form = TransactionForm.of(editing);
      _accountId = form.accountId;
      _date = form.date;
      _direction = form.direction;
      _amount.text = form.amount.text;
      _payee.text = form.payeeName;
      _categoryId = form.categoryId;
      _note.text = form.note;
      _status = form.status;
    } else {
      final open = _openAccounts;
      final preferred = widget.defaultAccountId;
      _accountId = open.any((a) => a.id == preferred)
          ? preferred
          : (open.isEmpty ? null : open.first.id);
      _date = CalendarDate.of(ref.read(clockProvider)());
      _direction = Direction.out;
    }
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _amount.dispose();
    _payee.dispose();
    _note.dispose();
    super.dispose();
  }

  Account? get _account {
    for (final a in widget.accounts) {
      if (a.id == _accountId) return a;
    }
    return null;
  }

  void _payeeTyped(String typed) {
    _debounce?.cancel();
    if (typed.trim().length < 2) {
      setState(() => _suggestions = const []);
      return;
    }
    _debounce = Timer(const Duration(milliseconds: 350), () async {
      final found = await ref.read(transactionsWriterProvider).suggestPayees(typed.trim());
      if (mounted) {
        setState(() => _suggestions = found.where((s) => s != _payee.text.trim()).take(5).toList());
      }
    });
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
    final accountId = _accountId;
    final amount = parseAmountInput(_amount.text, allowSign: true);
    if (accountId == null || amount is! ValidAmount) return;
    final form = TransactionForm(
      accountId: accountId,
      date: _date,
      direction: _amount.text.trim().startsWith('-') ? Direction.out : _direction,
      amount: amount,
      payeeName: _payee.text,
      categoryId: _categoryId,
      note: _note.text,
      status: _status,
    );
    final l = context.l10n;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final writer = ref.read(transactionsWriterProvider);
      final editing = _editing;
      if (editing == null) {
        await writer.create(form);
        if (mounted) Navigator.of(context).pop(l.transactions_created);
      } else {
        final sent = await writer.update(editing, form);
        if (mounted) Navigator.of(context).pop(sent ? l.transactions_updated : null);
      }
    } on AppError catch (error) {
      // A 409 is shown, and the ledger behind the sheet re-reads (mobile-app.md, Writes).
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) setState(() => _error = writeFailureText(context, ref, error));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final editing = _editing;
    final online = canWriteNow(ref);
    final account = _account;
    final choosable = pickableCategories(widget.categories);
    final error = _error;
    const gap = SizedBox(height: SpaceTokens.s4);

    return Scaffold(
      appBar: AppBar(
        title: Text(
          editing == null ? l.transactions_addTitle : l.transactions_editTitle,
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
              if (editing == null)
                DropdownButtonFormField<String>(
                  initialValue: _accountId,
                  isExpanded: true,
                  decoration: InputDecoration(labelText: l.transactions_account),
                  items: [
                    for (final a in _openAccounts)
                      DropdownMenuItem(value: a.id, child: Text('${a.label} (${a.currency})')),
                  ],
                  onChanged: (value) => setState(() => _accountId = value),
                  validator: (value) => value == null ? l.validation_required : null,
                )
              else
                InputDecorator(
                  decoration: InputDecoration(
                    labelText: l.transactions_account,
                    helperText: l.transactions_accountFixedHint,
                  ),
                  child: Text(account?.label ?? editing.accountName),
                ),
              gap,
              InkWell(
                onTap: _pickDate,
                child: InputDecorator(
                  decoration: InputDecoration(
                    labelText: l.transactions_date,
                    suffixIcon: const Icon(Icons.calendar_today_outlined),
                  ),
                  child: Text(context.date(_date)),
                ),
              ),
              gap,
              Text(l.transactions_direction, style: text.labelLarge),
              const SizedBox(height: SpaceTokens.s2),
              SegmentedButton<Direction>(
                segments: [
                  ButtonSegment(value: Direction.out, label: Text(l.transactions_directionOut)),
                  ButtonSegment(value: Direction.into, label: Text(l.transactions_directionIn)),
                ],
                selected: {_direction},
                showSelectedIcon: false,
                onSelectionChanged: (s) => setState(() => _direction = s.first),
              ),
              gap,
              TextFormField(
                controller: _amount,
                keyboardType: const TextInputType.numberWithOptions(decimal: true),
                autocorrect: false,
                enableSuggestions: false,
                decoration: InputDecoration(
                  labelText: l.transactions_amount,
                  helperText: account == null ? null : l.transactions_amountHint(account.currency),
                ),
                validator: (value) => parseAmountInput(value ?? '', allowSign: true) is ValidAmount
                    ? null
                    : l.validation_amount,
              ),
              gap,
              TextFormField(
                controller: _payee,
                maxLength: 80,
                autocorrect: false,
                onChanged: _payeeTyped,
                decoration: InputDecoration(
                  labelText: l.transactions_payee,
                  helperText: l.transactions_payeeHint,
                ),
              ),
              if (_suggestions.isNotEmpty)
                Wrap(
                  spacing: SpaceTokens.s2,
                  children: [
                    for (final s in _suggestions)
                      ActionChip(
                        label: Text(s),
                        onPressed: () => setState(() {
                          _payee.text = s;
                          _suggestions = const [];
                        }),
                      ),
                  ],
                ),
              gap,
              DropdownButtonFormField<String?>(
                initialValue: _categoryId,
                isExpanded: true,
                decoration: InputDecoration(labelText: l.transactions_category),
                items: [
                  DropdownMenuItem<String?>(child: Text(l.transactions_needsCategory)),
                  for (final cat in choosable)
                    DropdownMenuItem<String?>(
                      value: cat.id,
                      child: Text(
                        cat.parentName == null
                            ? cat.name
                            : l.transactions_categoryTrail(cat.parentName!, cat.name),
                      ),
                    ),
                  // A row filed under a category since archived keeps it: it is offered so the
                  // sheet can show it, and an edit that does not touch it does not send it.
                  if (_categoryId != null && choosable.every((cat) => cat.id != _categoryId))
                    DropdownMenuItem<String?>(
                      value: _categoryId,
                      child: Text(editing?.categoryName ?? ''),
                    ),
                ],
                onChanged: (value) => setState(() => _categoryId = value),
              ),
              gap,
              TextFormField(
                controller: _note,
                maxLength: 500,
                decoration: InputDecoration(
                  labelText: l.transactions_note,
                  helperText: l.transactions_noteHint,
                ),
              ),
              gap,
              Text(l.transactions_statusLabel, style: text.labelLarge),
              const SizedBox(height: SpaceTokens.s2),
              SegmentedButton<TransactionStatus>(
                segments: [
                  for (final s in TransactionStatus.settable)
                    ButtonSegment(value: s, label: Text(context.message(s.messageKey))),
                ],
                selected: {_status},
                showSelectedIcon: false,
                onSelectionChanged: (s) => setState(() => _status = s.first),
              ),
              Padding(
                padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
                child: Text(
                  l.transactions_statusHint,
                  style: text.bodySmall?.copyWith(color: c.muted),
                ),
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

/// The sentence for an amount the person typed, when it cannot be sent.
String amountProblemText(AppLocalizations l, AmountProblem problem, {bool unsigned = false}) =>
    unsigned ? l.validation_positiveAmount : l.validation_amount;
