import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/accounts/domain/requests.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/widgets/validation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Opens the add or edit sheet on the root navigator, above the tab bar: [editing] null adds one.
///
/// Returns the notice to show when something was saved, or null when nothing was.
Future<String?> showAccountSheet(
  BuildContext context, {
  required String defaultCurrency,
  Account? editing,
}) => Navigator.of(context, rootNavigator: true).push<String>(
  MaterialPageRoute(
    fullscreenDialog: true,
    builder: (_) => AccountSheet(editing: editing, defaultCurrency: defaultCurrency),
  ),
);

/// Adding an account, or correcting one (accounts.md, UI) — the web's dialog, field for field.
///
/// - **The opening balance is text**, signed, sent as typed (ADR-0006). Zero is valid and negative
///   is normal; nothing guesses a sign from the type.
/// - **A locked currency is disabled with the reason beside it**, not hidden.
/// - **An edit sends only what changed**; an untouched sheet sends nothing.
/// - **A `409 stale-version` stops and asks**: the server's copy is read again, each field that
///   moved is named with its new value, Save is disabled, and the two ways out are explicit —
///   *use theirs* reloads the form, *keep mine and save* sends the same form against the version
///   just shown. Nothing adopts a new version silently.
class AccountSheet extends ConsumerStatefulWidget {
  const AccountSheet({required this.defaultCurrency, this.editing, super.key});

  final Account? editing;
  final String defaultCurrency;

  @override
  ConsumerState<AccountSheet> createState() => _AccountSheetState();
}

