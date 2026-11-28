import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:material_ui/material_ui.dart';

/// Hides the app's content whenever it is not in the foreground, so the task switcher's
/// snapshot shows no balances (mobile-app.md, attack 7).
///
/// Android's `FLAG_SECURE` would do this too, and would also block the member's own screenshots
/// of their own budget — so it is deliberately not set. Instead the content is covered by an
/// opaque panel from the moment the app is `inactive` (iOS takes its snapshot then, and Android's
/// recents screen shortly after) until it is `resumed`. The cover is a plain ground with the
/// app's name: nothing from the screen underneath shows through, and nothing is blurred into
/// something legible.
class PrivacyCover extends StatefulWidget {
  const PrivacyCover({required this.child, super.key});

  final Widget child;

  @override
  State<PrivacyCover> createState() => _PrivacyCoverState();
}

class _PrivacyCoverState extends State<PrivacyCover> with WidgetsBindingObserver {
  bool _covered = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    final now = WidgetsBinding.instance.lifecycleState;
    _covered = now != null && now != AppLifecycleState.resumed;
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final cover = state != AppLifecycleState.resumed;
    if (cover != _covered) setState(() => _covered = cover);
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      textDirection: Directionality.maybeOf(context) ?? TextDirection.ltr,
      children: [
        // Excluded from semantics too: a screen reader must not read balances off a hidden app.
        ExcludeSemantics(excluding: _covered, child: widget.child),
        if (_covered)
          Positioned.fill(
            child: ColoredBox(
              color: context.colors.bg,
              child: Center(
                child: Semantics(
                  label: context.l10n.privacy_hidden,
                  child: Padding(
                    padding: const EdgeInsetsDirectional.all(SpaceTokens.s8),
                    child: Text(
                      context.l10n.app_name,
                      textAlign: TextAlign.center,
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                  ),
                ),
              ),
            ),
          ),
      ],
    );
  }
}
