import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// A signed-in user who is in no household: an empty state, never an error
/// (authentication-and-households.md). An invitation is the way in; signing out is the way back
/// to the connect screen, where one can be pasted.
class NoHouseholdScreen extends ConsumerWidget {
  const NoHouseholdScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.l10n;
    return Screen(
      title: l.noHousehold_title,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.noHousehold_bodyPhone),
          Gutter(
            child: OutlinedButton(
              onPressed: () => ref.read(sessionProvider.notifier).signOut(),
              child: Text(l.nav_signOut),
            ),
          ),
        ],
      ),
    );
  }
}
