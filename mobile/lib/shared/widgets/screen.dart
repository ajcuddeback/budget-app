import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:material_ui/material_ui.dart';

/// A screen's frame: the display-face title the artboards open with, and the body under it.
///
/// Every screen uses this, so the title, the gutters and the safe areas are decided once. Lay a
/// body out with `EdgeInsetsDirectional` and `AlignmentDirectional` only — the layout must work
/// right-to-left (mobile-app.md, Language).
class Screen extends StatelessWidget {
  const Screen({
    required this.title,
    required this.body,
    this.actions = const [],
    this.floatingActionButton,
    this.leading,
    this.scrollable = true,
    this.onRefresh,
    super.key,
  });

  final String title;
  final Widget body;
  final List<Widget> actions;
  final Widget? floatingActionButton;
  final Widget? leading;

  /// Pull-to-refresh: asks the server again. A refresh that fails keeps what is shown, labelled.
  final Future<void> Function()? onRefresh;

  /// False when [body] scrolls itself (a list).
  final bool scrollable;

  @override
  Widget build(BuildContext context) {
    final heading = Padding(
      padding: const EdgeInsetsDirectional.fromSTEB(
        SpaceTokens.s4,
        SpaceTokens.s3,
        SpaceTokens.s4,
        SpaceTokens.s2,
      ),
      // A Wrap, so at large text the actions move under the title instead of squeezing it into
      // one letter-broken column.
      child: Wrap(
        alignment: WrapAlignment.spaceBetween,
        crossAxisAlignment: WrapCrossAlignment.center,
        runSpacing: SpaceTokens.s1,
        children: [
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              ?leading,
              Flexible(
                child: Semantics(
                  header: true,
                  child: Text(title, style: Theme.of(context).textTheme.headlineSmall),
                ),
              ),
            ],
          ),
          if (actions.isNotEmpty) Row(mainAxisSize: MainAxisSize.min, children: actions),
        ],
      ),
    );
    final content = scrollable
        ? ListView(
            physics: const AlwaysScrollableScrollPhysics(),
            // Room for a floating button over the last row.
            padding: const EdgeInsetsDirectional.only(bottom: SpaceTokens.s8 * 3),
            children: [heading, body],
          )
        : Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              heading,
              Expanded(child: body),
            ],
          );
    final refresh = onRefresh;
    return Scaffold(
      floatingActionButton: floatingActionButton,
      body: SafeArea(
        bottom: false,
        child: refresh == null ? content : RefreshIndicator(onRefresh: refresh, child: content),
      ),
    );
  }
}

/// A block of body text with the screen's gutters.
class Gutter extends StatelessWidget {
  const Gutter({required this.child, super.key});

  final Widget child;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.symmetric(
      horizontal: SpaceTokens.s4,
      vertical: SpaceTokens.s2,
    ),
    child: child,
  );
}

/// The lede under a title, in the muted colour.
class Lede extends StatelessWidget {
  const Lede(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Gutter(
    child: Text(
      text,
      style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: context.colors.muted),
    ),
  );
}

/// A day's or a section's heading inside a list: small, uppercase, tracked.
class Overline extends StatelessWidget {
  const Overline(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsetsDirectional.fromSTEB(
      SpaceTokens.s4,
      SpaceTokens.s4,
      SpaceTokens.s4,
      SpaceTokens.s2,
    ),
    child: Semantics(
      header: true,
      child: Text(
        text.toUpperCase(),
        style: Theme.of(context).textTheme.labelSmall?.copyWith(color: context.colors.muted),
      ),
    ),
  );
}
