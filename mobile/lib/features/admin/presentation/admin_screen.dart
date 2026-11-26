import 'dart:async';

import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/admin/data/admin_repository.dart';
import 'package:budget_owl/features/admin/domain/admin.dart';
import 'package:budget_owl/features/admin/domain/settings_patch.dart';
import 'package:budget_owl/features/household/presentation/section_card.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart' show DateFormat, NumberFormat;
import 'package:material_ui/material_ui.dart';

/// The operator's console (admin-console.md): this instance, how people sign in, and who can.
///
/// **Reached only by the instance administrator.** More lists it only when `/api/auth/me` says
/// `instanceAdmin`, and the router sends anybody else who opens `/more/admin` back to More — but
/// both are courtesies: every route here answers `403 not-instance-admin` to anybody else, a
/// household `OWNER` included, and this screen shows that refusal translated if it ever meets it.
///
/// It holds no financial data. Four behaviours are rules rather than layout, as on the web:
///
/// - **The client secret field is empty on every load and every save**, and is sent only when
///   something was typed into it. Whether a secret is set is said in words, never as dots.
/// - **While password sign-in cannot be turned off, no switch is offered**: the rule is stated
///   beside it. Turning it off, when allowed, names the recovery command first.
/// - **Turning the provider off is confirmed**, because the server switches password sign-in back on.
/// - **A change is diffed against the copy the form was filled from**, with that copy's `version`.
///   If somebody saved first, the `409` re-reads the server's copy, names the settings that moved
///   — names only, one of them is a secret — keeps what was typed, and waits for a choice.
class AdminScreen extends ConsumerStatefulWidget {
  const AdminScreen({super.key});

  @override
  ConsumerState<AdminScreen> createState() => _AdminScreenState();
}

class _AdminScreenState extends ConsumerState<AdminScreen> {
  final _form = GlobalKey<FormState>();
  final _issuer = TextEditingController();
  final _clientId = TextEditingController();
  final _secret = TextEditingController();
  bool _registrationOpen = false;
  bool _passwordLoginEnabled = true;
  bool _oidcEnabled = false;
  bool _provisioning = false;

  /// The server's copy the form was filled from: what a change is diffed against, and whose
  /// `version` it carries. Replaced only by a fill, never behind the form's back.
  InstanceSettings? _opened;

  /// Which settings somebody else changed, while a conflict waits for a decision.
  List<SettingsField>? _conflict;

  bool _saving = false;
  String? _notice;
  AppError? _error;

  @override
  void initState() {
    super.initState();
    final first = ref.read(settingsProvider).data;
    if (first != null) _fill(first);
    ref.listenManual(settingsProvider, (_, next) {
      final latest = next.data;
      // A newer copy (the cached one, then the server's) fills a form nobody has touched. A form
      // somebody is editing keeps what they typed; its save carries the version it was filled
      // from, so a change made elsewhere meanwhile is a 409, never silently undone.
      if (latest == null || _conflict != null || (_opened != null && _dirty)) return;
      setState(() => _fill(latest));
    });
  }

  @override
  void dispose() {
    _issuer.dispose();
    _clientId.dispose();
    _secret.dispose();
    super.dispose();
  }

  SigningInForm get _value => SigningInForm(
    registrationOpen: _registrationOpen,
    passwordLoginEnabled: _passwordLoginEnabled,
    oidcEnabled: _oidcEnabled,
    oidcIssuerUri: _issuer.text,
    oidcClientId: _clientId.text,
    oidcClientSecret: _secret.text,
    oidcProvisioningEnabled: _provisioning,
  );

  bool get _dirty {
    final opened = _opened;
    if (opened == null) return false;
    final now = _value;
    final then = SigningInForm.of(opened);
    return now.registrationOpen != then.registrationOpen ||
        now.passwordLoginEnabled != then.passwordLoginEnabled ||
        now.oidcEnabled != then.oidcEnabled ||
        now.oidcIssuerUri != then.oidcIssuerUri ||
        now.oidcClientId != then.oidcClientId ||
        now.oidcClientSecret.isNotEmpty ||
        now.oidcProvisioningEnabled != then.oidcProvisioningEnabled;
  }

  /// Fills the form from [settings], which becomes the copy changes are diffed against. The
  /// secret is emptied: there is no value to show, and none was ever sent here.
  void _fill(InstanceSettings settings) {
    final form = SigningInForm.of(settings);
    _opened = settings;
    _registrationOpen = form.registrationOpen;
    _passwordLoginEnabled = form.passwordLoginEnabled;
    _oidcEnabled = form.oidcEnabled;
    _issuer.text = form.oidcIssuerUri;
    _clientId.text = form.oidcClientId;
    _secret.clear();
    _provisioning = form.oidcProvisioningEnabled;
  }

