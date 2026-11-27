import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/http/user_trust.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/server_store.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/l10n/gen/app_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

/// "just now", "3 minutes ago", "2 hours ago", "4 days ago".
String agoText(AppLocalizations l, DateTime now, DateTime then, String locale) {
  final numbers = NumberFormat.decimalPattern(locale);
  final elapsed = now.toUtc().difference(then.toUtc());
  if (elapsed.inMinutes < 1) return l.offline_justNow;
  if (elapsed.inMinutes == 1) return l.offline_minuteAgo;
  if (elapsed.inHours < 1) return l.offline_minutesAgo(numbers.format(elapsed.inMinutes));
  if (elapsed.inHours == 1) return l.offline_hourAgo;
  if (elapsed.inDays < 1) return l.offline_hoursAgo(numbers.format(elapsed.inHours));
  if (elapsed.inDays == 1) return l.offline_dayAgo;
  return l.offline_daysAgo(numbers.format(elapsed.inDays));
}

/// The label a cached copy carries once its refresh has failed: *"Last updated 2 hours ago —
/// you're offline"* or *"— the server didn't answer"*. Never omitted while a stale figure shows.
class StaleBanner extends ConsumerWidget {
  const StaleBanner({required this.fetchedAt, required this.error, super.key});

  final DateTime fetchedAt;
  final AppError error;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l = context.l10n;
    final ago = agoText(l, ref.watch(clockProvider)(), fetchedAt, context.localeName);
    final text = switch (error.kind) {
      AppErrorKind.offline => l.offline_staleOffline(ago),
      _ => l.offline_staleNoAnswer(ago),
    };
    return NoticeBanner(text: text, icon: Icons.cloud_off_outlined);
  }
}

/// A quiet strip at the top of a screen: a stale label, an offline note, a notice.
class NoticeBanner extends StatelessWidget {
  const NoticeBanner({required this.text, this.icon, this.success = false, super.key});

  final String text;
  final IconData? icon;
  final bool success;

  @override
  Widget build(BuildContext context) {
    final c = context.colors;
    final edge = success ? c.accent2 : c.accent;
    return Semantics(
      liveRegion: true,
      container: true,
      child: Container(
        width: double.infinity,
        padding: const EdgeInsetsDirectional.symmetric(
          horizontal: SpaceTokens.s4,
          vertical: SpaceTokens.s3,
        ),
        decoration: BoxDecoration(
          color: Color.alphaBlend(edge.withAlpha(36), c.bg),
          borderRadius: BorderRadius.circular(RadiusTokens.md),
          border: BorderDirectional(
            start: BorderSide(color: edge, width: 6),
            top: BorderSide(color: edge),
            end: BorderSide(color: edge),
            bottom: BorderSide(color: edge),
          ),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (icon != null) ...[
              Icon(icon, size: 20, color: c.text),
              const SizedBox(width: SpaceTokens.s2),
            ],
            Expanded(child: Text(text, style: Theme.of(context).textTheme.bodyMedium)),
          ],
        ),
      ),
    );
  }
}

/// An error with nothing else to show: the true message, and a way to try again.
class ErrorPanel extends StatelessWidget {
  const ErrorPanel({required this.error, this.onRetry, super.key});

  final AppError error;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final text = error.kind == AppErrorKind.offline
        ? l.offline_noCopy
        : context.errorMessage(error);
    return Padding(
      padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          NoticeBanner(text: text, icon: Icons.error_outline),
          if (error.correlationId != null) ...[
            const SizedBox(height: SpaceTokens.s2),
            Text(
              l.error_reference(error.correlationId!),
              style: Theme.of(context).textTheme.bodySmall?.copyWith(color: context.colors.muted),
            ),
          ],
          if (onRetry != null) ...[
            const SizedBox(height: SpaceTokens.s3),
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: OutlinedButton(onPressed: onRetry, child: Text(l.common_retry)),
            ),
          ],
        ],
      ),
    );
  }
}

