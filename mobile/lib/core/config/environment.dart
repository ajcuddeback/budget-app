import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';

/// Whether `http://localhost`, `127.0.0.1` and `10.0.2.2` may be used: a debug build only.
///
/// A release build has no such exception, here or in the platform configs (ADR-0042). Tests
/// override this to prove both halves.
final allowLocalCleartextProvider = Provider<bool>((ref) => kDebugMode);

/// "Now", for the "Last updated" label and a new transaction's default date. Injected so a test
/// can stand at the demo household's 20 August 2026 (testing-style.md: fixed clock).
final clockProvider = Provider<DateTime Function()>((ref) => DateTime.now);

/// Where the encrypted cache file lives: the platform's cache directory, which iOS never backs up
/// (Library/Caches) and Android's backup is switched off for anyway (`allowBackup="false"`).
final cacheDirectoryProvider = FutureProvider<Directory>(
  (ref) => getApplicationCacheDirectory(),
);

/// Which platform's wording the device label takes. Overridden in tests.
final targetPlatformProvider = Provider<TargetPlatform>((ref) => defaultTargetPlatform);
