import 'package:budget_owl/core/http/user_trust.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/storage/secure_store.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The CA the current server is pinned to, for the screens that say so (S1): the Connect
/// screen's choices and More → Server. Null for a server the system roots verify — the screens
/// then say nothing extra. Set by the session; cleared with the server.
final class PinnedCa extends Notifier<CaPin?> {
  @override
  CaPin? build() => null;

  // A setter would read as a field; this is a command the session gives.
  // ignore: use_setters_to_change_properties
  void set(CaPin? pin) => state = pin;
}

final pinnedCaProvider = NotifierProvider<PinnedCa, CaPin?>(PinnedCa.new);

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
  static const String _caNameKey = 'budget_owl.server_ca_name';

  final SecureStore _store;

  Future<ServerAddress?> read({required bool allowLocalCleartext}) async =>
      ServerAddress.tryRestore(await _store.read(_key), allowLocalCleartext: allowLocalCleartext);

  /// The pinned CA, or null: none was needed, or what is stored is not a fingerprint (then the
  /// system roots alone are trusted — fails closed). Its name is cleaned again as it is read.
  Future<CaPin?> readCaPin() async {
    final pin = await _store.read(_caPinKey);
    if (!isFingerprint(pin)) return null;
    return CaPin(pin!, subject: cleanCaName(await _store.read(_caNameKey)));
  }

  /// Keeps [address] and, when it needed one, the CA it was verified through.
  Future<void> save(ServerAddress address, {CaPin? caPin}) async {
    await _store.write(_key, address.stored);
    final subject = caPin?.subject;
    if (caPin == null) {
      await _store.delete(_caPinKey);
    } else {
      await _store.write(_caPinKey, caPin.fingerprint);
    }
    if (subject == null) {
      await _store.delete(_caNameKey);
    } else {
      await _store.write(_caNameKey, subject);
    }
  }

  Future<void> clear() async {
    await _store.delete(_caPinKey);
    await _store.delete(_caNameKey);
    await _store.delete(_key);
  }
}

final serverStoreProvider = Provider<ServerStore>(
  (ref) => ServerStore(ref.watch(secureStoreProvider)),
);
