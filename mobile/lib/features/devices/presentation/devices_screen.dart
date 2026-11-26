import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/l10n/l10n.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/core/theme/app_theme.dart';
import 'package:budget_owl/core/theme/tokens.dart';
import 'package:budget_owl/features/devices/data/devices.dart';
import 'package:budget_owl/shared/widgets/screen.dart';
import 'package:budget_owl/shared/widgets/status.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:intl/intl.dart';
import 'package:material_ui/material_ui.dart';

/// Where the member is signed in, each revocable (authentication-and-households.md, Devices).
///
/// **This phone is marked**, and signing it out is the session's sign-out: `POST /api/auth/logout`
/// and then the wipe, whether or not the server answered. Another device is revoked with `DELETE`,
/// which ends it on its very next request.
class DevicesScreen extends ConsumerStatefulWidget {
  const DevicesScreen({super.key});

  @override
  ConsumerState<DevicesScreen> createState() => _DevicesScreenState();
}

class _DevicesScreenState extends ConsumerState<DevicesScreen> {
  String? _busyId;
  AppError? _error;
  bool _revoked = false;

  Future<void> _signOut(Device device) async {
    final l = context.l10n;
    if (device.current) {
      final confirmed = await confirmAction(
        context,
        heading: l.devices_confirmHeadingPhone,
        message: l.devices_confirmMessagePhone,
        action: l.devices_signOutHere,
        destructive: true,
      );
      if (confirmed) await ref.read(sessionProvider.notifier).signOut();
      return;
    }
    setState(() {
      _busyId = device.id;
      _error = null;
      _revoked = false;
    });
    try {
      await revokeDevice(ref, device);
      if (mounted) setState(() => _revoked = true);
    } on AppError catch (error) {
      if (mounted) setState(() => _error = error);
    } finally {
      if (mounted) setState(() => _busyId = null);
    }
  }

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final snapshot = ref.watch(devicesProvider);
    final online = canWriteNow(ref);
    final error = _error;
    return Screen(
      title: l.devices_title,
      body: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Lede(l.devices_lede),
          if (_revoked) Gutter(child: NoticeBanner(text: l.devices_revoked, success: true)),
          if (error != null) Gutter(child: NoticeBanner(text: context.errorMessage(error))),
          SnapshotView(
            snapshot: snapshot,
            onRetry: () => ref.read(devicesProvider.notifier).refresh(),
            builder: (context, devices, stale) => Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (stale != null) Gutter(child: stale),
                if (devices.isEmpty) Lede(l.devices_empty),
                for (final device in devices)
                  _DeviceRow(
                    device: device,
                    busy: _busyId == device.id,
                    online: online,
                    onSignOut: () => _signOut(device),
                  ),
                const Gutter(child: OfflineNote()),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _DeviceRow extends StatelessWidget {
  const _DeviceRow({
    required this.device,
    required this.busy,
    required this.online,
    required this.onSignOut,
  });

  final Device device;
  final bool busy;
  final bool online;
  final VoidCallback onSignOut;

  @override
  Widget build(BuildContext context) {
    final l = context.l10n;
    final c = context.colors;
    final text = Theme.of(context).textTheme;
    final dates = DateFormat.yMMMd(context.localeName);
    final lastUsed = device.lastUsedAt;
    return Padding(
      padding: const EdgeInsetsDirectional.symmetric(
        horizontal: SpaceTokens.s4,
        vertical: SpaceTokens.s1,
      ),
      child: Card(
        child: Padding(
          padding: const EdgeInsetsDirectional.all(SpaceTokens.s4),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    device.isPhone ? Icons.smartphone_outlined : Icons.language_outlined,
                    color: c.muted,
                  ),
                  const SizedBox(width: SpaceTokens.s2),
                  Expanded(child: Text(device.label, style: text.titleMedium)),
                ],
              ),
              const SizedBox(height: SpaceTokens.s1),
              Text(
                [
                  if (device.isPhone) l.devices_kindApp else l.devices_kindSession,
                  l.devices_signedInAt(dates.format(device.createdAt.toLocal())),
                  if (lastUsed != null) l.devices_lastUsed(dates.format(lastUsed.toLocal())),
                ].join(' · '),
                style: text.bodySmall?.copyWith(color: c.muted),
              ),
              if (device.current) ...[
                const SizedBox(height: SpaceTokens.s2),
                Text(
                  device.isPhone ? l.devices_thisPhone : l.devices_thisDevice,
                  style: text.labelLarge?.copyWith(color: c.accentText),
                ),
              ],
              const SizedBox(height: SpaceTokens.s2),
              Align(
                alignment: AlignmentDirectional.centerEnd,
                child: OutlinedButton(
                  // This phone can always sign itself out: the wipe needs no server.
                  onPressed: busy || (!online && !device.current) ? null : onSignOut,
                  child: Text(device.current ? l.devices_signOutHere : l.devices_signOut),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
