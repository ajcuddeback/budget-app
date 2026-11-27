import 'package:budget_owl/core/http/http_adapter.dart';
import 'package:budget_owl/core/http/user_trust.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/server/server_check.dart';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'fixture_server.dart';

/// A transport that is a [ServerTrust], over the fixture server: it records what the session
/// asked of it, answers the connect-time check with [pinOnConnect], and says whatever a test sets
/// in [installed] about the pinned CA being on the phone.
final class FakeTrust implements ServerTrust {
  FakeTrust(this._server, {this.pinOnConnect, bool? installed})
    : installed = ValueNotifier(installed);

  final FixtureServer _server;
  final CaPin? pinOnConnect;
  final List<String> events = [];

  /// Whether the pinned CA is on the phone, as a test decides.
  final ValueNotifier<bool?> installed;

  @override
  ValueListenable<bool?> get pinnedCaInstalled => installed;

  @override
  Future<CaPin?> checkAndPin(ServerAddress address) async {
    events.add('checkAndPin');
    await ServerCheck(_server).check(address);
    return pinOnConnect;
  }

  @override
  void pinTo(String? fingerprint) => events.add('pinTo $fingerprint');

  @override
  Future<void> reloadPhoneStore() async => events.add('reload');

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) {
    events.add('fetch ${options.path}');
    return _server.fetch(options, requestStream, cancelFuture);
  }

  @override
  void close({bool force = false}) => _server.close(force: force);
}