  Future<void> _refresh() => Future.wait([
    ref.read(instanceProvider.notifier).refresh(),
    ref.read(settingsProvider.notifier).refresh(),
    ref.read(adminAccountsProvider.notifier).refresh(),
  ]);

  /// Save. Turning the provider off is confirmed first; everything else goes straight through.
  Future<void> _submit() async {
    final opened = _opened;
    if (opened == null || _saving || _conflict != null) return;
    if (!(_form.currentState?.validate() ?? false)) return;
    if (opened.oidcEnabled && !_oidcEnabled) {
      final l = context.l10n;
      final confirmed = await confirmAction(
        context,
        heading: l.admin_oidcOffHeading,
        message: l.admin_oidcOffMessage,
        action: l.admin_oidcOffConfirm,
        destructive: true,
      );
      if (!mounted) return;
      if (!confirmed) {
        // Nothing was sent, so the provider is still on: only that switch goes back, and
        // everything else that was typed stays.
        setState(() => _oidcEnabled = opened.oidcEnabled);
        return;
      }
    }
    await _save(opened);
  }

  Future<void> _save(InstanceSettings opened) async {
    final patch = buildSettingsPatch(opened, _value);
    setState(() {
      _notice = null;
      _error = null;
    });
    if (settingsPatchIsEmpty(patch)) {
      setState(() => _notice = context.l10n.admin_nothingChanged);
      return;
    }
    setState(() => _saving = true);
    try {
      final saved = await ref.read(adminWriterProvider).saveSettings(patch);
      if (!mounted) return;
      setState(() {
        _fill(saved);
        _notice = context.l10n.admin_saved;
      });
    } on AppError catch (error) {
      if (!mounted) return;
      if (error.status == 409 && error.code == 'stale-version') {
        await _explainConflict(opened);
      } else if (error.status == 409 && error.code == 'password-login-locked') {
        // The rule was met between the read and the save: say so, and show what is true now.
        setState(() => _error = error);
        await ref.read(settingsProvider.notifier).refresh();
        final latest = ref.read(settingsProvider).data;
        if (mounted && latest != null) setState(() => _fill(latest));
      } else {
        setState(() => _error = error);
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  /// Somebody else saved first. Re-read, name what moved, and stop: the next move is the
  /// administrator's. What they typed stays, and nothing has been written.
  Future<void> _explainConflict(InstanceSettings opened) async {
    await ref.read(settingsProvider.notifier).refresh();
    if (!mounted) return;
    final snapshot = ref.read(settingsProvider);
    final latest = snapshot.data;
    if (snapshot.error != null || latest == null || latest.version == opened.version) {
      // The re-read failed too. Say that rather than inventing a list of what changed.
      setState(() => _error = snapshot.error ?? const AppError.unexpected());
      return;
    }
    setState(() {
      _conflict = differingSettings(opened, latest);
      // "Save mine anyway" sends against the copy the person has now been told about.
      _opened = latest;
    });
  }

  void _takeLatest() {
    final latest = _opened;
    setState(() {
      _conflict = null;
      if (latest != null) _fill(latest);
    });
  }

  Future<void> _keepMine() async {
    setState(() => _conflict = null);
    // Through submit, so a conflict cannot become the one route that turns the provider off
    // without the confirmation.
    await _submit();
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final instance = ref.watch(instanceProvider);
    final settings = ref.watch(settingsProvider);
    final accounts = ref.watch(adminAccountsProvider);
    final myUserId = ref.watch(signedInProvider)?.user.id ?? '';
    return Screen(
      title: l.admin_title,
      onRefresh: _refresh,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.admin_lede),
          SnapshotView(
            snapshot: instance,
            onRetry: () => unawaited(ref.read(instanceProvider.notifier).refresh()),
            builder: (context, info, stale) => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (stale != null) Gutter(child: stale),
                _InstanceFacts(info: info),
              ],
            ),
          ),
          if (instance.hasData)
            SnapshotView(
              snapshot: settings,
              onRetry: () => unawaited(ref.read(settingsProvider.notifier).refresh()),
              builder: (context, _, stale) => Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (stale != null && !instance.isStale) Gutter(child: stale),
                  if (_opened != null) _signingIn(context, _opened!),
                ],
              ),
            ),
          if (instance.hasData)
            SnapshotView(
              snapshot: accounts,
              onRetry: () => unawaited(ref.read(adminAccountsProvider.notifier).refresh()),
              builder: (context, list, _) => _Accounts(accounts: list, myUserId: myUserId),
            ),
        ],
      ),
    );
  }

  Widget _signingIn(BuildContext context, InstanceSettings opened) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final online = canWriteNow(ref);
    final error = _error;
    final notice = _notice;
    final conflict = _conflict;
    final turningOidcOff = opened.oidcEnabled && !_oidcEnabled;
    final turningPasswordOff = opened.passwordLoginEnabled && !_passwordLoginEnabled;
    final openingRegistration = !opened.registrationOpen && _registrationOpen;
    final proven = opened.oidcOwnerLoginAt;
    const gap = SizedBox(height: SpaceTokens.s3);

    Widget group(String legend, List<Widget> children) => Padding(
      padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Semantics(header: true, child: Text(legend, style: text.titleSmall)),
          const SizedBox(height: SpaceTokens.s1),
          ...children,
        ],
      ),
    );

    return SectionCard(
      title: l.admin_signingInHeading,
      children: [
        if (notice != null) ...[NoticeBanner(text: notice, success: true), gap],
        if (conflict != null) ...[_ConflictNotice(changed: conflict, l: l), gap],
        if (conflict != null)
          Wrap(
            spacing: SpaceTokens.s2,
            runSpacing: SpaceTokens.s2,
            children: [
              OutlinedButton(onPressed: _takeLatest, child: Text(l.admin_useLatest)),
              OutlinedButton(
                onPressed: online ? () => unawaited(_keepMine()) : null,
                child: Text(l.admin_keepMine),
              ),
            ],
          ),
        if (error != null) ...[NoticeBanner(text: writeErrorText(context, ref, error)), gap],
        Form(
          key: _form,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              group(l.admin_registrationLegend, [
                SwitchListTile(
                  contentPadding: EdgeInsetsDirectional.zero,
                  title: Text(l.admin_registrationSwitch),
                  value: _registrationOpen,
                  onChanged: (on) => setState(() => _registrationOpen = on),
                ),
                // In the flow and at full size, never a tooltip: this is the one setting here
                // that can let a stranger in, and the words are the control.
                NoticeBanner(
                  text: openingRegistration
                      ? '${l.admin_registrationConsequence}\n\n${l.admin_registrationOpening}'
                      : l.admin_registrationConsequence,
                  icon: Icons.warning_amber_outlined,
                ),
              ]),
              group(l.admin_passwordLegend, [
                if (opened.offerPasswordSwitch) ...[
                  SwitchListTile(
                    contentPadding: EdgeInsetsDirectional.zero,
                    title: Text(l.admin_passwordSwitch),
                    value: _passwordLoginEnabled,
                    onChanged: (on) => setState(() => _passwordLoginEnabled = on),
                  ),
                  Hint(l.admin_passwordHint),
                  if (turningPasswordOff) ...[gap, _RecoveryNotice(l: l)],
                ] else ...[
                  Text(l.admin_passwordOn, style: text.bodyMedium),
                  gap,
                  NoticeBanner(
                    text: '${l.admin_passwordLockedTitle}\n${l.admin_passwordLockedRule}',
                    icon: Icons.lock_outline,
                  ),
                ],
              ]),
              group(l.admin_oidcLegend, [
                Hint(l.admin_oidcLede),
                SwitchListTile(
                  contentPadding: EdgeInsetsDirectional.zero,
                  title: Text(l.admin_oidcSwitch),
                  value: _oidcEnabled,
                  onChanged: (on) => setState(() => _oidcEnabled = on),
                ),
                if (turningOidcOff) NoticeBanner(text: l.admin_oidcOffWarning),
                if (opened.oidcEnabled && !turningOidcOff)
                  Hint(
                    proven == null
                        ? l.admin_oidcNotProven
                        : l.admin_oidcProven(_dateTime(context, proven)),
                  ),
                if (_oidcEnabled) ..._providerFields(context, opened),
              ]),
              gap,
              Align(
                alignment: AlignmentDirectional.centerStart,
                child: FilledButton(
                  onPressed: _saving || conflict != null || !online
                      ? null
                      : () => unawaited(_submit()),
                  child: Text(_saving ? l.common_saving : l.common_save),
                ),
              ),
              const OfflineNote(),
            ],
          ),
        ),
      ],
    );
  }

  List<Widget> _providerFields(BuildContext context, InstanceSettings opened) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    const gap = SizedBox(height: SpaceTokens.s3);
    return [
      gap,
      TextFormField(
        controller: _issuer,
        keyboardType: TextInputType.url,
        autocorrect: false,
        enableSuggestions: false,
        maxLength: AdminLimits.issuer,
        decoration: InputDecoration(
          labelText: l.admin_oidcIssuer,
          helperText: l.admin_oidcIssuerHint,
          helperMaxLines: 12,
        ),
        validator: (value) {
          final typed = value ?? '';
          if (typed.trim().isEmpty) return l.validation_required;
          return isIssuerUrl(typed) ? null : l.validation_issuerUrl;
        },
        onChanged: (_) => setState(() {}),
      ),
      gap,
      TextFormField(
        controller: _clientId,
        autocorrect: false,
        enableSuggestions: false,
        maxLength: AdminLimits.clientId,
        decoration: InputDecoration(
          labelText: l.admin_oidcClientId,
          helperText: l.admin_oidcClientIdHint,
          helperMaxLines: 12,
        ),
        validator: (value) => (value ?? '').trim().isEmpty ? l.validation_required : null,
        onChanged: (_) => setState(() {}),
      ),
      gap,
      // Empty on every load and every save, and bound to nothing but its controller: there is no
      // stored value to show. Obscured, never suggested, never autofilled.
      TextFormField(
        controller: _secret,
        obscureText: true,
        autocorrect: false,
        enableSuggestions: false,
        keyboardType: TextInputType.visiblePassword,
        maxLength: AdminLimits.secret,
        // No character counter: on a secret it would say how long the typed one is.
        decoration: InputDecoration(labelText: l.admin_oidcSecret, counterText: ''),
        validator: (value) => !opened.oidcClientSecretSet && (value ?? '').trim().isEmpty
            ? l.validation_required
            : null,
        onChanged: (_) => setState(() {}),
      ),
      // Said in words, never as dots standing in for a value this screen does not have.
      Text(
        opened.oidcClientSecretSet ? l.admin_secretSet : l.admin_secretNotSet,
        style: text.bodyMedium,
      ),
      Hint(l.admin_oidcSecretHint),
      gap,
      SwitchListTile(
        contentPadding: EdgeInsetsDirectional.zero,
        title: Text(l.admin_oidcProvisioning),
        value: _provisioning,
        onChanged: (on) => setState(() => _provisioning = on),
      ),
      Hint(l.admin_oidcProvisioningHint),
    ];
  }
}

