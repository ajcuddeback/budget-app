import 'dart:async';

import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/current_user.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/household/data/household_repository.dart';
import 'package:budget_owl/features/household/domain/currencies.dart';
import 'package:budget_owl/features/household/domain/household.dart';
import 'package:budget_owl/features/household/presentation/invite_sheet.dart';
import 'package:budget_owl/features/household/presentation/section_card.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart' show DateFormat;
import 'package:material_ui/material_ui.dart';

/// The household: its name and base currency, who is in it, and how to add someone
/// (authentication-and-households.md, "What the web client does").
///
/// - **An owner** renames it, sets its base currency, invites, changes anyone's role but their
///   own, and removes anyone but themselves. **A member or a viewer** reads the same screen with
///   none of those controls, and may leave.
/// - **The only owner** is told why they cannot leave rather than offered a button that fails.
/// - Removing and leaving are confirmed first, in the web's words. A role is changed by choosing
///   it, as on the web.
/// - **None of this is the control.** Every write is refused by the server for a role that may not
///   make it, and a refusal is shown here, translated, beside the thing that failed.
/// - There is no household switcher, because an instance holds exactly one household (ADR-0026).
class HouseholdScreen extends ConsumerStatefulWidget {
  const HouseholdScreen({super.key});

  @override
  ConsumerState<HouseholdScreen> createState() => _HouseholdScreenState();
}

class _HouseholdScreenState extends ConsumerState<HouseholdScreen> {
  final _form = GlobalKey<FormState>();
  final _name = TextEditingController();
  String _currency = '';
  Household? _applied;

  bool _saving = false;
  bool _saved = false;
  AppError? _saveError;

  bool _membersBusy = false;
  AppError? _membersError;

  @override
  void initState() {
    super.initState();
    // The form is filled from the server's copy when it arrives or changes — never while the
    // owner is part-way through typing over the same copy.
    _apply(ref.read(householdProvider).data);
    ref.listenManual(householdProvider, (_, next) => setState(() => _apply(next.data)));
  }

  void _apply(Household? household) {
    if (household == null) return;
    final applied = _applied;
    if (applied != null &&
        applied.name == household.name &&
        applied.baseCurrency == household.baseCurrency) {
      return;
    }
    _applied = household;
    _name.text = household.name;
    _currency = household.baseCurrency;
  }

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _refresh() => Future.wait([
    ref.read(householdProvider.notifier).refresh(),
    ref.read(membersProvider.notifier).refresh(),
  ]);

