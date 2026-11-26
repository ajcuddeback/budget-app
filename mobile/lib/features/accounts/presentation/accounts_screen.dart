import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/accounts/domain/requests.dart';
import 'package:budget_owl/features/accounts/presentation/account_sheet.dart';
import 'package:budget_owl/shared/widgets/grouped_row.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Where the household's money lives (accounts.md, UI), built on the Transactions pattern: a
/// cache-first read, the stale label, the role deciding what is offered, writes refused offline,
/// and every screen re-reading after a write rather than this one patching a figure.
///
/// The feature's rules, as the web keeps them:
/// - **Nothing is totalled across currencies**, and on the phone **nothing is totalled at all**:
///   the web's per-currency subtotal is the one sum its client computes, and this app never adds
///   two amounts (mobile-app.md, Language and money). With two currencies the rows are grouped
///   under each code; each balance is the server's.
/// - **Archived accounts are a separate section**, behind a toggle, muted.
/// - **Delete is offered only where the server said the account is empty**, never inferred from
///   the amounts. The server refuses anything else with `409 account-not-empty`.
/// - **A `VIEWER` sees no write control.** The API refuses one regardless.
class AccountsScreen extends ConsumerStatefulWidget {
  const AccountsScreen({super.key});

  @override
  ConsumerState<AccountsScreen> createState() => _AccountsScreenState();
}

enum _Action { edit, archive, unarchive, delete }

class _AccountsScreenState extends ConsumerState<AccountsScreen> {
  bool _showArchived = false;
  String? _notice;
  AppError? _actionError;

  /// Which account [_actionError] is about: the banner sits above the list, so it names it.
  String? _actionSubject;

  void _said(String? notice) {
    if (notice != null && mounted) {
      setState(() {
        _notice = notice;
        _actionError = null;
        _actionSubject = null;
      });
    }
  }

  void _offline() => setState(() {
    _notice = null;
    _actionSubject = null;
    _actionError = const AppError.offline();
  });

  Future<void> _run(Account account, Future<void> Function() action, String notice) async {
    setState(() {
      _actionError = null;
      _actionSubject = null;
      _notice = null;
    });
    try {
      await action();
      _said(notice);
    } on AppError catch (error) {
      // A 409 is shown, and the list re-reads to show the server's truth (mobile-app.md, Writes).
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) {
        setState(() {
          _actionError = error;
          _actionSubject = account.name;
        });
      }
    }
  }

  Future<void> _add(String baseCurrency) async {
    setState(() {
      _notice = null;
      _actionError = null;
    });
    _said(await showAccountSheet(context, defaultCurrency: baseCurrency));
  }

  Future<void> _rowActions(Account account, String baseCurrency) async {
    final l = context.l10n;
    final online = ref.read(reachabilityProvider).canWrite;
    final deletable = !account.knownToHaveHistory;
    final choice = await showRowActions<_Action>(
      context,
      title: l.transactions_actionsFor(accountTitle(context, account)),
      online: online,
      actions: [
        if (account.archived)
          RowAction(_Action.unarchive, Icons.unarchive_outlined, l.accounts_unarchive)
        else ...[
          RowAction(_Action.edit, Icons.edit_outlined, l.accounts_edit),
          RowAction(_Action.archive, Icons.archive_outlined, l.accounts_archive),
        ],
        if (deletable)
          RowAction(_Action.delete, Icons.delete_outline, l.accounts_delete, danger: true),
      ],
    );
    if (choice == null || !mounted) return;
    final writer = ref.read(accountsWriterProvider);
    switch (choice) {
      case _Action.edit:
        setState(() {
          _notice = null;
          _actionError = null;
        });
        _said(await showAccountSheet(context, defaultCurrency: baseCurrency, editing: account));
      case _Action.archive:
        final confirmed = await confirmAction(
          context,
          heading: l.accounts_archiveHeading(account.name),
          message: l.accounts_archiveMessagePhone,
          action: l.accounts_archive,
        );
        if (confirmed) {
          await _run(account, () => writer.archive(account), l.accounts_archivedNotice);
        }
      case _Action.unarchive:
        // Bringing one back loses nothing, so it does not ask first.
        await _run(account, () => writer.unarchive(account), l.accounts_unarchivedNotice);
      case _Action.delete:
        final confirmed = await confirmAction(
          context,
          heading: l.accounts_deleteHeading(account.name),
          message: l.accounts_deleteMessage,
          action: l.accounts_delete,
          destructive: true,
        );
        if (confirmed) {
          await _run(account, () => writer.delete(account), l.accounts_deletedNotice);
        }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final membership = ref.watch(signedInProvider)?.membership;
    final canWrite = membership?.role.canWrite ?? false;
    final baseCurrency = membership?.baseCurrency ?? '';
    final online = canWriteNow(ref);
    final snapshot = ref.watch(accountsProvider);
    final hasActive = snapshot.data?.any((a) => !a.archived) ?? false;
    final error = _actionError;

    return Screen(
      title: l.accounts_title,
      onRefresh: () => ref.read(accountsProvider.notifier).refresh(),
      actions: [
        if (canWrite && hasActive)
          TextButton.icon(
            // Offline it stays tappable and says why, as Transactions' buttons do.
            onPressed: online ? () => _add(baseCurrency) : _offline,
            style: online ? null : TextButton.styleFrom(foregroundColor: c.muted),
            icon: const Icon(Icons.add),
            label: Text(l.accounts_add),
          ),
      ],
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.accounts_lede),
          if (_notice != null) Gutter(child: NoticeBanner(text: _notice!, success: true)),
          if (error != null)
            Gutter(
              child: NoticeBanner(
                text: subjectAndMessage(
                  _actionSubject,
                  error.isConnectivity ? l.offline_write : context.errorMessage(error),
                ),
              ),
            ),
          SnapshotView<AccountList>(
            snapshot: snapshot,
            onRetry: () => ref.read(accountsProvider.notifier).refresh(),
            builder: (context, accounts, stale) => _AccountsBody(
              accounts: accounts,
              stale: stale,
              canWrite: canWrite,
              showArchived: _showArchived,
              onToggleArchived: () => setState(() => _showArchived = !_showArchived),
              onAddFirst: online ? () => _add(baseCurrency) : _offline,
              onRow: canWrite ? (account) => _rowActions(account, baseCurrency) : null,
            ),
          ),
        ],
      ),
    );
  }
}