String _dateTime(BuildContext context, DateTime at) =>
    DateFormat.yMMMd(context.localeName).add_jm().format(at.toLocal());

/// Version, when setup finished, who the administrator is, how many accounts exist. A count of
/// people — nothing on this screen is a figure of anybody's money (ADR-0026).
class _InstanceFacts extends StatelessWidget {
  const _InstanceFacts({required this.info});

  final InstanceInfo info;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final setup = info.setupCompletedAt;
    Widget fact(String label, String value, [String? sub]) => Padding(
      padding: const EdgeInsetsDirectional.only(bottom: SpaceTokens.s3),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: text.labelMedium?.copyWith(color: c.muted)),
          Text(value, style: text.bodyLarge),
          if (sub != null) Text(sub, style: text.bodySmall?.copyWith(color: c.muted)),
        ],
      ),
    );
    return SectionCard(
      title: l.admin_instanceHeading,
      children: [
        fact(l.admin_versionLabel, info.version),
        fact(
          l.admin_setupLabel,
          setup == null ? l.admin_setupUnrecorded : _dateTime(context, setup),
        ),
        fact(l.admin_administratorLabel, info.administratorName, info.administratorEmail),
        fact(
          l.admin_accountCountLabel,
          NumberFormat.decimalPattern(context.localeName).format(info.accountCount),
        ),
      ],
    );
  }
}

