import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/storage/secure_store.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The household's server address, in secure storage (mobile-app.md, Data model).
///
/// Not a credential, but it says where a household's finances live, so it is kept with the
/// token rather than in plain preferences. It survives a sign-out and is removed on a change of
/// server.
final class ServerStore {
  ServerStore(this._store);

  static const String _key = 'budget_owl.server';

  final SecureStore _store;

  Future<ServerAddress?> read({required bool allowLocalCleartext}) async =>
      ServerAddress.tryRestore(await _store.read(_key), allowLocalCleartext: allowLocalCleartext);

  Future<void> save(ServerAddress address) => _store.write(_key, address.stored);

  Future<void> clear() => _store.delete(_key);
}

final serverStoreProvider = Provider<ServerStore>(
  (ref) => ServerStore(ref.watch(secureStoreProvider)),
);
