import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:meta/meta.dart';

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

/// The user-installed CA a server is pinned to: its [fingerprint], and the name it gives itself
/// ([subject], its certificate's subject CN, or O when there is no CN) so the person can see whom
/// the phone believes about this server (S1). The pin is trust-on-first-use: shown, a CA the person
/// does not recognise — an employer's inspection CA, say — can be noticed.
///
/// [subject] is chosen by whoever made the CA. It is **for display only**: cleaned of control and
/// direction-override characters and shortened ([caDisplayName]), never used to decide anything,
/// and never logged — [toString] leaves it out.
@immutable
final class CaPin {
  const CaPin(this.fingerprint, {this.subject});

  final String fingerprint;
  final String? subject;

  @override
  bool operator ==(Object other) =>
      other is CaPin && other.fingerprint == fingerprint && other.subject == subject;

  @override
  int get hashCode => Object.hash(fingerprint, subject);

  @override
  String toString() => 'CaPin(${fingerprint.substring(0, 8)}…)';
}

/// The most of a CA's name shown.
const int caNameMaxLength = 64;

/// The display name in a CA certificate ([der]): its subject's CN, else its O, cleaned for display
/// ([cleanCaName]); null when it has neither or cannot be read. A small DER walk of the
/// certificate's `tbsCertificate` to its `subject` — nothing is verified here, and nothing depends
/// on the answer but what the screen says.
String? caDisplayName(List<int> der) {
  try {
    final tbs = _Der(der).sequence().sequence();
    if (tbs.nextTag == 0xA0) tbs.skip(); // [0] version
    tbs
      ..skip() // serialNumber
      ..skip() // signature
      ..skip() // issuer
      ..skip(); // validity
    final subject = tbs.sequence();
    String? commonName;
    String? organisation;
    while (!subject.done) {
      final rdn = subject.set();
      while (!rdn.done) {
        final attribute = rdn.sequence();
        final oid = attribute.read(0x06);
        final value = attribute.text();
        if (_sameBytes(oid, const [0x55, 0x04, 0x03])) commonName ??= value;
        if (_sameBytes(oid, const [0x55, 0x04, 0x0A])) organisation ??= value;
      }
    }
    return cleanCaName(commonName) ?? cleanCaName(organisation);
  } on Object {
    return null;
  }
}

/// [name] fit to show (T1), by an allow-list: only letters, digits, spaces, punctuation that is
/// not a quotation mark, and at most [_marksPerBase] combining marks on a letter, digit or
/// punctuation mark. Everything else is dropped: controls, format characters (direction marks and
/// overrides, isolates, U+061C, U+00AD, U+180E, U+FFF9–FFFB, tags), private-use and unassigned
/// code points, symbols, every quotation mark and apostrophe look-alike (so the name cannot close
/// a quote it is shown in), and the default-ignorable characters that are letters or marks
/// (Hangul fillers, the grapheme joiner, variation selectors). Whitespace is collapsed to one
/// space; at most [caNameMaxLength] characters are kept. Null when nothing is left.
///
/// The name is still the CA maker's own words: the screen shows it on its own line, as a
/// first-strong isolate, never inside a sentence. Storage is not trusted either: what is read
/// back is cleaned again.
String? cleanCaName(String? name) {
  if (name == null) return null;
  final out = <int>[];
  var space = false;
  var marks = 0;
  var base = false;
  for (final rune in name.runes.take(_caNameRunesRead)) {
    final char = String.fromCharCode(rune);
    if (_ignorable(rune) || _quoteLike(rune) || _quotePunctuation.hasMatch(char)) {
      continue;
    }
    if (_space.hasMatch(char)) {
      space = out.isNotEmpty;
      base = false;
      continue;
    }
    if (_mark.hasMatch(char)) {
      if (base && marks < _marksPerBase) {
        out.add(rune);
        marks++;
      }
      continue;
    }
    if (_letterOrDigit.hasMatch(char) || _punctuation.hasMatch(char)) {
      if (space) out.add(0x20);
      space = false;
      out.add(rune);
      base = true;
      marks = 0;
    }
    // Anything else — a control, format, private-use, unassigned or surrogate code point, a
    // symbol — is dropped.
  }
  if (out.isEmpty) return null;
  if (out.length <= caNameMaxLength) return String.fromCharCodes(out);
  return '${String.fromCharCodes(out.take(caNameMaxLength - 1)).trimRight()}…';
}

/// The most of a subject's text looked at: a certificate can carry a long one.
const int _caNameRunesRead = 512;

/// Combining marks kept on one base character: enough for any real name, too few to stack.
const int _marksPerBase = 2;

final RegExp _letterOrDigit = RegExp(r'^[\p{L}\p{Nd}]$', unicode: true);
final RegExp _punctuation = RegExp(r'^\p{P}$', unicode: true);
final RegExp _quotePunctuation = RegExp(r'^[\p{Pi}\p{Pf}]$', unicode: true);
final RegExp _mark = RegExp(r'^\p{M}$', unicode: true);
final RegExp _space = RegExp(r'^[\p{Zs}\p{Zl}\p{Zp}\t\n\v\f\r]$', unicode: true);

