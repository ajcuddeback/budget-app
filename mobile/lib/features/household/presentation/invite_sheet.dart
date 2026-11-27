import 'dart:async';

import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/platform/sensitive_clipboard.dart';
import 'package:budget_owl/core/session/current_user.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/household/data/household_repository.dart';
import 'package:budget_owl/features/household/domain/household.dart';
import 'package:budget_owl/features/household/presentation/section_card.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:budget_owl/shared/widgets/validation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart' show DateFormat;
import 'package:material_ui/material_ui.dart';

/// Opens the invitation sheet above the tab bar, on the root navigator.
Future<void> showInviteSheet(BuildContext context) => Navigator.of(
  context,
  rootNavigator: true,
).push<void>(MaterialPageRoute(fullscreenDialog: true, builder: (_) => const InviteSheet()));

/// Creates an invitation and shows the link to share (the web's `InviteDialogComponent`).
///
/// **The link is a credential.** Possession of it is all that joining needs, so:
///
/// - it is shown **once** — the server keeps only a hash and cannot show it again — and held in
///   this sheet's state only. Closing the sheet drops it; nothing caches, stores or logs it (the
///   response goes through `ApiClient.write`, which is never cached, and the debug log line is a
///   method, a path and a status);
/// - it is built from the server this phone is connected to, never from anything the server said
///   about its own address ([buildInviteLink]);
/// - it reaches the clipboard **only when the owner taps "Copy link"**: nothing copies it on its
///   own, and it is selectable for anybody who would rather copy it by hand. It is copied
///   **marked sensitive** (Android 13+), and **taken back** — if the clipboard still holds it —
///   when it is revoked and when the sheet closes ([SensitiveClipboard], round 4 L2);
/// - the sheet says what the link is — "treat this link like a password" — with the role it
///   grants and when it expires, and the owner can revoke it before anybody uses it.
class InviteSheet extends ConsumerStatefulWidget {
  const InviteSheet({super.key});

  @override
  ConsumerState<InviteSheet> createState() => _InviteSheetState();
}

class _InviteSheetState extends ConsumerState<InviteSheet> {
  final _form = GlobalKey<FormState>();
  final _email = TextEditingController();
  Role _role = Role.member;

  Invitation? _invitation;
  String? _link;
  bool _revoked = false;
  bool _copied = false;
  bool _busy = false;
  AppError? _error;

  /// The last link this sheet put on the clipboard, and the clipboard it used: [dispose] cannot
  /// read providers, so it is kept from the copy.
  String? _onClipboard;
  SensitiveClipboard? _clipboard;

  @override
  void dispose() {
    final link = _onClipboard;
    if (link != null) unawaited(_clipboard?.clearIf(link));
    _email.dispose();
    super.dispose();
  }

