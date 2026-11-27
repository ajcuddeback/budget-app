import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/accounts/data/accounts.dart';
import 'package:budget_owl/features/import/data/imports_repository.dart';
import 'package:budget_owl/features/import/data/statement_file.dart';
import 'package:budget_owl/features/import/domain/import.dart';
import 'package:budget_owl/features/import/presentation/import_mapping.dart';
import 'package:budget_owl/features/import/presentation/import_preview.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

/// Import a statement: choose the account and the file, say how to read it, look at what will be
/// added, confirm (file-import.md, UI).
///
/// - **The preview is the confirmation** (rule 1): it writes nothing, and the commit resends
///   **the request that was previewed**, never the form as it is now. A setting changed since
///   marks the preview out of date, and the button waits for Update preview.
/// - **The file stays in memory**: picked with the system picker, its bytes read and the
///   platform's copy deleted at once (statement_file.dart), held here as base64 and dropped after
///   the commit. Its name is never sent.
/// - **Nothing here parses an amount**: the counts are integers, every figure the server's.
/// - **The role decides what is offered**: a `VIEWER` gets a sentence, not a form, and would be
///   refused `403` before the server decoded a byte of the file.
class ImportScreen extends ConsumerStatefulWidget {
  const ImportScreen({super.key});

  @override
  ConsumerState<ImportScreen> createState() => _ImportScreenState();
}

enum _Busy { preview, commit, undo }

class _ImportScreenState extends ConsumerState<ImportScreen> {
  String? _accountId;
  ChosenFile? _file;
  String? _fileProblem;
  ImportFormat? _format;
  ImportSettings _settings = const ImportSettings();
  bool _mapped = false;
  bool _fromSaved = false;
  ImportPreview? _preview;
  ImportRequest? _previewed;
  bool _outOfDate = false;
  Set<int> _included = {};
  bool _includeAll = false;
  bool _saveMapping = false;
  _Busy? _busy;
  AppError? _error;
  int? _staleDuplicates;
  ImportBatch? _done;
  int? _undone;

  bool get _online => ref.read(reachabilityProvider).canWrite;

  Account? _selected(List<Account> open) {
    for (final account in open) {
      if (account.id == _accountId) return account;
    }
    return null;
  }

  void _resetPreview() {
    _preview = null;
    _previewed = null;
    _outOfDate = false;
    _mapped = false;
    _fromSaved = false;
    _included = {};
    _includeAll = false;
    _saveMapping = false;
    _error = null;
    _staleDuplicates = null;
  }

  /// Drops the file and everything read from it.
  void _forgetFile() {
    _file = null;
    _settings = const ImportSettings();
    _resetPreview();
  }

  Future<void> _pick() async {
    final l = context.l10n;
    PickedStatement? picked;
    try {
      picked = await ref.read(statementPickerProvider).pick();
    } on Object {
      if (mounted) setState(() => _fileProblem = l.import_readFailedPhone);
      return;
    }
    if (picked == null || !mounted) return;
    final statement = picked;
    setState(() {
      _forgetFile();
      _done = null;
      _undone = null;
      _fileProblem = null;
      // The size check is a courtesy; the server's is the control (rule 3).
      if (statement.bytes.length > maxFileBytes) {
        _fileProblem = l.import_tooLarge;
        return;
      }
      _file = ChosenFile(statement.bytes);
      _format = formatFromName(statement.name) ?? _format;
    });
  }

  Future<void> _runPreview({bool update = false}) async {
    final file = _file;
    final format = _format;
    final account = _accountId;
    if (file == null || format == null || account == null) return;
    if (update && format == ImportFormat.csv) _mapped = true;
    final request = previewRequest(
      accountId: account,
      format: format,
      file: file,
      options: optionsFor(format, _settings, mapped: _mapped),
    );
    await _previewWith(request);
  }

