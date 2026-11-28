import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/current_user.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// One block of a settings screen: a heading, optional actions beside it, and its content — the
/// web's `.card` with its `.card-title` (household, preferences and the admin console).
class SectionCard extends StatelessWidget {
  const SectionCard({required this.title, required this.children, this.action, super.key});

  final String title;
  final Widget? action;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsetsDirectional.symmetric(
        horizontal: SpaceTokens.s4,
        vertical: SpaceTokens.s2,
      ),
      child: Card(
        child: Padding(
          padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              // A Wrap, so at large text the action moves under the heading instead of
              // squeezing it into a column of single letters.
              Wrap(
                alignment: WrapAlignment.spaceBetween,
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: SpaceTokens.s2,
                runSpacing: SpaceTokens.s2,
                children: [
                  Semantics(header: true, child: Text(title, style: text.titleMedium)),
                  ?action,
                ],
              ),
              const SizedBox(height: SpaceTokens.s3),
              ...children,
            ],
          ),
        ),
      ),
    );
  }
}

/// A small label: a role, "You", "Administrator", an account's status.
class Tag extends StatelessWidget {
  const Tag(this.text, {this.strong = false, super.key});

  final String text;

  /// The accent-edged variant, for the one row that stands out (the caller, the administrator).
  final bool strong;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    return Container(
      padding: const EdgeInsetsDirectional.symmetric(
        horizontal: SpaceTokens.s2,
        vertical: SpaceTokens.s1 / 2,
      ),
      decoration: BoxDecoration(
        color: strong ? Color.alphaBlend(c.accent.withAlpha(36), c.card) : c.surface,
        borderRadius: BorderRadius.circular(RadiusTokens.sm),
        border: Border.all(color: strong ? c.accent : c.divider),
      ),
      child: Text(text, style: Theme.of(context).textTheme.labelMedium?.copyWith(color: c.text)),
    );
  }
}

/// A line of muted help under a field or a control.
class Hint extends StatelessWidget {
  const Hint(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s1),
    child: Text(
      text,
      style: Theme.of(context).textTheme.bodySmall?.copyWith(color: context.colors.muted),
    ),
  );
}

/// One option of a [ChoiceField]. `ltr` for a label that reads left to right in every language —
/// a currency code and its symbol — which a mirrored layout would otherwise reorder.
typedef Choice = ({String value, String label, bool ltr});

/// A choice from a list (a currency, a language): never typed.
///
/// The chosen value is drawn on **one line**, with an ellipsis when large text will not fit it; the
/// open menu shows every option in full. (A wrapped value was clipped to its first line.)
class ChoiceField extends StatelessWidget {
  const ChoiceField({
    required this.value,
    required this.choices,
    required this.label,
    required this.onChanged,
    this.hint,
    this.validator,
    super.key,
  });

  final String? value;
  final List<Choice> choices;
  final String label;
  final String? hint;
  final ValueChanged<String?>? onChanged;
  final FormFieldValidator<String>? validator;

  @override
  Widget build(BuildContext context) => DropdownButtonFormField<String>(
    initialValue: value,
    isExpanded: true,
    menuMaxHeight: 400,
    decoration: InputDecoration(labelText: label, helperText: hint, helperMaxLines: 12),
    selectedItemBuilder: (_) => [
      for (final choice in choices)
        Align(
          alignment: AlignmentDirectional.centerStart,
          child: Text(
            choice.label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            textDirection: choice.ltr ? TextDirection.ltr : null,
          ),
        ),
    ],
    items: [
      for (final choice in choices)
        DropdownMenuItem(
          value: choice.value,
          child: Text(choice.label, textDirection: choice.ltr ? TextDirection.ltr : null),
        ),
    ],
    onChanged: onChanged,
    validator: validator,
  );
}

/// The sentence for a write that did not go through. A write the phone refused before sending
/// (offline, or the server's version not yet confirmed this launch) says which of the two it was,
/// as the disabled controls do (`writesOffText`); anything the server answered is its translated
/// code.
String writeErrorText(BuildContext context, WidgetRef ref, AppError error) {
  final reachability = ref.read(reachabilityProvider);
  return error.kind == AppErrorKind.offline && !reachability.canWrite
      ? writesOffText(context.l10n, reachability)
      : context.errorMessage(error);
}

/// A role's name, in the web's words (`role.OWNER` …).
String roleLabel(AppLocalizations l, Role role) => switch (role) {
  Role.owner => l.role_OWNER,
  Role.member => l.role_MEMBER,
  Role.viewer => l.role_VIEWER,
};

/// What a role may do, in the web's words (`role.OWNER.description` …).
String roleDescription(AppLocalizations l, Role role) => switch (role) {
  Role.owner => l.role_OWNER_description,
  Role.member => l.role_MEMBER_description,
  Role.viewer => l.role_VIEWER_description,
};
