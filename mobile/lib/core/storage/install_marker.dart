import 'dart:io';

import 'package:budget_owl/core/config/environment.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Whether this installation of the app has run before.
///
/// **The iOS Keychain survives an uninstall** (security audit L5): delete the app, install it
/// again, and the old token, cache key and server would still be there to read. A file in
/// Application Support does not survive an uninstall on either platform, so its absence at launch
/// means "a new installation", and the session wipes everything secure storage holds before it
/// reads anything. On Android the Keystore-backed values go with the app anyway; the marker costs
/// nothing there.
final class InstallMarker {
  InstallMarker(this._directory);

  static const String fileName = 'budget_owl.installed';

  final Future<Directory> Function() _directory;

  Future<File> get _file async => File('${(await _directory()).path}/$fileName');

  Future<bool> get present async => (await _file).existsSync();

  Future<void> place() async {
    final file = await _file;
    await file.create(recursive: true);
  }
}

final installMarkerProvider = Provider<InstallMarker>(
  (ref) => InstallMarker(() => ref.read(supportDirectoryProvider.future)),
);