  Future<void> _previewWith(ImportRequest request, {ImportPreview? before}) async {
    setState(() {
      _busy = _Busy.preview;
      _error = null;
      if (before == null) _staleDuplicates = null;
    });
    try {
      final writer = ref.read(importsWriterProvider);
      final preview = await writer.preview(request);
      if (preview.csv != null && !_mapped) {
        final saved = await writer.mapping(request['accountId']! as String);
        _settings = settingsFrom(preview.csv!, saved, _settings);
        _fromSaved = saved != null;
      }
      if (_settings.charset.isEmpty && preview.suggestedCharset != null) {
        _settings = _settings.copyWith(charset: preview.suggestedCharset);
      }
      if (!mounted) return;
      setState(() {
        _preview = preview;
        _previewed = request;
        _outOfDate = false;
        _included = {};
        _includeAll = false;
        final was = before?.counts;
        final now = preview.counts;
        if (was != null && now != null) {
          final more = now.duplicate - was.duplicate;
          _staleDuplicates = more > 0 ? more : 0;
        }
      });
    } on AppError catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  Future<void> _commit() async {
    final preview = _preview;
    final previewed = _previewed;
    final digest = preview?.previewDigest;
    if (preview == null || previewed == null || digest == null || _outOfDate) return;
    final request = commitRequestFor(
      previewed,
      digest,
      decisionsFor(preview.attention, _included, includeAll: _includeAll),
      saveMapping: _saveMapping,
    );
    setState(() {
      _busy = _Busy.commit;
      _error = null;
      _staleDuplicates = null;
    });
    try {
      final batch = await ref.read(importsWriterProvider).commit(request);
      if (!mounted) return;
      setState(() {
        _done = batch;
        _forgetFile();
      });
    } on AppError catch (error) {
      if (!mounted) return;
      setState(() => _busy = null);
      if (error.status == 409 && error.code == 'import-preview-stale') {
        // Somebody imported or typed rows in between. Nothing was written: preview the same
        // request again and say what moved, rather than commit something nobody looked at.
        await _previewWith(previewed, before: preview);
        return;
      }
      setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  Future<void> _undo(ImportBatch batch) async {
    final l = context.l10n;
    final confirmed = await confirmAction(
      context,
      heading: l.import_undoHeading,
      // Names the count and the account, and the links that go with the rows (decision 5).
      message: l.import_undoMessage(_count(batch.removable), batch.accountName),
      action: l.import_undo,
      destructive: true,
    );
    if (!confirmed || !mounted) return;
    setState(() {
      _busy = _Busy.undo;
      _error = null;
    });
    try {
      await ref.read(importsWriterProvider).undo(batch);
      if (!mounted) return;
      setState(() {
        _undone = batch.removable;
        if (_done?.id == batch.id) _done = null;
      });
    } on AppError catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  void _startOver() => setState(() {
    _forgetFile();
    _format = null;
    _done = null;
    _undone = null;
    _error = null;
  });

  String _count(int value) => NumberFormat.decimalPattern(context.localeName).format(value);

  String _label(Account account) => account.lastFour == null
      ? account.name
      : context.l10n.accounts_nameWithDigits(account.name, account.lastFour!);

  // --------------------------------------------------------------------------------- build

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final canWrite = ref.watch(signedInProvider)?.membership?.role.canWrite ?? false;
    if (!canWrite) {
      // Not a form that will be refused: a viewer is told plainly. The server's 403 is the
      // control; this is the courtesy. Nothing is fetched for it.
      return Screen(
        title: l.import_title,
        body: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Lede(l.import_lede),
            _Section(title: l.import_viewerHeading, children: [Text(l.import_viewerBody)]),
          ],
        ),
      );
    }
    final accounts = ref.watch(accountsProvider);
    // Watched so the offline notes and the disabled buttons follow the connection.
    ref.watch(reachabilityProvider);
    return Screen(
      title: l.import_title,
      onRefresh: () => Future.wait([
        ref.read(accountsProvider.notifier).refresh(),
        ref.read(importHistoryProvider.notifier).refresh(),
      ]),
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.import_lede),
          SnapshotView<List<Account>>(
            snapshot: accounts,
            onRetry: () => ref.read(accountsProvider.notifier).refresh(),
            builder: (context, all, stale) {
              final open = all.where((a) => !a.archived).toList();
              return Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  ?(stale == null ? null : Gutter(child: stale)),
                  if (open.isEmpty)
                    _Section(
                      children: [
                        Text(l.import_noAccountsBody),
                        _Action(l.import_goToAccounts, () => context.go('/accounts')),
                      ],
                    )
                  else if (_done != null)
                    _doneCard(context, _done!, open)
                  else ...[
                    if (_undone != null)
                      Gutter(
                        child: NoticeBanner(text: l.import_undone(_count(_undone!)), success: true),
                      ),
                    _fileStep(context, open),
                    if (_preview case final preview?) ...[
                      if (preview.format == ImportFormat.csv || preview.needs.isNotEmpty)
                        _Section(
                          number: 2,
                          title: l.import_stepMapping,
                          children: [
                            ImportMappingView(
                              preview: preview,
                              settings: _settings,
                              fromSaved: _fromSaved,
                              busy: _busy != null || !_online,
                              onChanged: (next) => setState(() {
                                _settings = next;
                                _outOfDate = true;
                              }),
                              onUpdate: () => _runPreview(update: true),
                            ),
                            if (preview.counts == null) ?_errorBanner(context),
                          ],
                        ),
                      if (preview.counts != null) _previewStep(context, preview, open),
                    ],
                  ],
                  _history(context),
                ],
              );
            },
          ),
        ],
      ),
    );
  }

  Widget? _errorBanner(BuildContext context) {
    final error = _error;
    if (error == null) return null;
    return Padding(
      padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
      child: NoticeBanner(
        icon: Icons.error_outline,
        text: error.isConnectivity
            ? writesOffText(context.l10n, ref.read(reachabilityProvider))
            : context.errorMessage(error),
      ),
    );
  }

  Widget _fileStep(BuildContext context, List<Account> open) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final selected = _selected(open);
    final canPreview =
        selected != null && _file != null && _format != null && _busy == null && _online;
    return _Section(
      number: 1,
      title: l.import_stepFile,
      children: [
        DropdownButtonFormField<String>(
          initialValue: selected?.id,
          isExpanded: true,
          decoration: InputDecoration(labelText: l.import_account),
          hint: Text(l.import_chooseAccount),
          items: [
            for (final account in open)
              DropdownMenuItem(value: account.id, child: Text(_label(account))),
          ],
          onChanged: (id) => setState(() {
            _accountId = id;
            _resetPreview();
          }),
        ),
        const SizedBox(height: SpaceTokens.s3),
        Text(l.import_file, style: text.titleSmall),
        Text(l.import_fileHint, style: text.bodySmall?.copyWith(color: context.colors.muted)),
        const SizedBox(height: SpaceTokens.s2),
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: OutlinedButton.icon(
            onPressed: _busy == null ? _pick : null,
            icon: const Icon(Icons.upload_file),
            label: Text(l.import_chooseFile),
          ),
        ),
        if (_file != null)
          Padding(
            padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
            child: Text(
              l.import_chosenFile(l.import_kilobytes(_count((_file!.size + 1023) ~/ 1024))),
              style: text.bodySmall,
            ),
          ),
        if (_fileProblem != null)
          Padding(
            padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
            child: NoticeBanner(text: _fileProblem!, icon: Icons.error_outline),
          ),
        const SizedBox(height: SpaceTokens.s3),
        DropdownButtonFormField<ImportFormat>(
          key: ValueKey(_format),
          initialValue: _format,
          isExpanded: true,
          decoration: InputDecoration(
            labelText: l.import_format,
            helperText: l.import_formatHint,
            helperMaxLines: 4,
          ),
          hint: Text(l.import_chooseFormat),
          items: [
            for (final format in ImportFormat.values)
              DropdownMenuItem(value: format, child: Text(formatLabel(l, format))),
          ],
          onChanged: (format) => setState(() {
            _format = format;
            _settings = const ImportSettings();
            _resetPreview();
          }),
        ),
        if (_preview == null) ...[
          ?_errorBanner(context),
          const SizedBox(height: SpaceTokens.s3),
          Align(
            alignment: AlignmentDirectional.centerStart,
            child: FilledButton(
              onPressed: canPreview ? _runPreview : null,
              child: Text(_busy == _Busy.preview ? l.import_working : l.import_preview),
            ),
          ),
          Text(
            l.import_nothingWritten,
            style: text.bodySmall?.copyWith(color: context.colors.muted),
          ),
          const OfflineNote(),
        ],
      ],
    );
  }

  Widget _previewStep(BuildContext context, ImportPreview preview, List<Account> open) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final selected = _selected(open);
    final account = preview.accountLastFour == null
        ? preview.accountName
        : l.accounts_nameWithDigits(preview.accountName, preview.accountLastFour!);
    final count = rowsToImport(preview.counts!.fresh, preview.attention, _included);
    final ready = preview.previewDigest != null && preview.needs.isEmpty && !_outOfDate;
    final showsMapping = preview.format == ImportFormat.csv || preview.needs.isNotEmpty;
    return _Section(
      number: showsMapping ? 3 : 2,
      title: l.import_stepPreview,
      children: [
        if (_staleDuplicates != null)
          NoticeBanner(
            icon: Icons.info_outline,
            text: [
              l.import_stale,
              if (_staleDuplicates! > 0) l.import_staleDuplicates(_count(_staleDuplicates!)),
            ].join('\n'),
          ),
        Opacity(
          opacity: _outOfDate ? 0.6 : 1,
          child: ImportPreviewView(
            preview: preview,
            included: _included,
            accountLabel: account,
            onToggle: (row) => setState(() {
              if (!_included.remove(row)) _included.add(row);
            }),
            onIncludeAll: () => setState(() {
              _included = suspectedRows(preview.attention).toSet();
              _includeAll = true;
            }),
            onExcludeAll: () => setState(() {
              _included = {};
              _includeAll = false;
            }),
          ),
        ),
        ?_errorBanner(context),
        if (preview.format == ImportFormat.csv && !_outOfDate)
          CheckboxListTile(
            contentPadding: EdgeInsetsDirectional.zero,
            controlAffinity: ListTileControlAffinity.leading,
            value: _saveMapping,
            onChanged: (value) => setState(() => _saveMapping = value ?? false),
            title: Text(l.import_saveMapping),
            subtitle: Text(l.import_saveMappingHint),
          ),
        if (_outOfDate)
          Text(l.import_outOfDate, style: text.bodyMedium?.copyWith(color: context.colors.muted)),
        const SizedBox(height: SpaceTokens.s3),
        // The preview is the confirmation: the button says what it does, to which account, and
        // there is no second dialog.
        FilledButton(
          onPressed: ready && count > 0 && _busy == null && _online && selected != null
              ? _commit
              : null,
          child: Text(
            _busy == _Busy.commit ? l.import_committing : l.import_confirm(_count(count), account),
          ),
        ),
        TextButton(onPressed: _startOver, child: Text(l.import_startOver)),
        const OfflineNote(),
      ],
    );
  }

  Widget _doneCard(BuildContext context, ImportBatch batch, List<Account> open) {
    final l = context.l10n;
    Account? account;
    for (final a in open) {
      if (a.id == batch.accountId) account = a;
    }
    return _Section(
      children: [
        NoticeBanner(
          success: true,
          text: [
            l.import_done(
              _count(batch.imported),
              account == null ? batch.accountName : _label(account),
            ),
            l.import_doneDetail,
          ].join('\n'),
        ),
        const SizedBox(height: SpaceTokens.s3),
        Wrap(
          spacing: SpaceTokens.s2,
          runSpacing: SpaceTokens.s2,
          children: [
            FilledButton(
              onPressed: () => context.go('/transactions'),
              child: Text(l.import_viewLedger),
            ),
            OutlinedButton(onPressed: _startOver, child: Text(l.import_another)),
            if (batch.removable > 0)
              OutlinedButton(
                style: OutlinedButton.styleFrom(
                  foregroundColor: context.colors.accentText,
                  side: BorderSide(color: context.colors.accentText),
                ),
                onPressed: _busy == null && _online ? () => _undo(batch) : null,
                child: Text(l.import_undo),
              ),
          ],
        ),
        ?_errorBanner(context),
        const OfflineNote(),
      ],
    );
  }

  Widget _history(BuildContext context) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final history = ref.watch(importHistoryProvider);
    final batches = history.data ?? const <ImportBatch>[];
    return _Section(
      title: l.import_history,
      children: [
        if (history.isStale) StaleBanner(fetchedAt: history.fetchedAt!, error: history.error!),
        if (batches.isEmpty)
          Text(l.import_historyEmpty, style: text.bodyMedium?.copyWith(color: context.colors.muted))
        else
          for (final batch in batches)
            Padding(
              padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s2),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          l.import_historyRow(
                            batch.accountName,
                            formatLabel(l, batch.format),
                            DateFormat.yMMMd(context.localeName)
                                .add_jm()
                                .format(batch.createdAt.toLocal()),
                          ),
                          style: text.titleSmall,
                        ),
                        Text(
                          l.import_historyCounts(
                            _count(batch.imported),
                            _count(batch.duplicate),
                            _count(batch.excluded),
                            _count(batch.unreadable),
                          ),
                          style: text.bodySmall?.copyWith(color: context.colors.muted),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: SpaceTokens.s2),
                  if (batch.removable > 0)
                    Semantics(
                      label: '${l.import_historyUndo} ${batch.accountName}',
                      button: true,
                      excludeSemantics: true,
                      child: OutlinedButton(
                        onPressed: _busy == null && _online ? () => _undo(batch) : null,
                        child: Text(l.import_historyUndo),
                      ),
                    )
                  else
                    Text(
                      l.import_historyNothingLeft,
                      style: text.bodySmall?.copyWith(color: context.colors.muted),
                    ),
                ],
              ),
            ),
      ],
    );
  }
}

