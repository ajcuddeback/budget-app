import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/dates/calendar_date.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/categories/data/categories.dart';
import 'package:budget_owl/features/transactions/data/transactions_repository.dart';
import 'package:budget_owl/features/transactions/domain/transaction.dart';
import 'package:budget_owl/features/transactions/presentation/ledger_row.dart';
import 'package:budget_owl/features/transactions/presentation/pair_sheet.dart';
import 'package:budget_owl/features/transactions/presentation/transaction_sheet.dart';
import 'package:budget_owl/features/transactions/presentation/transfer_sheet.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// The ledger: what actually happened to the household's money (transactions.md, transfers.md).
///
/// **The reference feature for phase 2** — every pattern a screen needs is here once: a
/// cache-first read per query ([ledgerProvider]), the stale label, the role deciding what is
/// offered (never what is allowed), writes refused offline, a write's errors from their code, a
/// `409` that re-reads, and the screens refreshing after a write rather than this one patching a
/// figure. See mobile/README.md.
///
/// The rules it keeps, as the web's ledger does:
/// - **Nothing is totalled across currencies.** The page total is the server's, and absent when a
///   page mixes them; there is no addition here.
/// - **"Needs a category" is a filter you can stand in**, separate from the category filter.
/// - **A transfer is one thing with two rows.** Both legs show; Edit, Split and Delete act on the
///   pair through its group id, and a leg with none offers nothing.
/// - **A `VIEWER` sees no write control** at all. The API refuses one regardless.
class TransactionsScreen extends ConsumerStatefulWidget {
  const TransactionsScreen({super.key});

  @override
  ConsumerState<TransactionsScreen> createState() => _TransactionsScreenState();
}

class _TransactionsScreenState extends ConsumerState<TransactionsScreen> {
  LedgerQuery _query = const LedgerQuery();
  final _search = TextEditingController();
  String? _notice;
  AppError? _actionError;

  @override
  void dispose() {
    _search.dispose();
    super.dispose();
  }

  void _filter(LedgerQuery next) => setState(() {
    _query = next;
    _notice = null;
  });

  void _said(String? notice) {
    if (notice != null && mounted) {
      setState(() {
        _notice = notice;
        _actionError = null;
      });
    }
  }

  Future<void> _run(Future<void> Function() action, String notice) async {
    setState(() {
      _actionError = null;
      _notice = null;
    });
    try {
      await action();
      _said(notice);
    } on AppError catch (error) {
      if (error.status == 409) ref.read(dataRevisionProvider.notifier).bump();
      if (mounted) setState(() => _actionError = error);
    }
  }

  Future<void> _openFilters(List<Category> categories) async {
    final next = await showModalBottomSheet<LedgerQuery>(
      context: context,
      isScrollControlled: true,
      builder: (_) => _FilterSheet(query: _query, categories: categories),
    );
    if (next != null) _filter(next);
  }

