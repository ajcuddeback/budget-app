import 'package:budget_owl/core/storage/secure_store.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:meta/meta.dart';

/// A bearer token and the origin that issued it. The pair travels together: the interceptor
/// attaches the token only to a request for that origin (audit H-a).
@immutable
final class IssuedToken {
  IssuedToken(this.value, String origin) : origin = Uri.parse(origin).origin;

  final String value;

  /// `scheme://host[:port]`, normalised as `Uri.origin` writes it, so it compares with a request's.
  final String origin;

  /// Whether [requestUri] is a request this token may be attached to.
  bool isFor(Uri requestUri) => requestUri.origin == origin;

  @override
  String toString() => 'IssuedToken(<redacted> for $origin)';
}

/// What [TokenStore.load] found.
enum TokenLoad {
  /// No token stored: signed out.
  none,

  /// A token the expected origin issued, now in memory.
  loaded,

  /// A token another origin issued, or one with no origin recorded. Deleted, not loaded; the
  /// session wipes everything else it may have left (audit N2).
  foreign,
}

/// The bearer token: full access to the household's finances until it is revoked.
///
/// **This class is the only holder.** The token is written to secure storage and nowhere else —
/// never `SharedPreferences`, a file, the Drift cache, a log line or a URL (mobile-app.md, attack 1)
/// — and the storage key below appears in no other file in `lib/`, which
/// `test/architecture/guards_test.dart` checks. The in-memory copy exists so the HTTP interceptor
/// can attach it without a Keychain read per request; [clear] drops both.
///
/// **The token is bound to the origin that issued it.** It is stored with that origin; [load]
/// refuses a token whose origin is not the server the phone is about to talk to (audit L3), and
/// the interceptor refuses to attach it to a request for any other origin (audit H-a).
final class TokenStore {
  TokenStore(this._store);

  static const String _key = 'budget_owl.bearer_token';
  static const String _originKey = 'budget_owl.token_origin';

  final SecureStore _store;
  IssuedToken? _held;

  /// The token for the next request, with its origin, or null when signed out. Synchronous on
  /// purpose: the interceptor must not await between deciding to send and sending.
  IssuedToken? get held => _held;

  /// The token's value alone, for code that only needs to know there is one.
  String? get current => _held?.value;

  bool get hasToken => _held != null;

  /// Reads the stored token into memory, at start-up — only if it was issued by [origin]. A token
  /// for any other origin, or with no origin recorded, is deleted, not loaded.
  ///
  /// A stored origin that is not an `http(s)://host` origin at all — empty, `owl.lan`, garbage — is
  /// treated exactly as another origin's: the token is deleted and the session wipes the rest. It
  /// never throws (`Uri.parse('').origin` does, and once hung start-up on its spinner).
  Future<TokenLoad> load({required String origin}) async {
    final token = await _store.read(_key);
    if (token == null) {
      _held = null;
      return TokenLoad.none;
    }
    final stored = originOf(await _store.read(_originKey));
    final expected = originOf(origin);
    if (stored == null || expected == null || stored != expected) {
      await clear();
      return TokenLoad.foreign;
    }
    _held = IssuedToken(token, expected);
    return TokenLoad.loaded;
  }

  /// `scheme://host[:port]` for an `http` or `https` URL with a host, or null for anything else.
  /// Storage is not trusted to hold only what this class once wrote.
  static String? originOf(String? value) {
    final uri = value == null ? null : Uri.tryParse(value);
    if (uri == null || (uri.scheme != 'https' && uri.scheme != 'http') || uri.host.isEmpty) {
      return null;
    }
    return uri.origin;
  }

  /// Keeps [token], issued by [origin].
  Future<void> save(String token, {required String origin}) async {
    await _store.write(_originKey, origin);
    await _store.write(_key, token);
    _held = IssuedToken(token, origin);
  }

  /// Hands over the token and forgets it — from memory and from secure storage — **before** the
  /// caller does anything slow with it (audit N1). A sign-out that is killed while its logout is
  /// in flight then leaves nothing to resume with.
  Future<IssuedToken?> take() async {
    final token = _held;
    await clear();
    return token;
  }

  /// Whether a token is still in secure storage. A store that cannot be read says yes: nothing
  /// may claim a sign-out finished that it cannot confirm (round 6, V1).
  Future<bool> isStored() async {
    try {
      return await _store.read(_key) != null;
    } on Object {
      return true;
    }
  }

  Future<void> clear() async {
    _held = null;
    await _store.delete(_key);
    await _store.delete(_originKey);
  }
}

final tokenStoreProvider = Provider<TokenStore>(
  (ref) => TokenStore(ref.watch(secureStoreProvider)),
);
