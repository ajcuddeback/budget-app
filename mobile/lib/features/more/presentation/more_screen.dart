import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/routing/destinations.dart';
import 'package:budget_owl/core/session/server_store.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:material_ui/material_ui.dart';

/// Everything not on the bottom bar, in the web's order (mobile-app.md, Screens), then the server
/// and signing out. Admin is listed only for an instance administrator — the admin API refuses
/// anybody else whatever this shows.
class MoreScreen extends ConsumerWidget {
  const MoreScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.l10n;
    final session = ref.watch(signedInProvider);
    final admin = session?.user.instanceAdmin ?? false;
    final server = session?.server;
    final entries = moreEntries.where((e) => !e.adminOnly || admin);
    return Screen(
      title: l.more_title,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final entry in entries)
            ListTile(
              leading: Icon(entry.icon),
              title: Text(entry.label(l)),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => context.go(entry.path),
            ),
          const Divider(),
          Overline(l.more_server),
          if (server != null)
            ListTile(
              leading: const Icon(Icons.dns_outlined),
              title: Text(server.display),
              // Beside the address: the CA on this phone it is trusted through, if any (S1).
              subtitle: ref.watch(pinnedCaProvider) == null
                  ? Text(l.more_changeServer)
                  : Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        TrustedThroughNote(changeServer: l.more_changeServer),
                        const SizedBox(height: SpaceTokens.s1),
                        Text(l.more_changeServer),
                      ],
                    ),
              onTap: () async {
                final confirmed = await confirmAction(
                  context,
                  heading: l.more_changeServerHeading,
                  message: l.more_changeServerMessage(server.display),
                  action: l.more_changeServer,
                  destructive: true,
                );
                if (confirmed) {
                  await ref.read(sessionProvider.notifier).signOut(forgetServer: true);
                }
              },
            ),
          ListTile(
            leading: Icon(Icons.logout, color: context.colors.accentText),
            title: Text(l.nav_signOut),
            onTap: () async {
              final confirmed = await confirmAction(
                context,
                heading: l.more_signOutHeading,
                message: l.more_signOutMessage,
                action: l.nav_signOut,
                destructive: true,
              );
              if (confirmed) await ref.read(sessionProvider.notifier).signOut();
            },
          ),
        ],
      ),
    );
  }
}
