import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';

/// The certificate authorities the person installed on an **Android** phone, and which one of them
/// — at most one — the app's server is trusted through.
///
/// ADR-0042's promise is that a self-hoster with a private CA installs it on the phone. iOS hands
/// an enabled CA profile to every TLS stack; Android does not: `dart:io` reads only the system
/// store, whatever `network_security_config.xml` says. `MainActivity` reads the user-installed CAs
/// (`AndroidCAStore`, aliases `user:…`) and hands them over here.
///
/// **They are not all trusted.** A user CA can be one an employer's device management pushed, which
/// could read the token and balances through TLS inspection. So a server is **pinned** when it is
/// connected to: if its chain validates against the system roots, no user CA is ever added for it;
/// if it validates only with a user CA, that one CA's SHA-256 fingerprint is stored with the server
/// address, and from then on the server is trusted through the system roots and **that CA alone**
/// (`PhoneTrustAdapter`). If the pinned CA leaves the phone, the server is trusted through the
/// system roots only, and fails as "certificate not trusted".
///
/// No certificate check is skipped anywhere: a CA is added as a trust anchor to a [SecurityContext]
/// that keeps the system roots, and the chain and the host name are verified as for any server.
/// There is no `badCertificateCallback` (guards_test). Every failure means fewer anchors, never
/// more.
const MethodChannel trustChannel = MethodChannel('app.budgetowl/trust');

/// How long a read of the phone's store may take before the app goes on without user CAs.
const Duration userTrustTimeout = Duration(seconds: 5);

/// Asks `MainActivity` for the user-installed CAs, as DER. Throws whatever the channel throws; the
/// adapter turns that into "no user CAs".
Future<List<Uint8List>> readUserCaCertificates() async {
  final answer = await trustChannel.invokeListMethod<Object?>('userCaCertificates');
  return [
    for (final certificate in answer ?? const <Object?>[])
      if (certificate is Uint8List) certificate,
  ];
}

/// What a server is pinned to: the SHA-256 of its CA's DER encoding, as 64 lowercase hex digits.
String fingerprintOf(List<int> der) => sha256.convert(der).toString();

/// Whether [value] is a pin [fingerprintOf] could have written. Storage is not trusted to hold only
/// what the app once wrote; anything else is no pin, which is the system roots only.
bool isFingerprint(String? value) => value != null && RegExp(r'^[0-9a-f]{64}$').hasMatch(value);

/// A context trusting [systemRoots] and, when given, the one CA [anchor] (DER). An [anchor] that is
/// not a certificate is not added: the system roots alone.
SecurityContext contextTrusting(
  Uint8List? anchor, {
  required SecurityContext Function() systemRoots,
}) {
  final context = systemRoots();
  if (anchor == null) return context;
  try {
    context.setTrustedCertificatesBytes(utf8.encode(pemOf(anchor)));
    return context;
  } on TlsException {
    return systemRoots();
  }
}

/// The system's trust store, as `HttpClient`'s default context has it.
SecurityContext systemRootsOnly() => SecurityContext(withTrustedRoots: true);

/// [der] as one PEM `CERTIFICATE` block, which `setTrustedCertificatesBytes` reads.
String pemOf(List<int> der) {
  final base64Text = base64.encode(der);
  final lines = [
    for (var i = 0; i < base64Text.length; i += 64)
      base64Text.substring(i, i + 64 > base64Text.length ? base64Text.length : i + 64),
  ];
  return '-----BEGIN CERTIFICATE-----\n${lines.join('\n')}\n-----END CERTIFICATE-----\n';
}