  Future<void> _save() async {
    if (!(_form.currentState?.validate() ?? false)) return;
    setState(() {
      _saving = true;
      _saved = false;
      _saveError = null;
    });
    try {
      await ref.read(householdWriterProvider).rename(name: _name.text, baseCurrency: _currency);
      if (mounted) setState(() => _saved = true);
    } on AppError catch (error) {
      if (mounted) setState(() => _saveError = error);
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  /// Runs one member write, showing a refusal above the list and leaving the list as it was.
  Future<void> _memberWrite(Future<void> Function(HouseholdWriter writer) write) async {
    setState(() {
      _membersBusy = true;
      _membersError = null;
    });
    try {
      await write(ref.read(householdWriterProvider));
    } on AppError catch (error) {
      if (mounted) setState(() => _membersError = error);
    } finally {
      if (mounted) setState(() => _membersBusy = false);
    }
  }

  Future<void> _changeRole(Member member, Role role) async {
    if (role == member.role) return;
    await _memberWrite((writer) => writer.changeRole(member, role));
  }

  Future<void> _remove(Member member) async {
    final l = context.l10n;
    final confirmed = await confirmAction(
      context,
      heading: l.members_removeHeading(member.displayName),
      message: l.members_removeMessage(member.displayName),
      action: l.members_remove,
      destructive: true,
    );
    if (!confirmed || !mounted) return;
    await _memberWrite((writer) => writer.remove(member));
  }

  Future<void> _leave(Member me) async {
    final l = context.l10n;
    final confirmed = await confirmAction(
      context,
      heading: l.members_leaveHeading,
      message: l.members_leaveMessage,
      action: l.members_leave,
      destructive: true,
    );
    if (!confirmed || !mounted) return;
    await _memberWrite((writer) => writer.leave(me.id));
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final household = ref.watch(householdProvider);
    final members = ref.watch(membersProvider);
    final myMembershipId = ref.watch(signedInProvider)?.membership?.membershipId ?? '';
    final online = canWriteNow(ref);
    final isOwner = household.data?.role == Role.owner;
    return Screen(
      title: l.household_title,
      onRefresh: _refresh,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.household_lede),
          SnapshotView(
            snapshot: household,
            onRetry: () => unawaited(ref.read(householdProvider.notifier).refresh()),
            builder: (context, data, stale) => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (stale != null) Gutter(child: stale),
                _details(context, data, online: online),
              ],
            ),
          ),
          // With nothing to show there is nothing to disable: the error says offline already.
          if (household.hasData) ...[
            _members(
              context,
              members,
              myMembershipId: myMembershipId,
              isOwner: isOwner,
              online: online,
              // One stale label per screen: the members' only when the household's is not shown.
              showStale: !household.isStale,
            ),
            const Gutter(child: OfflineNote()),
          ],
        ],
      ),
    );
  }

  Widget _details(BuildContext context, Household household, {required bool online}) {
    final l = context.l10n;
    final saveError = _saveError;
    const gap = SizedBox(height: SpaceTokens.s3);
    if (household.role != Role.owner) {
      return SectionCard(
        title: l.household_detailsHeading,
        children: [
          _ReadOnlyField(label: l.household_name, value: household.name),
          gap,
          _ReadOnlyField(
            label: l.household_baseCurrency,
            value: currencyLabel(household.baseCurrency, context.localeName),
            // A code and its symbol read left to right in every language ("$ · USD" otherwise).
            direction: TextDirection.ltr,
            hint: l.household_baseCurrencyHint,
          ),
          gap,
          Hint(l.household_ownerOnlyEdit),
        ],
      );
    }
    return SectionCard(
      title: l.household_detailsHeading,
      children: [
        if (saveError != null) ...[
          NoticeBanner(text: writeErrorText(context, ref, saveError)),
          gap,
        ],
        if (_saved) ...[NoticeBanner(text: l.household_saved, success: true), gap],
        Form(
          key: _form,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextFormField(
                controller: _name,
                maxLength: 100,
                autocorrect: false,
                decoration: InputDecoration(labelText: l.household_name),
                validator: (value) => (value ?? '').trim().isEmpty ? l.validation_required : null,
              ),
              gap,
              ChoiceField(
                key: ValueKey('currency-$_currency'),
                value: _currency.isEmpty ? null : _currency,
                label: l.household_baseCurrency,
                hint: l.household_baseCurrencyHint,
                choices: [
                  for (final code in currencyChoices(_currency))
                    (value: code, label: currencyLabel(code, context.localeName), ltr: true),
                ],
                onChanged: (value) => setState(() => _currency = value ?? _currency),
                validator: (value) => value == null ? l.validation_required : null,
              ),
              gap,
              Align(
                alignment: AlignmentDirectional.centerStart,
                child: FilledButton(
                  onPressed: _saving || !online ? null : _save,
                  child: Text(_saving ? l.common_saving : l.common_save),
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _members(
    BuildContext context,
    Snapshot<List<Member>> snapshot, {
    required String myMembershipId,
    required bool isOwner,
    required bool online,
    required bool showStale,
  }) {
    final l = context.l10n;
    final error = _membersError;
    return SectionCard(
      title: l.members_heading,
      action: isOwner
          ? FilledButton(
              onPressed: online && !_membersBusy ? () => showInviteSheet(context) : null,
              child: Text(l.members_invite),
            )
          : null,
      children: [
        if (error != null) ...[
          NoticeBanner(text: writeErrorText(context, ref, error)),
          const SizedBox(height: SpaceTokens.s2),
        ],
        SnapshotView(
          snapshot: snapshot,
          onRetry: () => unawaited(ref.read(membersProvider.notifier).refresh()),
          builder: (context, members, stale) => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (showStale) ?stale,
              for (final (index, member) in members.indexed) ...[
                if (index > 0) const Divider(),
                _MemberRow(
                  member: member,
                  mine: member.id == myMembershipId,
                  controls: controlsFor(
                    member,
                    myMembershipId: myMembershipId,
                    iAmOwner: isOwner,
                    all: members,
                  ),
                  enabled: online && !_membersBusy,
                  onRole: (role) => _changeRole(member, role),
                  onRemove: () => _remove(member),
                  onLeave: () => _leave(member),
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }
}

class _ReadOnlyField extends StatelessWidget {
  const _ReadOnlyField({required this.label, required this.value, this.hint, this.direction});

  final String label;
  final String value;
  final String? hint;
  final TextDirection? direction;

  /// Drawn as a disabled field, as the web's disabled form is: it reads as a value, not an input.
  @override
  Widget build(BuildContext context) => InputDecorator(
    decoration: InputDecoration(
      labelText: label,
      helperText: hint,
      helperMaxLines: 12,
      enabled: false,
    ),
    child: Text(value, textDirection: direction),
  );
}

class _MemberRow extends StatelessWidget {
  const _MemberRow({
    required this.member,
    required this.mine,
    required this.controls,
    required this.enabled,
    required this.onRole,
    required this.onRemove,
    required this.onLeave,
  });

  final Member member;
  final bool mine;
  final MemberControls controls;
  final bool enabled;
  final ValueChanged<Role> onRole;
  final VoidCallback onRemove;
  final VoidCallback onLeave;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final joined = DateFormat.yMMMd(context.localeName).format(member.joinedAt.toLocal());
    final destructive = OutlinedButton.styleFrom(
      foregroundColor: c.accentText,
      side: BorderSide(color: c.accentText),
    );
    return Padding(
      padding: const EdgeInsetsDirectional.symmetric(vertical: SpaceTokens.s2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Wrap(
            spacing: SpaceTokens.s2,
            runSpacing: SpaceTokens.s1,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              Text(member.displayName, style: text.titleSmall),
              if (mine) Tag(l.common_you, strong: true),
            ],
          ),
          Text(member.email, style: text.bodySmall?.copyWith(color: c.muted)),
          Text(l.members_joined(joined), style: text.bodySmall?.copyWith(color: c.muted)),
          const SizedBox(height: SpaceTokens.s2),
          Wrap(
            spacing: SpaceTokens.s2,
            runSpacing: SpaceTokens.s2,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: switch (controls) {
              MemberControls.manage => [
                Semantics(
                  label: l.members_roleFor(member.displayName),
                  child: DropdownButton<Role>(
                    value: member.role,
                    onChanged: enabled ? (role) => role == null ? null : onRole(role) : null,
                    items: [
                      for (final role in Role.values)
                        DropdownMenuItem(value: role, child: Text(roleLabel(l, role))),
                    ],
                  ),
                ),
                OutlinedButton(
                  style: destructive,
                  onPressed: enabled ? onRemove : null,
                  child: Text(l.members_remove),
                ),
              ],
              MemberControls.leave => [
                Tag(roleLabel(l, member.role)),
                OutlinedButton(
                  style: destructive,
                  onPressed: enabled ? onLeave : null,
                  child: Text(l.members_leave),
                ),
              ],
              MemberControls.soleOwner => [
                Tag(roleLabel(l, member.role)),
                Hint(l.members_soleOwner),
              ],
              MemberControls.none => [Tag(roleLabel(l, member.role))],
            },
          ),
        ],
      ),
    );
  }
}