/// Unicode's default-ignorable code points (DerivedCoreProperties), which include letters and
/// marks that show nothing, and U+FFF9–FFFB.
bool _ignorable(int rune) => _inAny(rune, const [
  (0x00AD, 0x00AD),
  (0x034F, 0x034F),
  (0x061C, 0x061C),
  (0x115F, 0x1160),
  (0x17B4, 0x17B5),
  (0x180B, 0x180F),
  (0x200B, 0x200F),
  (0x202A, 0x202E),
  (0x2060, 0x206F),
  (0x3164, 0x3164),
  (0xFE00, 0xFE0F),
  (0xFEFF, 0xFEFF),
  (0xFFA0, 0xFFA0),
  (0xFFF0, 0xFFFB),
  (0x1BCA0, 0x1BCA3),
  (0x1D173, 0x1D17A),
  (0xE0000, 0xE0FFF),
]);

/// Quotation marks and apostrophes that are not initial or final punctuation: the ASCII ones,
/// primes, geresh and gershayim, modifier-letter apostrophes, saltillo, low-9 quotes, CJK corner
/// brackets and their fullwidth and vertical forms.
bool _quoteLike(int rune) => _inAny(rune, const [
  (0x0022, 0x0022),
  (0x0027, 0x0027),
  (0x0060, 0x0060),
  (0x00B4, 0x00B4),
  (0x02B9, 0x02BD),
  (0x02C8, 0x02C8),
  (0x02EE, 0x02EE),
  (0x05F3, 0x05F4),
  (0x2018, 0x201F),
  (0x2032, 0x2037),
  (0x2E42, 0x2E42),
  (0x300C, 0x300F),
  (0x301D, 0x301F),
  (0xA78B, 0xA78C),
  (0xFE41, 0xFE44),
  (0xFF02, 0xFF02),
  (0xFF07, 0xFF07),
  (0xFF62, 0xFF63),
]);

bool _inAny(int rune, List<(int, int)> ranges) {
  for (final (low, high) in ranges) {
    if (rune >= low && rune <= high) return true;
  }
  return false;
}

bool _sameBytes(List<int> a, List<int> b) {
  if (a.length != b.length) return false;
  for (var i = 0; i < a.length; i++) {
    if (a[i] != b[i]) return false;
  }
  return true;
}

/// Just enough DER to reach a certificate's subject: tag, length, content. Any malformation throws.
final class _Der {
  _Der(List<int> bytes) : this._(bytes, 0, bytes.length);

  _Der._(this._bytes, this._at, this._end);

  final List<int> _bytes;
  int _at;
  final int _end;

  bool get done => _at >= _end;

  int get nextTag {
    if (done) throw const FormatException('DER: nothing left');
    return _bytes[_at];
  }

  /// The next element's tag and content bounds, and moves past it.
  (int tag, int start, int end) _next() {
    if (_at + 2 > _end) throw const FormatException('DER: truncated');
    final tag = _bytes[_at++];
    // A tag number of 31 or more takes more bytes; no certificate field uses one.
    if (tag & 0x1f == 0x1f) throw const FormatException('DER: multi-byte tag');
    var length = _bytes[_at++];
    if (length & 0x80 != 0) {
      final count = length & 0x7f;
      if (count == 0 || count > 4 || _at + count > _end) {
        throw const FormatException('DER: bad length');
      }
      length = 0;
      for (var i = 0; i < count; i++) {
        length = (length << 8) | _bytes[_at++];
      }
    }
    final start = _at;
    final end = start + length;
    if (end > _end || end < start) throw const FormatException('DER: overruns');
    _at = end;
    return (tag, start, end);
  }

  _Der _inside(int expected) {
    final (tag, start, end) = _next();
    if (tag != expected) throw const FormatException('DER: unexpected tag');
    return _Der._(_bytes, start, end);
  }

  _Der sequence() => _inside(0x30);

  _Der set() => _inside(0x31);

  void skip() => _next();

  List<int> read(int expected) {
    final (tag, start, end) = _next();
    if (tag != expected) throw const FormatException('DER: unexpected tag');
    return _bytes.sublist(start, end);
  }

  /// A directory string: UTF8String, PrintableString, IA5String, T61String, BMPString or
  /// UniversalString.
  String text() {
    final (tag, start, end) = _next();
    final content = _bytes.sublist(start, end);
    switch (tag) {
      case 0x0C:
        return utf8.decode(content);
      case 0x13 || 0x16 || 0x14:
        return latin1.decode(content);
      case 0x1E:
        if (content.length.isOdd) throw const FormatException('DER: BMPString');
        return String.fromCharCodes([
          for (var i = 0; i < content.length; i += 2) (content[i] << 8) | content[i + 1],
        ]);
      case 0x1C:
        if (content.length % 4 != 0) throw const FormatException('DER: UniversalString');
        final codePoints = [
          for (var i = 0; i < content.length; i += 4)
            (content[i] << 24) | (content[i + 1] << 16) | (content[i + 2] << 8) | content[i + 3],
        ];
        if (codePoints.any((c) => c > 0x10FFFF)) {
          throw const FormatException('DER: UniversalString beyond Unicode');
        }
        return String.fromCharCodes(codePoints);
      default:
        throw const FormatException('DER: not a directory string');
    }
  }
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
