import 'dart:io';

import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/http/api_client.dart' show debugLogFailure;
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// A session is being ended on this phone, and is not yet known to have been (round 7, W2).
///
/// Placed in Application Support — beside the install marker, as it is — **before** any path that
/// ends a session wipes anything (sign-out, a `401`, a failed sign-in, a failed start-up), and
/// removed only once secure storage confirms the token gone. So an app closed on "This phone
/// couldn't finish signing out" does not resume signed in at its next launch: start-up finds the
/// marker first and ends the session again — wipe, no token loaded, the delete tried again —
/// before it reads anything else.
///
/// It also says whether the sign-out was a change of server, so the next attempt does the same.
/// Its file work is synchronous: one word, written before the wipe begins and certain to be on
/// disk when it does. Every operation is best effort: a marker that cannot be written or read is logged (debug
/// builds) and the sign-out goes on — the person is still told when it did not finish.
final class SignOutMarker {
  SignOutMarker(this._directory);

  static const String fileName = 'budget_owl.sign_out_pending';
  static const String _forgetServer = 'forget-server';
  static const String _keepServer = 'keep-server';

  final Future<Directory> Function() _directory;

  Future<File> get _file async => File('${(await _directory()).path}/$fileName');

  /// The pending sign-out, if any: whether it forgets the server. Null when none is pending; a
  /// marker that cannot be read counts as pending, keeping the server.
  Future<({bool forgetServer})?> read() async {
    try {
      final file = await _file;
      if (!file.existsSync()) return null;
      return (forgetServer: file.readAsStringSync().trim() == _forgetServer);
    } on Object catch (error) {
      debugLogFailure('reading the sign-out marker', error);
      return (forgetServer: false);
    }
  }

  /// Marks a sign-out as pending. A pending change of server stays one.
  Future<void> place({required bool forgetServer}) async {
    try {
      final file = await _file;
      final forget = forgetServer || (await read())?.forgetServer == true;
      file
        ..createSync(recursive: true)
        ..writeAsStringSync(forget ? _forgetServer : _keepServer, flush: true);
    } on Object catch (error) {
      debugLogFailure('writing the sign-out marker', error);
    }
  }

  /// The sign-out finished: the token is confirmed gone, or a new sign-in has replaced it.
  Future<void> remove() async {
    try {
      final file = await _file;
      if (file.existsSync()) file.deleteSync();
    } on Object catch (error) {
      debugLogFailure('removing the sign-out marker', error);
    }
  }
}

final signOutMarkerProvider = Provider<SignOutMarker>(
  (ref) => SignOutMarker(() => ref.read(supportDirectoryProvider.future)),
);