class _AccountSheetState extends ConsumerState<AccountSheet> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController();
  final _lastFour = TextEditingController();
  final _currency = TextEditingController();
  final _opening = TextEditingController();
  String _type = 'CHECKING';

  /// Bumped whenever the form is refilled, so the type picker shows the new value.
  int _filled = 0;

  /// The copy the form is editing against: the one it opened with, or the server's after a
  /// conflict the person chose to resolve.
  Account? _target;
  AppError? _error;
  bool _busy = false;

  /// Fields the server named in a refusal, marked until they are edited.
  Set<String> _refused = const {};

  /// A conflict standing: the server's copy and what differs from the one this form opened with.
  Account? _latest;
  List<AccountField>? _conflict;

  /// What was submitted when the conflict happened, so "keep mine" has something to keep.
  AccountForm? _submitted;

  @override
  void initState() {
    super.initState();
    _target = widget.editing;
    final editing = widget.editing;
    _fill(
      editing == null
          ? AccountForm(
              name: '',
              type: 'CHECKING',
              currency: widget.defaultCurrency.toUpperCase(),
              openingBalance: '',
            )
          : AccountForm.of(editing),
    );
  }

  void _fill(AccountForm form) {
    _name.text = form.name;
    _lastFour.text = form.lastFour;
    _currency.text = form.currency;
    _opening.text = form.openingBalance;
    _type = form.type;
    _filled++;
  }

  @override
  void dispose() {
    for (final controller in [_name, _lastFour, _currency, _opening]) {
      controller.dispose();
    }
    super.dispose();
  }

  AccountForm get _typed => AccountForm(
    name: _name.text,
    lastFour: _lastFour.text,
    type: _type,
    currency: _currency.text,
    openingBalance: _opening.text,
  );

  Future<void> _save() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    _submitted = _typed;
    await _send(_target, _typed);
  }

  Future<void> _send(Account? target, AccountForm form) async {
    final l = context.l10n;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final writer = ref.read(accountsWriterProvider);
      if (target == null) {
        await writer.create(form);
        if (mounted) Navigator.of(context).pop(l.accounts_added);
      } else {
        final sent = await writer.update(target, form);
        if (mounted) Navigator.of(context).pop(sent ? l.accounts_saved : null);
      }
    } on AppError catch (error) {
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (target != null && error.status == 409 && error.code == 'stale-version') {
        await _explainConflict(target);
      } else if (mounted) {
        setState(() {
          _error = error;
          _refused = error.fields.toSet();
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Somebody else saved first. Read the server's copy and say which fields moved — then stop.
  Future<void> _explainConflict(Account opened) async {
    try {
      final latest = await ref.read(accountsWriterProvider).latest(opened.id);
      if (!mounted) return;
      setState(() {
        _latest = latest;
        _conflict = differingFields(opened, latest);
      });
    } on AppError catch (error) {
      // The re-read failed too (it may have been deleted). Say that rather than invent a diff.
      if (mounted) setState(() => _error = error);
    }
  }

  /// "Use theirs": the server's copy replaces the form; the person's edit is discarded knowingly.
  void _useTheirs() {
    final latest = _latest;
    if (latest == null) return;
    setState(() {
      _target = latest;
      _fill(AccountForm.of(latest));
      _latest = null;
      _conflict = null;
      _error = null;
    });
  }

  /// "Keep mine and save": the same edit, now against the version they have just been shown.
  Future<void> _keepMine() async {
    final latest = _latest;
    final form = _submitted;
    if (latest == null || form == null) return;
    setState(() {
      _target = latest;
      _latest = null;
      _conflict = null;
    });
    await _send(latest, form);
  }

  String? _refusedText(AppLocalizations l, String field) =>
      _refused.contains(field) ? l.validation_server : null;

  void _edited(String field) {
    if (_refused.contains(field)) setState(() => _refused = {..._refused}..remove(field));
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final editing = widget.editing;
    final online = canWriteNow(ref);
    final locked = _target?.knownToHaveHistory ?? false;
    final error = _error;
    final conflict = _conflict;
    const gap = SizedBox(height: SpaceTokens.s4);

    return Scaffold(
      appBar: AppBar(
        title: Text(editing == null ? l.accounts_addTitle : l.accounts_editTitle, maxLines: 2),
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
              if (error != null) ...[
                NoticeBanner(
                  text: error.isConnectivity ? l.offline_write : context.errorMessage(error),
                ),
                gap,
              ],
              if (conflict != null && _latest != null) ...[
                _ConflictPanel(
                  latest: _latest!,
                  fields: conflict,
                  busy: _busy,
                  onUseTheirs: _useTheirs,
                  onKeepMine: online ? _keepMine : null,
                ),
                gap,
              ],
              TextFormField(
                controller: _name,
                maxLength: 80,
                autocorrect: false,
                onChanged: (_) => _edited('name'),
                decoration: InputDecoration(
                  labelText: l.accounts_name,
                  helperText: l.accounts_nameHint,
                  helperMaxLines: 10,
                ),
                forceErrorText: _refusedText(l, 'name'),
                validator: (v) =>
                    Rules.required(l, v ?? '') ??
                    ((v ?? '').trim().length > 80 ? l.validation_maxLength('80') : null),
              ),
              gap,
              TextFormField(
                controller: _lastFour,
                maxLength: 4,
                keyboardType: TextInputType.number,
                autocorrect: false,
                enableSuggestions: false,
                inputFormatters: [FilteringTextInputFormatter.deny(RegExp(r'\s'))],
                onChanged: (_) => _edited('lastFour'),
                decoration: InputDecoration(
                  labelText: l.accounts_lastFour,
                  helperText: l.accounts_lastFourHint,
                  helperMaxLines: 10,
                ),
                forceErrorText: _refusedText(l, 'lastFour'),
                validator: (v) => isLastFour(v ?? '') ? null : l.validation_lastFour,
              ),
              gap,
              DropdownButtonFormField<String>(
                key: ValueKey('type-$_filled'),
                initialValue: _type,
                isExpanded: true,
                isDense: false,
                decoration: InputDecoration(
                  labelText: l.accounts_type,
                  errorText: _refusedText(l, 'type'),
                ),
                items: [
                  for (final type in {...accountTypes, _type})
                    DropdownMenuItem(
                      value: type,
                      child: Text(context.message(accountTypeKey(type))),
                    ),
                ],
                onChanged: (value) {
                  if (value != null) setState(() => _type = value);
                  _edited('type');
                },
              ),
              gap,
              TextFormField(
                controller: _currency,
                enabled: !locked,
                maxLength: 3,
                textCapitalization: TextCapitalization.characters,
                autocorrect: false,
                enableSuggestions: false,
                onChanged: (_) => _edited('currency'),
                decoration: InputDecoration(
                  labelText: l.accounts_currency,
                  helperText: locked ? l.accounts_currencyLocked : l.accounts_currencyHint,
                  helperMaxLines: 10,
                ),
                forceErrorText: _refusedText(l, 'currency'),
                validator: (v) => Rules.currency(l, v ?? ''),
              ),
              gap,
              TextFormField(
                controller: _opening,
                // A minus is how a credit card you owe on opens; the keypad must offer one.
                keyboardType: const TextInputType.numberWithOptions(signed: true, decimal: true),
                textDirection: TextDirection.ltr,
                autocorrect: false,
                enableSuggestions: false,
                onChanged: (_) => _edited('openingBalance'),
                decoration: InputDecoration(
                  labelText: l.accounts_openingBalance,
                  helperText: l.accounts_openingBalanceHint,
                  helperMaxLines: 10,
                ),
                forceErrorText: _refusedText(l, 'openingBalance'),
                validator: (v) =>
                    Rules.required(l, v ?? '') ??
                    (isOpeningBalance(v ?? '') ? null : l.validation_amount),
              ),
              gap,
              FilledButton(
                // While a conflict stands this would send the version the server just refused;
                // the two choices above are the way forward.
                onPressed: _busy || !online || conflict != null ? null : _save,
                child: Text(_busy ? l.common_saving : l.accounts_save),
              ),
              const OfflineNote(),
            ],
          ),
        ),
      ),
    );
  }
}

