import 'dart:convert';

import 'package:budget_owl/core/http/transport_failure.dart';
import 'package:budget_owl/core/server/server_address.dart';
import 'package:dio/dio.dart';

/// The API version this app was written against (`GET /api/meta`, ADR-0042).
const int clientApiVersion = 1;

/// Why a server could not be used, each with its own sentence on the connect screen.
enum ConnectProblem {
  hostNotFound,
  unreachable,
  certificateUntrusted,
  certificateWrongName,
  notBudgetOwl,
  versionMismatch,
  transport,
}

final class ConnectFailure implements Exception {
  const ConnectFailure(this.problem, {this.serverApiVersion});

  final ConnectProblem problem;

  /// For [ConnectProblem.versionMismatch]: what the server said it speaks.
  final int? serverApiVersion;

  @override
  String toString() => 'ConnectFailure(${problem.name})';
}

/// A server that answered `/api/meta` as Budget Owl, in a version this app can talk to.
final class ServerIdentity {
  const ServerIdentity({required this.apiVersion});
  final int apiVersion;
}

/// Asks a server who it is, **before** anything else is sent to it.
///
/// The password goes only to an address that answered this over TLS the platform verified
/// (mobile-app.md, attack 4). No bearer, no cookie, no redirect followed, and at most a few
/// kilobytes read: a server that is not Budget Owl gets nothing and costs nothing.
final class ServerCheck {
  ServerCheck(this._adapter);

  final HttpClientAdapter _adapter;

  /// The most of a meta response this reads. The real one is 64 bytes.
  static const int maximumBytes = 4096;

  Future<ServerIdentity> check(ServerAddress address) async {
    final dio = Dio(
      BaseOptions(
        baseUrl: address.stored,
        followRedirects: false,
        maxRedirects: 0,
        connectTimeout: const Duration(seconds: 10),
        receiveTimeout: const Duration(seconds: 10),
        responseType: ResponseType.stream,
        validateStatus: (_) => true,
        headers: {'Accept': 'application/json'},
      ),
    )..httpClientAdapter = _adapter;

    final Response<ResponseBody> response;
    try {
      response = await dio.get<ResponseBody>('/api/meta');
    } on DioException catch (failure) {
      throw ConnectFailure(switch (classifyTransportFailure(failure)) {
        TransportFailure.hostNotFound || TransportFailure.noNetwork => ConnectProblem.hostNotFound,
        TransportFailure.unreachable => ConnectProblem.unreachable,
        TransportFailure.certificateUntrusted => ConnectProblem.certificateUntrusted,
        TransportFailure.certificateWrongName => ConnectProblem.certificateWrongName,
        TransportFailure.other => ConnectProblem.transport,
      });
    } finally {
      dio.close();
    }

    if (response.statusCode != 200) {
      throw const ConnectFailure(ConnectProblem.notBudgetOwl);
    }
    final body = response.data;
    if (body == null) throw const ConnectFailure(ConnectProblem.notBudgetOwl);
    final bytes = <int>[];
    await for (final chunk in body.stream) {
      bytes.addAll(chunk);
      if (bytes.length > maximumBytes) {
        throw const ConnectFailure(ConnectProblem.notBudgetOwl);
      }
    }
    return identify(bytes);
  }

  /// Whether [bytes] are a Budget Owl meta body this app can work with.
  static ServerIdentity identify(List<int> bytes) {
    final Object? decoded;
    try {
      decoded = jsonDecode(utf8.decode(bytes));
    } on FormatException {
      throw const ConnectFailure(ConnectProblem.notBudgetOwl);
    }
    if (decoded is! Map<String, Object?> || decoded['app'] != 'budget-owl') {
      throw const ConnectFailure(ConnectProblem.notBudgetOwl);
    }
    final apiVersion = decoded['apiVersion'];
    final minimumClient = decoded['minimumClientApiVersion'];
    if (apiVersion is! int || minimumClient is! int) {
      throw const ConnectFailure(ConnectProblem.notBudgetOwl);
    }
    // The server must speak at least the API this app was written for, and must still serve it.
    if (apiVersion < clientApiVersion || minimumClient > clientApiVersion) {
      throw ConnectFailure(ConnectProblem.versionMismatch, serverApiVersion: apiVersion);
    }
    return ServerIdentity(apiVersion: apiVersion);
  }
}