/// "Travel Card •8820" through the web's translated template, so a right-to-left language can
/// order the two (ADR-0023, ADR-0028).
String accountTitle(BuildContext context, Account account) {
  final digits = account.lastFour;
  return digits == null ? account.name : context.l10n.accounts_nameWithDigits(account.name, digits);
}

/// A refusal that names what it is about, as the web's error alert does: *"Gift Card — …"*.
String subjectAndMessage(String? subject, String message) =>
    subject == null ? message : '$subject — $message';

class _AccountsBody extends StatelessWidget {
  const _AccountsBody({
    required this.accounts,
    required this.stale,
    required this.canWrite,
    required this.showArchived,
    required this.onToggleArchived,
    required this.onAddFirst,
    required this.onRow,
  });

  final AccountList accounts;
  final Widget? stale;
  final bool canWrite;
  final bool showArchived;
  final VoidCallback onToggleArchived;
  final VoidCallback onAddFirst;
  final void Function(Account account)? onRow;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final active = [
      for (final a in accounts)
        if (!a.archived) a,
    ];
    final archived = [
      for (final a in accounts)
        if (a.archived) a,
    ];
    final groups = groupByCurrency(active);
    final notShown = accounts.totalElements - accounts.length;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (stale != null) Gutter(child: stale!),
        Overline(l.accounts_heading),
        if (active.isEmpty) ...[
          // First run is the common case, not a fault: what an account is for, and one thing to do.
          Lede(l.accounts_emptyBody),
          if (canWrite)
            Gutter(
              child: Align(
                alignment: AlignmentDirectional.centerStart,
                child: FilledButton(onPressed: onAddFirst, child: Text(l.accounts_addFirst)),
              ),
            )
          else
            Lede(l.accounts_emptyReadOnly),
        ] else
          for (final group in groups) ...[
            // Headings only from the second currency on: one group needs no name.
            if (groups.length > 1) _CurrencyHeading(group.key),
            for (var i = 0; i < group.value.length; i++)
              _AccountRow(
                account: group.value[i],
                first: i == 0,
                last: i == group.value.length - 1,
                onTap: onRow,
              ),
          ],
        if (notShown > 0)
          Lede(notShown == 1 ? l.accounts_notShownOne : l.accounts_notShown('$notShown')),
        if (archived.isNotEmpty) ...[
          Overline(l.accounts_archivedHeading),
          Gutter(
            child: Align(
              alignment: AlignmentDirectional.centerStart,
              child: OutlinedButton(
                onPressed: onToggleArchived,
                child: Text(
                  showArchived
                      ? l.accounts_hideArchived
                      : l.accounts_showArchived('${archived.length}'),
                ),
              ),
            ),
          ),
          if (showArchived) ...[
            Lede(canWrite ? l.accounts_archivedNoteWritable : l.accounts_archivedNote),
            for (var i = 0; i < archived.length; i++)
              _AccountRow(
                account: archived[i],
                first: i == 0,
                last: i == archived.length - 1,
                onTap: onRow,
              ),
          ],
        ],
      ],
    );
  }
}

/// The accounts by currency, groups in code order so the list does not reshuffle between loads;
/// accounts keep the server's order (by name, case-insensitively, then id). **No sum anywhere.**
List<MapEntry<String, List<Account>>> groupByCurrency(List<Account> accounts) {
  final byCurrency = <String, List<Account>>{};
  for (final account in accounts) {
    byCurrency.putIfAbsent(account.currency, () => []).add(account);
  }
  return byCurrency.entries.toList()..sort((a, b) => a.key.compareTo(b.key));
}

class _CurrencyHeading extends StatelessWidget {
  const _CurrencyHeading(this.currency);

  final String currency;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.fromSTEB(
      SpaceTokens.s4,
      SpaceTokens.s3,
      SpaceTokens.s4,
      SpaceTokens.s1,
    ),
    child: Semantics(
      header: true,
      child: Text(currency, style: Theme.of(context).textTheme.titleSmall),
    ),
  );
}

class _AccountRow extends StatelessWidget {
  const _AccountRow({
    required this.account,
    required this.first,
    required this.last,
    required this.onTap,
  });

  final Account account;
  final bool first;
  final bool last;
  final void Function(Account account)? onTap;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final title = accountTitle(context, account);
    final type = context.message(accountTypeKey(account.type));
    final kind = account.archived ? '$type · ${l.accounts_archivedTag}' : type;
    final balance = context.money(account.balance);
    final tap = onTap;
    return GroupedRow(
      title: title,
      semanticLabel: '$title, $kind, $balance',
      lines: [RowLine(kind)],
      muted: account.archived,
      first: first,
      last: last,
      onTap: tap == null ? null : () => tap(account),
      trailing: Text(
        balance,
        textDirection: context.amountDirection,
        style: Theme.of(context).textTheme.titleMedium?.copyWith(
          color: account.archived ? context.colors.muted : null,
          fontFeatures: const [FontFeature.tabularFigures()],
        ),
      ),
    );
  }
}
