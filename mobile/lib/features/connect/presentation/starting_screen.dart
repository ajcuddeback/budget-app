import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// While the stored session is read. If it cannot be resumed — the server is out of reach and the
/// phone has no copy of who the member is — it says so and offers to try again or sign out.
class StartingScreen extends ConsumerWidget {
  const StartingScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.l10n;
    final session = ref.watch(sessionProvider);
    final failure = session is SessionStarting ? session.failure : null;
    if (failure == null) {
      return Scaffold(
        body: Center(
          child: Semantics(label: l.common_loading, child: const CircularProgressIndicator()),
        ),
      );
    }
    return Screen(
      title: l.app_name,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ErrorPanel(error: failure, onRetry: () => ref.invalidate(sessionProvider)),
          const SizedBox(height: SpaceTokens.s4),
          Gutter(
            child: TextButton(
              onPressed: () => ref.read(sessionProvider.notifier).signOut(),
              child: Text(l.nav_signOut),
            ),
          ),
        ],
      ),
    );
  }
}