/// Said before it happens, naming the command: a recovery path nobody can find at 11pm is not a
/// recovery path.
///
/// The command has a line of its own that **never wraps** — it scrolls sideways instead, at any
/// text size — because it has hyphens in it, and a reader cannot tell a hyphen in the filename
/// from one a line break added. It is the one string on the screen that has to be unambiguous.
class _RecoveryNotice extends StatefulWidget {
  const _RecoveryNotice({required this.l});

  final AppLocalizations l;

  @override
  State<_RecoveryNotice> createState() => _RecoveryNoticeState();
}

class _RecoveryNoticeState extends State<_RecoveryNotice> {
  final _scroll = ScrollController();

  @override
  void dispose() {
    _scroll.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l = widget.l;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        NoticeBanner(text: l.admin_passwordOffRecovery, icon: Icons.warning_amber_outlined),
        const SizedBox(height: SpaceTokens.s2),
        Container(
          padding: const EdgeInsetsDirectional.fromSTEB(
            SpaceTokens.s3,
            SpaceTokens.s3,
            SpaceTokens.s3,
            SpaceTokens.s2,
          ),
          decoration: BoxDecoration(
            color: c.surface,
            borderRadius: BorderRadius.circular(RadiusTokens.sm),
            border: Border.all(color: c.divider),
          ),
          // A visible scrollbar whenever the line is wider than the box, so a cut-off command at
          // large text sizes says that it continues.
          child: Scrollbar(
            controller: _scroll,
            thumbVisibility: true,
            child: SingleChildScrollView(
              controller: _scroll,
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsetsDirectional.only(bottom: SpaceTokens.s3),
              child: SelectableText(
                l.admin_passwordOffRecoveryCommand,
                textDirection: TextDirection.ltr,
                maxLines: 1,
                style: text.bodyMedium?.copyWith(fontWeight: FontWeight.w700),
              ),
            ),
          ),
        ),
        Hint(l.admin_passwordOffRecoveryAfter),
      ],
    );
  }
}