  Future<void> _rowActions(
    Transaction row,
    List<Account> accounts,
    List<Category> categories,
    List<Transaction> ledger,
  ) async {
    final l = context.l10n;
    final writer = ref.read(transactionsWriterProvider);
    final online = canWriteNowFrom(ref);
    final choice = await showModalBottomSheet<_RowAction>(
      context: context,
      builder: (sheetContext) => _RowActionsSheet(row: row, online: online),
    );
    if (choice == null || !mounted) return;
    switch (choice) {
      case _RowAction.edit || _RowAction.categorise:
        _said(
          await showTransactionSheet(
            context,
            accounts: accounts,
            categories: categories,
            editing: row,
          ),
        );
      case _RowAction.pair:
        _said(await showPairSheet(context, row: row, ledger: ledger));
      case _RowAction.delete:
        final confirmed = await confirmAction(
          context,
          heading: l.transactions_deleteHeading,
          message: l.transactions_deleteMessage,
          action: l.common_delete,
          destructive: true,
        );
        if (confirmed) await _run(() => writer.delete(row), l.transactions_deleted);
      case _RowAction.editTransfer:
        final id = row.actionableTransferId;
        if (id == null) return;
        try {
          final transfer = await writer.transfer(id);
          if (!mounted) return;
          _said(await showTransferSheet(context, accounts: accounts, editing: transfer));
        } on AppError catch (error) {
          if (mounted) setState(() => _actionError = error);
        }
      case _RowAction.split:
        final id = row.actionableTransferId;
        if (id == null) return;
        try {
          final transfer = await writer.transfer(id);
          if (!mounted) return;
          final confirmed = await confirmAction(
            context,
            heading: l.pairing_splitHeading,
            message: l.pairing_splitMessage,
            action: l.pairing_split,
          );
          if (confirmed) await _run(() => writer.split(transfer), l.pairing_splitDone);
        } on AppError catch (error) {
          if (mounted) setState(() => _actionError = error);
        }
      case _RowAction.deleteTransfer:
        final id = row.actionableTransferId;
        if (id == null) return;
        final confirmed = await confirmAction(
          context,
          heading: l.transfers_deleteHeading,
          message: l.transfers_deleteMessage,
          action: l.common_delete,
          destructive: true,
        );
        if (confirmed) await _run(() => writer.deleteTransfer(id), l.transfers_deleted);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final role = ref.watch(signedInProvider)?.membership?.role;
    final canWrite = role?.canWrite ?? false;
    final online = canWriteNow(ref);
    final ledger = ref.watch(ledgerProvider(_query));
    final accountsSnapshot = ref.watch(accountsProvider);
    final categoriesSnapshot = ref.watch(categoriesProvider);
    final accounts = accountsSnapshot.data ?? const <Account>[];
    final categories = categoriesSnapshot.data ?? const <Category>[];
    final open = accounts.where((a) => !a.archived).toList();
    Account? selected;
    for (final a in accounts) {
      if (a.id == _query.accountId) selected = a;
    }

    final fab = !canWrite || open.isEmpty
        ? null
        : Semantics(
            enabled: online,
            child: FloatingActionButton.extended(
              backgroundColor: online ? null : c.divider,
              foregroundColor: online ? null : c.muted,
              onPressed: online
                  ? () async => _said(
                      await showTransactionSheet(
                        context,
                        accounts: accounts,
                        categories: categories,
                        defaultAccountId: _query.accountId,
                      ),
                    )
                  : () => setState(() => _actionError = const AppError.offline()),
              icon: const Icon(Icons.add),
              label: Text(l.transactions_add),
            ),
          );

    return Screen(
      title: l.transactions_title,
      floatingActionButton: fab,
      onRefresh: () => Future.wait([
        ref.read(ledgerProvider(_query).notifier).refresh(),
        ref.read(accountsProvider.notifier).refresh(),
      ]),
      actions: [
        if (canWrite && open.length >= 2)
          TextButton(
            onPressed: online
                ? () async => _said(await showTransferSheet(context, accounts: accounts))
                : null,
            child: Text(l.transfers_move),
          ),
        IconButton(
          tooltip: l.transactions_filterButton,
          icon: Icon(_query.filtered ? Icons.filter_alt : Icons.filter_alt_outlined),
          onPressed: () => _openFilters(categories),
        ),
      ],
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Gutter(
            child: TextField(
              controller: _search,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                hintText: l.transactions_searchPlaceholder,
                labelText: l.transactions_search,
                prefixIcon: const Icon(Icons.search),
                helperText: l.transactions_searchHint,
              ),
              onSubmitted: (value) => _filter(
                _query.copyWith(search: () => value.trim().isEmpty ? null : value.trim()),
              ),
            ),
          ),
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsetsDirectional.symmetric(horizontal: SpaceTokens.s4),
            child: Row(
              children: [
                _Chip(
                  label: l.transactions_allAccounts,
                  selected: _query.accountId == null,
                  onTap: () => _filter(_query.copyWith(accountId: () => null)),
                ),
                for (final a in accounts)
                  _Chip(
                    label: a.label,
                    selected: _query.accountId == a.id,
                    onTap: () => _filter(_query.copyWith(accountId: () => a.id)),
                  ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsetsDirectional.fromSTEB(SpaceTokens.s4, 0, SpaceTokens.s4, 0),
            child: Wrap(
              spacing: SpaceTokens.s2,
              children: [
                FilterChip(
                  label: Text(l.transactions_needsCategoryFilter),
                  selected: _query.uncategorised,
                  onSelected: (on) => _filter(
                    _query.copyWith(
                      uncategorised: on,
                      categoryId: on ? () => null : null,
                    ),
                  ),
                ),
                if (_query.filtered)
                  ActionChip(
                    label: Text(l.transactions_clearFilters),
                    onPressed: () {
                      _search.clear();
                      _filter(const LedgerQuery());
                    },
                  ),
              ],
            ),
          ),
          if (selected != null) _BalanceCard(account: selected),
          if (_notice != null) Gutter(child: NoticeBanner(text: _notice!, success: true)),
          if (_actionError != null)
            Gutter(
              child: NoticeBanner(
                text: _actionError!.isConnectivity
                    ? l.offline_write
                    : context.errorMessage(_actionError!),
              ),
            ),
          if (canWrite && !online) const Gutter(child: OfflineNote()),
          SnapshotView<Ledger>(
            snapshot: ledger,
            onRetry: () => ref.read(ledgerProvider(_query).notifier).refresh(),
            builder: (context, page, stale) => _LedgerBody(
              page: page,
              stale: stale,
              query: _query,
              canWrite: canWrite,
              noAccounts: accountsSnapshot.hasData && accounts.isEmpty,
              onRow: canWrite
                  ? (row) => _rowActions(row, accounts, categories, page.content)
                  : null,
              onPage: (index) => _filter(_query.copyWith(page: index)),
            ),
          ),
        ],
      ),
    );
  }
}

