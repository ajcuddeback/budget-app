import 'package:meta/meta.dart';
/// The household's server, as the person typed it, checked before anything is sent to it.
///
/// **There is no default and no suggestion** (ADR-0016): the app ships knowing no server, and
/// this class has no constant address in it anywhere.
@immutable
final class ServerAddress {
  const ServerAddress._(this.origin);

  /// `https://host[:port]` — no path, no query, no credentials. The API lives at `/api` under it.
  final Uri origin;

  String get host => origin.host;

  /// What to show a person: the host, and the port when it is not the scheme's default.
  String get display => origin.hasPort ? '${origin.host}:${origin.port}' : origin.host;

  /// The stored form, and the form [tryRestore] reads back.
  String get stored => origin.toString();

  /// Whether this address is the debug-only plain-HTTP exception.
  bool get isCleartext => origin.scheme == 'http';

  /// A previously stored address, re-checked with the same rules: storage is not trusted to hold
  /// only what this class once wrote.
  static ServerAddress? tryRestore(String? stored, {required bool allowLocalCleartext}) {
    if (stored == null) return null;
    return switch (parse(stored, allowLocalCleartext: allowLocalCleartext)) {
      AddressAccepted(:final address) => address,
      AddressRefused() => null,
    };
  }

  /// Normalises and checks what the person typed (mobile-app.md, Connecting to a server).
  ///
  /// - `https://` is assumed when no scheme is typed; a trailing slash is dropped.
  /// - **HTTPS only.** `http://` is refused, except when [allowLocalCleartext] — true only in a
  ///   debug build (`kDebugMode`) — and the host is `localhost`, `127.0.0.1` or the Android
  ///   emulator's `10.0.2.2`. The platform network configs refuse cleartext as well.
  /// - A path other than `/` is refused, since the API lives at `/api` and a path would put it
  ///   somewhere else; so are a query, a fragment, and a user name or password in the address.
  static AddressParse parse(String typed, {required bool allowLocalCleartext}) {
    final text = typed.trim();
    if (text.isEmpty) return const AddressRefused(AddressProblem.empty);
    if (RegExp(r'\s').hasMatch(text)) return const AddressRefused(AddressProblem.invalid);

    final withScheme = RegExp('^[A-Za-z][A-Za-z0-9+.-]*://').hasMatch(text)
        ? text
        : 'https://$text';
    final Uri uri;
    try {
      uri = Uri.parse(withScheme);
    } on FormatException {
      return const AddressRefused(AddressProblem.invalid);
    }

    final scheme = uri.scheme.toLowerCase();
    if (scheme != 'https' && scheme != 'http') {
      return const AddressRefused(AddressProblem.invalid);
    }
    if (uri.host.isEmpty) return const AddressRefused(AddressProblem.invalid);
    if (uri.userInfo.isNotEmpty) return const AddressRefused(AddressProblem.credentials);
    if (uri.hasQuery || uri.hasFragment || withScheme.contains('?') || withScheme.contains('#')) {
      return const AddressRefused(AddressProblem.query);
    }
    if (uri.path.isNotEmpty && uri.path != '/') {
      return const AddressRefused(AddressProblem.path);
    }

    final host = uri.host.toLowerCase();
    if (scheme == 'http' && !(allowLocalCleartext && _localDevelopmentHosts.contains(host))) {
      return const AddressRefused(AddressProblem.cleartext);
    }

    final origin = Uri(
      scheme: scheme,
      host: host,
      port: uri.hasPort ? uri.port : null,
    );
    return AddressAccepted(ServerAddress._(origin));
  }

  @override
  bool operator ==(Object other) => other is ServerAddress && other.origin == origin;

  @override
  int get hashCode => origin.hashCode;

  @override
  String toString() => 'ServerAddress($display)';
}

/// The only hosts a debug build may reach over plain HTTP. A release build reaches none.
const Set<String> _localDevelopmentHosts = {'localhost', '127.0.0.1', '10.0.2.2'};

sealed class AddressParse {
  const AddressParse();
}

final class AddressAccepted extends AddressParse {
  const AddressAccepted(this.address);
  final ServerAddress address;
}

final class AddressRefused extends AddressParse {
  const AddressRefused(this.problem);
  final AddressProblem problem;
}

enum AddressProblem { empty, invalid, cleartext, path, query, credentials }