/// The four states of a read, the same way on every screen (mobile-app.md, UI): loading, an error
/// with no copy to show, data — and data with the stale label once a refresh has failed.
class SnapshotView<T> extends StatelessWidget {
  const SnapshotView({required this.snapshot, required this.builder, this.onRetry, super.key});

  final Snapshot<T> snapshot;
  final Widget Function(BuildContext context, T data, Widget? staleBanner) builder;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    final data = snapshot.data;
    if (data == null) {
      final error = snapshot.error;
      if (error != null && !snapshot.loading) {
        return ErrorPanel(error: error, onRetry: onRetry);
      }
      return Center(
        child: Padding(
          padding: const EdgeInsetsDirectional.all(SpaceTokens.s8),
          child: Semantics(
            label: context.l10n.common_loading,
            child: const CircularProgressIndicator(),
          ),
        ),
      );
    }
    final stale = snapshot.isStale
        ? StaleBanner(fetchedAt: snapshot.fetchedAt!, error: snapshot.error!)
        : null;
    return builder(context, data, stale);
  }
}

/// Whether a write may be sent now, and what to say when it may not.
///
/// A write is sent only when the last request reached the server **and** the server's version was
/// confirmed this launch (`Reachability.unconfirmed` until then); otherwise its control is
/// disabled with *"You're offline — this needs a connection"*, or the version note, and **nothing
/// is queued** (mobile-app.md decision 3, ADR-0042).
bool canWriteNow(WidgetRef ref) => ref.watch(reachabilityProvider).canWrite;

/// Why writes are off, in a sentence: offline, or the server's version not yet confirmed.
String writesOffText(AppLocalizations l, Reachability reachability) =>
    reachability == Reachability.unconfirmed ? l.offline_versionUnconfirmed : l.offline_write;

/// Which certificate authority installed on this phone the server is trusted through (S1): its
/// name, or — when its certificate names none — the start of its fingerprint. Only for a server
/// pinned to a user CA; a server the system roots verify gets no sentence at all.
String trustedThroughText(AppLocalizations l, CaPin pin) {
  final name = pin.subject;
  if (name != null) return l.trust_through(name);
  final pairs = [for (var i = 0; i < 16; i += 2) pin.fingerprint.substring(i, i + 2).toUpperCase()];
  return l.trust_throughUnnamed('${pairs.join(':')}…');
}

/// The sentence above, quiet, under the server it is about. Nothing when there is no pin.
class TrustedThroughNote extends ConsumerWidget {
  const TrustedThroughNote({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final pin = ref.watch(pinnedCaProvider);
    if (pin == null) return const SizedBox.shrink();
    return Text(
      trustedThroughText(context.l10n, pin),
      style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: context.colors.muted),
    );
  }
}

/// The note under a disabled write control, saying why it is disabled.
class OfflineNote extends ConsumerWidget {
  const OfflineNote({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final reachability = ref.watch(reachabilityProvider);
    if (reachability.canWrite) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsetsDirectional.only(top: SpaceTokens.s2),
      child: NoticeBanner(
        text: writesOffText(context.l10n, reachability),
        icon: Icons.cloud_off_outlined,
      ),
    );
  }
}

/// Asks before something that cannot be undone. Returns true when confirmed.
Future<bool> confirmAction(
  BuildContext context, {
  required String heading,
  required String message,
  required String action,
  bool destructive = false,
}) async {
  final l = context.l10n;
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: Text(heading),
      content: Text(message),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(dialogContext).pop(false),
          child: Text(l.common_cancel),
        ),
        if (destructive)
          OutlinedButton(
            style: OutlinedButton.styleFrom(
              foregroundColor: dialogContext.colors.accentText,
              side: BorderSide(color: dialogContext.colors.accentText),
            ),
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(action),
          )
        else
          FilledButton(onPressed: () => Navigator.of(dialogContext).pop(true), child: Text(action)),
      ],
    ),
  );
  return confirmed ?? false;
}
