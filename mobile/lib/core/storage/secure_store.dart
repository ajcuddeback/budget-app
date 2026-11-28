import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// The Keychain / Keystore, and nothing else (ADR-0018, ADR-0042).
///
/// The **only** file in `lib/` that may name `FlutterSecureStorage` — an architecture test fails if
/// another does — so the platform options below are the options, everywhere:
///
/// - **iOS**: `first_unlock_this_device`. Readable after the first unlock since boot, so the app can
///   start from a notification; never synced to iCloud Keychain and never restored onto another
///   device from a backup.
/// - **Android**: the package's default, AES-GCM with the key wrapped by an RSA key in the Android
///   Keystore. `allowBackup="false"` in the manifest keeps the wrapped values out of a backup too.
abstract interface class SecureStore {
  Future<String?> read(String key);

  Future<void> write(String key, String value);

  Future<void> delete(String key);
}

final class PlatformSecureStore implements SecureStore {
  const PlatformSecureStore();

  static const FlutterSecureStorage _storage = FlutterSecureStorage(
    iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock_this_device),
  );

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) => _storage.write(key: key, value: value);

  @override
  Future<void> delete(String key) => _storage.delete(key: key);
}

/// Overridden in tests with an in-memory store; the app never constructs another.
final secureStoreProvider = Provider<SecureStore>((ref) => const PlatformSecureStore());
