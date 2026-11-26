import 'dart:async';

/// The zone value Flutter's engine reads before **every** `dart:io` HTTP connection
/// (`_getHttpConnectionHookClosure` in the engine's `dart:ui` hooks): when it is `false`, a
/// connection to an `http://` URL is refused with an `UnsupportedError` unless the host is loopback,
/// whatever the platform's own policy says.
///
/// Android's `network_security_config.xml` and iOS's ATS govern the platform's networking, not the
/// Dart sockets this app uses, so on their own they enforce nothing here. HTTPS is enforced by, in
/// order: `ServerAddress.parse` (an `http` address is refused before anything is sent), the API
/// client's origin guard (a request may go only to the chosen origin), no redirects (a server
/// cannot bounce a request elsewhere), and this flag (the socket layer itself refuses cleartext).
/// The platform files remain as declarations of the same policy.
const Symbol allowHttpZoneKey = #flutter.io.allow_http;

/// Runs [body] — the whole app — under the cleartext policy.
///
/// A release build runs in a zone that refuses `http://` at the socket. A debug build is left
/// alone: its development exception includes `10.0.2.2`, the Android emulator's host, which is not
/// loopback and which the flag would refuse; `ServerAddress.parse` still limits it to the three
/// local development hosts.
R runWithCleartextPolicy<R>(R Function() body, {required bool allowLocalCleartext}) =>
    allowLocalCleartext ? body() : runZoned(body, zoneValues: {allowHttpZoneKey: false});