String formatLabel(AppLocalizations l, ImportFormat format) => switch (format) {
  ImportFormat.csv => l.import_format_CSV,
  ImportFormat.ofx => l.import_format_OFX,
  ImportFormat.qif => l.import_format_QIF,
};

class _Section extends StatelessWidget {
  const _Section({required this.children, this.title, this.number});

  final String? title;

  /// The step's number, drawn as a badge beside its title.
  final int? number;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Gutter(
      child: Card(
        child: Padding(
          padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (title != null) ...[
                Row(
                  children: [
                    if (number != null) ...[
                      ExcludeSemantics(
                        child: CircleAvatar(
                          radius: SpaceTokens.s3,
                          backgroundColor: context.colors.primary,
                          foregroundColor: context.colors.onPrimary,
                          child: Text(
                            NumberFormat.decimalPattern(context.localeName).format(number),
                            style: text.labelLarge?.copyWith(color: context.colors.onPrimary),
                          ),
                        ),
                      ),
                      const SizedBox(width: SpaceTokens.s2),
                    ],
                    Expanded(
                      child: Semantics(header: true, child: Text(title!, style: text.titleLarge)),
                    ),
                  ],
                ),
                const SizedBox(height: SpaceTokens.s3),
              ],
              ...children,
            ],
          ),
        ),
      ),
    );
  }
}

class _Action extends StatelessWidget {
  const _Action(this.label, this.onPressed);

  final String label;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s3),
    child: Align(
      alignment: AlignmentDirectional.centerStart,
      child: FilledButton(onPressed: onPressed, child: Text(label)),
    ),
  );
}
