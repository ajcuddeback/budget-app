import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// One place the member is signed in: a browser session or a phone's token.
final class Device {
  const Device({
    required this.id,
    required this.kind,
    required this.label,
    required this.createdAt,
    required this.current,
    this.lastUsedAt,
  });

  factory Device.fromJson(JsonMap json) => Device(
    id: json.string('id'),
    kind: json.string('kind'),
    label: json.string('label'),
    createdAt: DateTime.parse(json.string('createdAt')),
    lastUsedAt: switch (json.stringOrNull('lastUsedAt')) {
      final String at => DateTime.parse(at),
      null => null,
    },
    current: json.boolean('current'),
  );

  /// `t-<uuid>` for a token, `s-<sha256>` for a session — a handle, not the credential.
  final String id;
  final String kind;
  final String label;
  final DateTime createdAt;
  final DateTime? lastUsedAt;

  /// The credential making this request: on the phone, this phone.
  final bool current;

  bool get isPhone => kind == 'BEARER';
}

final class DevicesResource extends CachedResource<List<Device>> {
  @override
  String get path => '/api/auth/devices';

  @override
  Map<String, String> get query => const {'page': '0', 'size': '50'};

  @override
  List<Device> decode(JsonMap json) => [
    for (final device in json.objects('content')) Device.fromJson(device),
  ];
}

final devicesProvider = NotifierProvider.autoDispose<DevicesResource, Snapshot<List<Device>>>(
  DevicesResource.new,
);

/// Signs one other device out (`DELETE /api/auth/devices/{id}`). Revocation is immediate on the
/// server; this phone signs itself out through the session instead, which also wipes it.
Future<void> revokeDevice(WidgetRef ref, Device device) async {
  if (!ref.read(reachabilityProvider).canWrite) throw const AppError.offline();
  final api = ref.read(apiClientProvider);
  if (api == null) throw const AppError.unexpected();
  await api.write('DELETE', '/api/auth/devices/${Uri.encodeComponent(device.id)}');
  // Only the list changed: no household data moved, so nothing else is re-read.
  await ref.read(devicesProvider.notifier).refresh();
}