  Future<void> _create() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    final server = ref.read(signedInProvider)?.server;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final made = await ref.read(householdWriterProvider).invite(email: _email.text, role: _role);
      if (!mounted) return;
      setState(() {
        _invitation = made;
        _link = server == null ? null : buildInviteLink(made.acceptPath, server);
        _copied = false;
      });
    } on AppError catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _revoke() async {
    final made = _invitation;
    if (made == null) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(householdWriterProvider).revokeInvitation(made);
      final link = _link;
      if (link != null && link == _onClipboard) {
        await _clipboard?.clearIf(link);
        _onClipboard = null;
      }
      if (!mounted) return;
      // The link is useless now; it leaves the screen, and the sheet's memory, with the revoke.
      setState(() {
        _revoked = true;
        _link = null;
      });
    } on AppError catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// A deliberate act, and the only way the link reaches the clipboard.
  Future<void> _copy() async {
    final link = _link;
    if (link == null) return;
    final clipboard = ref.read(sensitiveClipboardProvider);
    _clipboard = clipboard;
    await clipboard.copy(link);
    _onClipboard = link;
    if (mounted) setState(() => _copied = true);
  }

  void _another() {
    _form.currentState?.reset();
    _email.clear();
    setState(() {
      _invitation = null;
      _link = null;
      _revoked = false;
      _copied = false;
      _error = null;
      _role = Role.member;
    });
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final made = _invitation;
    return Scaffold(
      appBar: AppBar(
        title: Text(l.invite_title, maxLines: 2),
        leading: IconButton(
          icon: const Icon(Icons.close),
          tooltip: made == null ? l.common_cancel : l.common_done,
          onPressed: () => Navigator.of(context).pop(),
        ),
      ),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
          children: made == null ? _formChildren(context) : _madeChildren(context, made),
        ),
      ),
    );
  }

  List<Widget> _formChildren(BuildContext context) {
    final l = context.l10n;
    final text = Theme.of(context).textTheme;
    final online = canWriteNow(ref);
    final error = _error;
    const gap = SizedBox(height: SpaceTokens.s4);
    return [
      Text(l.invite_intro, style: text.bodyMedium?.copyWith(color: context.colors.muted)),
      gap,
      if (error != null) ...[NoticeBanner(text: writeErrorText(context, ref, error)), gap],
      Form(
        key: _form,
        child: TextFormField(
          controller: _email,
          keyboardType: TextInputType.emailAddress,
          autocorrect: false,
          enableSuggestions: false,
          maxLength: 254,
          decoration: InputDecoration(
            labelText: l.invite_email,
            helperText: l.invite_emailHint,
            helperMaxLines: 12,
          ),
          validator: (value) => Rules.email(l, value ?? ''),
        ),
      ),
      gap,
      Semantics(header: true, child: Text(l.invite_role, style: text.labelLarge)),
      const SizedBox(height: SpaceTokens.s1),
      RadioGroup<Role>(
        groupValue: _role,
        onChanged: (role) => setState(() => _role = role ?? _role),
        child: Column(
          children: [
            for (final role in Role.values)
              RadioListTile<Role>(
                value: role,
                contentPadding: EdgeInsetsDirectional.zero,
                title: Text(roleLabel(l, role), style: Theme.of(context).textTheme.titleSmall),
                subtitle: Text(roleDescription(l, role)),
              ),
          ],
        ),
      ),
      gap,
      Wrap(
        spacing: SpaceTokens.s2,
        runSpacing: SpaceTokens.s2,
        alignment: WrapAlignment.end,
        children: [
          TextButton(onPressed: () => Navigator.of(context).pop(), child: Text(l.common_cancel)),
          FilledButton(
            onPressed: _busy || !online ? null : _create,
            child: Text(_busy ? l.invite_creating : l.invite_create),
          ),
        ],
      ),
      const OfflineNote(),
    ];
  }

  List<Widget> _madeChildren(BuildContext context, Invitation made) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final online = canWriteNow(ref);
    final link = _link;
    final error = _error;
    const gap = SizedBox(height: SpaceTokens.s4);
    final expires = DateFormat.yMMMd(context.localeName).add_jm().format(made.expiresAt.toLocal());
    return [
      if (_revoked)
        NoticeBanner(text: l.invite_revoked)
      else ...[
        if (link != null) ...[
          Text(l.invite_shareIntro(made.email), style: text.bodyMedium),
          gap,
          Semantics(header: true, child: Text(l.invite_linkLabel, style: text.labelLarge)),
          const SizedBox(height: SpaceTokens.s1),
          Container(
            padding: const EdgeInsetsDirectional.all(SpaceTokens.s3),
            decoration: BoxDecoration(
              color: c.surface,
              borderRadius: BorderRadius.circular(RadiusTokens.sm),
              border: Border.all(color: c.divider),
            ),
            // A link reads left to right in every language, as the web's link box does.
            child: SelectableText(link, textDirection: TextDirection.ltr, style: text.bodyMedium),
          ),
          const SizedBox(height: SpaceTokens.s3),
          Wrap(
            spacing: SpaceTokens.s3,
            runSpacing: SpaceTokens.s2,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              FilledButton.icon(
                onPressed: _copy,
                icon: const Icon(Icons.copy_outlined),
                label: Text(l.invite_copy),
              ),
              if (_copied)
                Semantics(liveRegion: true, child: Text(l.invite_copied, style: text.bodySmall)),
            ],
          ),
        ] else
          NoticeBanner(text: l.invite_linkUnavailable, icon: Icons.error_outline),
        gap,
        NoticeBanner(
          text: l.invite_credentialWarning(roleLabel(l, made.role), expires),
          icon: Icons.key_outlined,
        ),
      ],
      if (error != null) ...[gap, NoticeBanner(text: writeErrorText(context, ref, error))],
      gap,
      Wrap(
        spacing: SpaceTokens.s2,
        runSpacing: SpaceTokens.s2,
        alignment: WrapAlignment.end,
        children: [
          if (!_revoked)
            OutlinedButton(
              style: OutlinedButton.styleFrom(
                foregroundColor: c.accentText,
                side: BorderSide(color: c.accentText),
              ),
              onPressed: _busy || !online ? null : _revoke,
              child: Text(l.invite_revoke),
            ),
          OutlinedButton(onPressed: _another, child: Text(l.invite_another)),
          FilledButton(onPressed: () => Navigator.of(context).pop(), child: Text(l.common_done)),
        ],
      ),
      if (!_revoked) const OfflineNote(),
    ];
  }
}