/// The offline check outside a build, for an action begun from a callback.
bool canWriteNowFrom(WidgetRef ref) => ref.read(reachabilityProvider).canWrite;

class _Chip extends StatelessWidget {
  const _Chip({required this.label, required this.selected, required this.onTap});

  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(end: SpaceTokens.s2),
    child: ChoiceChip(
      label: Text(label),
      selected: selected,
      showCheckmark: false,
      labelStyle: Theme.of(context).textTheme.labelLarge?.copyWith(
        color: selected ? context.colors.bg : context.colors.text,
      ),
      onSelected: (_) => onTap(),
    ),
  );
}

/// The chosen account's balance — the server's figure, refreshed after every write.
class _BalanceCard extends StatelessWidget {
  const _BalanceCard({required this.account});

  final Account account;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsetsDirectional.fromSTEB(
        SpaceTokens.s4,
        SpaceTokens.s2,
        SpaceTokens.s4,
        0,
      ),
      child: Card(
        child: Padding(
          padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  context.l10n.transactions_balanceOf(account.label),
                  style: text.bodyMedium?.copyWith(color: context.colors.muted),
                ),
              ),
              Text(context.money(account.balance), style: text.titleLarge),
            ],
          ),
        ),
      ),
    );
  }
}

class _LedgerBody extends StatelessWidget {
  const _LedgerBody({
    required this.page,
    required this.stale,
    required this.query,
    required this.canWrite,
    required this.noAccounts,
    required this.onRow,
    required this.onPage,
  });

  final Ledger page;
  final Widget? stale;
  final LedgerQuery query;
  final bool canWrite;
  final bool noAccounts;
  final void Function(Transaction row)? onRow;
  final void Function(int page) onPage;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final muted = context.colors.muted;
    final rows = page.content;
    final days = <CalendarDate, List<Transaction>>{};
    for (final row in rows) {
      days.putIfAbsent(row.date, () => []).add(row);
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (stale != null) Gutter(child: stale!),
        if (rows.isEmpty)
          Lede(
            query.filtered
                ? l.transactions_noMatches
                : noAccounts
                ? l.transactions_noAccountsBody
                : canWrite
                ? l.transactions_emptyBody
                : '${l.transactions_emptyBody} ${l.transactions_emptyReadOnly}',
          ),
        for (final day in days.entries) ...[
          Overline(day.key.heading(context.localeName)),
          for (var i = 0; i < day.value.length; i++)
            LedgerRow(
              transaction: day.value[i],
              showAccount: query.accountId == null,
              first: i == 0,
              last: i == day.value.length - 1,
              onTap: onRow == null ? null : () => onRow!(day.value[i]),
            ),
        ],
        if (rows.isNotEmpty)
          Gutter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  l.transactions_showing('${rows.length}', '${page.totalElements}'),
                  style: text.bodySmall?.copyWith(color: muted),
                ),
                if (page.pageTotal != null)
                  Text(
                    '${l.transactions_pageTotal} ${context.money(page.pageTotal!)}',
                    style: text.bodyMedium,
                  )
                else
                  Text(l.transactions_mixedCurrencies, style: text.bodySmall?.copyWith(color: muted)),
              ],
            ),
          ),
        if (page.totalPages > 1)
          Gutter(
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                OutlinedButton(
                  onPressed: page.page == 0 ? null : () => onPage(page.page - 1),
                  child: Text(l.common_previous),
                ),
                Text(l.transactions_pageOf('${page.page + 1}', '${page.totalPages}')),
                OutlinedButton(
                  onPressed: page.page + 1 >= page.totalPages ? null : () => onPage(page.page + 1),
                  child: Text(l.common_next),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

enum _RowAction { categorise, edit, pair, delete, editTransfer, split, deleteTransfer }

class _RowActionsSheet extends StatelessWidget {
  const _RowActionsSheet({required this.row, required this.online});

  final Transaction row;
  final bool online;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final transferId = row.actionableTransferId;
    Widget action(_RowAction value, IconData icon, String label, {bool danger = false}) =>
        ListTile(
          enabled: online,
          leading: Icon(icon, color: danger ? context.colors.accentText : null),
          title: Text(
            label,
            style: danger ? TextStyle(color: context.colors.accentText) : null,
          ),
          onTap: () => Navigator.of(context).pop(value),
        );

    return SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Gutter(
            child: Semantics(
              header: true,
              child: Text(
                l.transactions_actionsFor(rowTitle(context, row)),
                style: Theme.of(context).textTheme.titleMedium,
              ),
            ),
          ),
          if (row.transfer && transferId == null)
            Lede(l.transactions_transferPairOnly)
          else if (row.transfer) ...[
            action(_RowAction.editTransfer, Icons.edit_outlined, l.common_edit),
            action(_RowAction.split, Icons.call_split, l.transactions_split),
            action(_RowAction.deleteTransfer, Icons.delete_outline, l.common_delete, danger: true),
          ] else ...[
            if (row.needsCategory)
              action(_RowAction.categorise, Icons.category_outlined, l.transactions_categorise),
            action(_RowAction.edit, Icons.edit_outlined, l.common_edit),
            action(_RowAction.pair, Icons.link, l.transactions_pair),
            action(_RowAction.delete, Icons.delete_outline, l.common_delete, danger: true),
          ],
          if (!online) const Gutter(child: OfflineNote()),
          const SizedBox(height: SpaceTokens.s4),
        ],
      ),
    );
  }
}

