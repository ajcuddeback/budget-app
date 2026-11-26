import 'package:budget_owl/core/http/user_trust.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/storage/secure_store.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The household's server address, in secure storage (mobile-app.md, Data model), and the
/// fingerprint of the one user-installed CA it is trusted through, when it needed one (Android).
///
/// Not credentials, but they say where a household's finances live and whom the phone believes
/// about it, so they are kept with the token rather than in plain preferences. They survive a
/// sign-out — the pin with the address, so signing back in is one screen and never re-pins — and
/// are removed together on a change of server.
final class ServerStore {
  ServerStore(this._store);

  static const String _key = 'budget_owl.server';
  static const String _caPinKey = 'budget_owl.server_ca_pin';

  final SecureStore _store;

  Future<ServerAddress?> read({required bool allowLocalCleartext}) async =>
      ServerAddress.tryRestore(await _store.read(_key), allowLocalCleartext: allowLocalCleartext);

  /// The pinned CA's fingerprint, or null: none was needed, or what is stored is not a fingerprint
  /// (then the system roots alone are trusted — fails closed).
  Future<String?> readCaPin() async {
    final pin = await _store.read(_caPinKey);
    return isFingerprint(pin) ? pin : null;
  }

  /// Keeps [address] and, when it needed one, the fingerprint of the CA it was verified through.
  Future<void> save(ServerAddress address, {String? caPin}) async {
    await _store.write(_key, address.stored);
    if (caPin == null) {
      await _store.delete(_caPinKey);
    } else {
      await _store.write(_caPinKey, caPin);
    }
  }

  Future<void> clear() async {
    await _store.delete(_caPinKey);
    await _store.delete(_key);
  }
}

final serverStoreProvider = Provider<ServerStore>(
  (ref) => ServerStore(ref.watch(secureStoreProvider)),
);
