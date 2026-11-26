import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

/// The certificate authorities the person installed on an **Android** phone, for Dart's TLS.
///
/// ADR-0042's promise is that a self-hoster with a private CA installs it on the phone. iOS hands
/// an enabled CA profile to every TLS stack; Android does not: `dart:io` reads only the system
/// store, whatever `network_security_config.xml` says. This closes that gap without relaxing
/// anything: the app's own network security config already trusts user CAs
/// (`<certificates src="user" />`, pinned by `platform_config_test`), and this gives Dart exactly
/// that set, read from `AndroidCAStore` (aliases `user:…`) by `MainActivity`.
///
/// - **No certificate check is skipped.** The certificates are added as trust anchors to a
///   [SecurityContext] that also keeps the system roots; the chain and the host name are verified
///   as for any other server. There is no `badCertificateCallback` anywhere (guards_test).
/// - **Fails closed.** A store that cannot be read, a channel that does not answer in time, an
///   empty answer or bytes that are not a certificate all mean the system roots only — the
///   behaviour before this existed — and never a crash.
const MethodChannel trustChannel = MethodChannel('app.budgetowl/trust');

/// How long the first request waits for the phone's store before going on with system roots.
const Duration userTrustTimeout = Duration(seconds: 5);

/// Asks `MainActivity` for the user-installed CAs, as DER. Throws whatever the channel throws;
/// [phoneTrustContext] turns that into "system roots only".
Future<List<Uint8List>> readUserCaCertificates() async {
  final answer = await trustChannel.invokeListMethod<Object?>('userCaCertificates');
  return [
    for (final certificate in answer ?? const <Object?>[])
      if (certificate is Uint8List) certificate,
  ];
}

/// The TLS context for the app's one transport: the system roots plus whatever [read] returns, or
/// **null for the system roots alone** — `HttpClient`'s default context, exactly as before — when
/// it fails, times out, or adds nothing.
Future<SecurityContext?> phoneTrustContext(
  Future<List<Uint8List>> Function() read, {
  Duration timeout = userTrustTimeout,
}) async {
  final List<Uint8List> certificates;
  try {
    certificates = await read().timeout(timeout);
  } on Object {
    return null;
  }
  return trustContextWith(certificates);
}

/// A context trusting the system roots **and** each DER certificate in [certificates], or null
/// when none of them could be added. One that is not a certificate is skipped; the others stand.
SecurityContext? trustContextWith(List<Uint8List> certificates) {
  if (certificates.isEmpty) return null;
  final context = SecurityContext(withTrustedRoots: true);
  var added = 0;
  for (final der in certificates) {
    try {
      context.setTrustedCertificatesBytes(utf8.encode(pemOf(der)));
      added++;
    } on TlsException {
      // Not a certificate: trusting less than was offered is the safe direction.
    }
  }
  return added == 0 ? null : context;
}

/// [der] as one PEM `CERTIFICATE` block, which `setTrustedCertificatesBytes` reads.
String pemOf(List<int> der) {
  final base64Text = base64.encode(der);
  final lines = [
    for (var i = 0; i < base64Text.length; i += 64)
      base64Text.substring(i, i + 64 > base64Text.length ? base64Text.length : i + 64),
  ];
  return '-----BEGIN CERTIFICATE-----\n${lines.join('\n')}\n-----END CERTIFICATE-----\n';
}