/// Category, dates, and the clear button: the filters the web shows inline, in a sheet.
class _FilterSheet extends StatefulWidget {
  const _FilterSheet({required this.query, required this.categories});

  final LedgerQuery query;
  final List<Category> categories;

  @override
  State<_FilterSheet> createState() => _FilterSheetState();
}

class _FilterSheetState extends State<_FilterSheet> {
  late LedgerQuery _draft = widget.query;

  Future<CalendarDate?> _pick(CalendarDate? current) async {
    final picked = await showDatePicker(
      context: context,
      initialDate: (current ?? CalendarDate.of(DateTime.now())).forPicker,
      firstDate: DateTime(1970),
      lastDate: DateTime(2100),
    );
    return picked == null ? null : CalendarDate.of(picked);
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final choosable = pickableCategories(widget.categories);
    return SafeArea(
      child: Padding(
        padding: EdgeInsetsDirectional.only(
          start: SpaceTokens.s4,
          end: SpaceTokens.s4,
          bottom: MediaQuery.viewInsetsOf(context).bottom + SpaceTokens.s4,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(l.transactions_filtersLabel, style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: SpaceTokens.s4),
            DropdownButtonFormField<String?>(
              initialValue: _draft.categoryId,
              isExpanded: true,
              decoration: InputDecoration(labelText: l.transactions_category),
              items: [
                DropdownMenuItem<String?>(child: Text(l.transactions_allCategories)),
                for (final c in choosable)
                  DropdownMenuItem<String?>(
                    value: c.id,
                    child: Text(
                      c.parentName == null
                          ? c.name
                          : l.transactions_categoryPath(c.parentName!, c.name),
                    ),
                  ),
              ],
              onChanged: (id) => setState(
                // A category and "uncategorised only" contradict: the explicit choice wins.
                () => _draft = _draft.copyWith(
                  categoryId: () => id,
                  uncategorised: id == null && _draft.uncategorised,
                ),
              ),
            ),
            const SizedBox(height: SpaceTokens.s3),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () async {
                      final from = await _pick(_draft.from);
                      if (from != null) setState(() => _draft = _draft.copyWith(from: () => from));
                    },
                    child: Text(
                      _draft.from == null
                          ? l.transactions_from
                          : '${l.transactions_from} ${context.date(_draft.from!)}',
                    ),
                  ),
                ),
                const SizedBox(width: SpaceTokens.s2),
                Expanded(
                  child: OutlinedButton(
                    onPressed: () async {
                      final to = await _pick(_draft.to);
                      if (to != null) setState(() => _draft = _draft.copyWith(to: () => to));
                    },
                    child: Text(
                      _draft.to == null
                          ? l.transactions_to
                          : '${l.transactions_to} ${context.date(_draft.to!)}',
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: SpaceTokens.s4),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(_draft),
              child: Text(l.common_done),
            ),
            TextButton(
              onPressed: () => Navigator.of(context).pop(const LedgerQuery()),
              child: Text(l.transactions_clearFilters),
            ),
          ],
        ),
      ),
    );
  }
}