/// "Somebody else saved first", what the account says now, and the two ways out.
class _ConflictPanel extends StatelessWidget {
  const _ConflictPanel({
    required this.latest,
    required this.fields,
    required this.busy,
    required this.onUseTheirs,
    required this.onKeepMine,
  });

  final Account latest;
  final List<AccountField> fields;
  final bool busy;
  final VoidCallback onUseTheirs;

  /// Null offline: keeping mine is a write.
  final VoidCallback? onKeepMine;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    String label(AccountField field) => switch (field) {
      AccountField.name => l.accounts_name,
      AccountField.lastFour => l.accounts_lastFour,
      AccountField.type => l.accounts_type,
      AccountField.currency => l.accounts_currency,
      AccountField.openingBalance => l.accounts_openingBalance,
      AccountField.archived => l.accounts_state,
    };
    String value(AccountField field) => switch (field) {
      AccountField.name => latest.name,
      AccountField.lastFour => latest.lastFour ?? l.accounts_lastFourNone,
      AccountField.type => context.message(accountTypeKey(latest.type)),
      AccountField.currency => latest.currency,
      AccountField.openingBalance => context.money(latest.openingBalance),
      AccountField.archived => latest.archived ? l.accounts_archivedTag : l.accounts_activeTag,
    };

    return Semantics(
      liveRegion: true,
      container: true,
      child: Container(
        padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
        decoration: BoxDecoration(
          color: Color.alphaBlend(c.accent.withAlpha(36), c.bg),
          borderRadius: BorderRadius.circular(RadiusTokens.md),
          border: BorderDirectional(
            start: BorderSide(color: c.accent, width: 6),
            top: BorderSide(color: c.accent),
            end: BorderSide(color: c.accent),
            bottom: BorderSide(color: c.accent),
          ),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(l.accounts_conflictTitle, style: text.titleMedium),
            const SizedBox(height: SpaceTokens.s1),
            Text(l.accounts_conflictBody, style: text.bodyMedium),
            const SizedBox(height: SpaceTokens.s2),
            for (final field in fields)
              Padding(
                padding: const EdgeInsetsDirectional.only(bottom: SpaceTokens.s1),
                child: Text(
                  l.accounts_conflictField(label(field), value(field)),
                  style: text.bodyMedium,
                ),
              ),
            const SizedBox(height: SpaceTokens.s2),
            Wrap(
              spacing: SpaceTokens.s2,
              runSpacing: SpaceTokens.s2,
              children: [
                OutlinedButton(onPressed: onUseTheirs, child: Text(l.accounts_conflictUseLatest)),
                OutlinedButton(
                  style: OutlinedButton.styleFrom(
                    foregroundColor: c.accentText,
                    side: BorderSide(color: c.accentText),
                  ),
                  onPressed: busy ? null : onKeepMine,
                  child: Text(l.accounts_conflictOverwrite),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