/// "Somebody else saved first": what moved, by name only, and nothing saved.
class _ConflictNotice extends StatelessWidget {
  const _ConflictNotice({required this.changed, required this.l});

  final List<SettingsField> changed;
  final AppLocalizations l;

  String _label(SettingsField field) => switch (field) {
    SettingsField.registrationOpen => l.admin_field_registrationOpen,
    SettingsField.passwordLoginEnabled => l.admin_field_passwordLoginEnabled,
    SettingsField.oidcEnabled => l.admin_field_oidcEnabled,
    SettingsField.oidcIssuerUri => l.admin_field_oidcIssuerUri,
    SettingsField.oidcClientId => l.admin_field_oidcClientId,
    SettingsField.oidcClientSecret => l.admin_field_oidcClientSecret,
    SettingsField.oidcProvisioningEnabled => l.admin_field_oidcProvisioningEnabled,
  };

  @override
  Widget build(BuildContext context) {
    final lines = [
      l.admin_conflictTitle,
      l.admin_conflictMessage,
      if (changed.isEmpty)
        l.admin_conflictNothingVisible
      else ...[
        l.admin_conflictChanged,
        for (final field in changed) '•  ${_label(field)}',
      ],
    ];
    return NoticeBanner(text: lines.join('\n'), icon: Icons.sync_problem_outlined);
  }
}

/// Who can sign in, their status and when they joined. Read-only: suspending or removing an
/// account is out of scope (admin-console.md).
class _Accounts extends StatelessWidget {
  const _Accounts({required this.accounts, required this.myUserId});

  final List<AdminAccount> accounts;
  final String myUserId;

  String _status(AppLocalizations l, String status) => switch (status) {
    'ACTIVE' => l.admin_status_ACTIVE,
    'DISABLED' => l.admin_status_DISABLED,
    _ => l.admin_status_unknown,
  };

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final dates = DateFormat.yMMMd(context.localeName);
    return SectionCard(
      title: l.admin_accountsHeading,
      children: [
        Hint(l.admin_accountsLede),
        const SizedBox(height: SpaceTokens.s2),
        if (accounts.isEmpty) Text(l.admin_accountsEmpty, style: text.bodyMedium),
        for (final (index, account) in accounts.indexed) ...[
          if (index > 0) const Divider(),
          Padding(
            padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s2),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Wrap(
                  spacing: SpaceTokens.s2,
                  runSpacing: SpaceTokens.s1,
                  crossAxisAlignment: WrapCrossAlignment.center,
                  children: [
                    Text(account.displayName, style: text.titleSmall),
                    if (account.id == myUserId) Tag(l.common_you, strong: true),
                  ],
                ),
                Text(account.email, style: text.bodySmall?.copyWith(color: c.muted)),
                Text(
                  l.admin_joined(dates.format(account.createdAt.toLocal())),
                  style: text.bodySmall?.copyWith(color: c.muted),
                ),
                const SizedBox(height: SpaceTokens.s1),
                Wrap(
                  spacing: SpaceTokens.s2,
                  runSpacing: SpaceTokens.s1,
                  children: [
                    if (account.instanceAdmin) Tag(l.admin_administratorTag, strong: true),
                    Tag(_status(l, account.status)),
                  ],
                ),
              ],
            ),
          ),
        ],
      ],
    );
  }
}
