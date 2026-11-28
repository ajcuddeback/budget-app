import 'dart:io';

import 'package:dio/dio.dart';

/// Why a connection to a server failed before any HTTP answer came back.
///
/// The connect screen gives each of these its own sentence (mobile-app.md: "host not found,
/// connection refused or timed out, certificate not trusted, certificate for a different name").
/// "Something went wrong" is the failure to tell them apart.
enum TransportFailure {
  /// The name does not resolve — a typo, or a LAN name this network cannot see.
  hostNotFound,

  /// No network at all on this phone.
  noNetwork,

  /// Something is at the address but refused the connection, or nothing answered in time.
  unreachable,

  /// The certificate chain does not lead to an authority this phone trusts.
  certificateUntrusted,

  /// The certificate is valid, but for a different name.
  certificateWrongName,

  /// The connection opened and then failed in a way none of the above describes.
  other,
}

/// Classifies what Dio (or `dart:io` beneath it) threw.
///
/// Certificates are verified by the platform and **never** overridden here: there is no
/// `badCertificateCallback` anywhere in this app, so a handshake failure is always a refusal, and
/// this function only decides which sentence explains it.
TransportFailure classifyTransportFailure(Object error) {
  if (error is DioException) {
    switch (error.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
      case DioExceptionType.transformTimeout:
        return TransportFailure.unreachable;
      case DioExceptionType.badCertificate:
        return TransportFailure.certificateUntrusted;
      case DioExceptionType.connectionError:
      case DioExceptionType.unknown:
      case DioExceptionType.cancel:
      case DioExceptionType.badResponse:
        final inner = error.error;
        if (inner != null && inner != error) return classifyTransportFailure(inner);
        return TransportFailure.other;
    }
  }
  if (error is TlsException) {
    final message = '${error.message} ${error.osError?.message ?? ''}'.toLowerCase();
    if (message.contains('hostname mismatch') ||
        message.contains('host name mismatch') ||
        message.contains('does not match')) {
      return TransportFailure.certificateWrongName;
    }
    return TransportFailure.certificateUntrusted;
  }
  if (error is SocketException) {
    final message = '${error.message} ${error.osError?.message ?? ''}'.toLowerCase();
    final code = error.osError?.errorCode;
    if (message.contains('failed host lookup') ||
        message.contains('no address associated') ||
        message.contains('name or service not known') ||
        message.contains('nodename nor servname')) {
      return TransportFailure.hostNotFound;
    }
    if (message.contains('network is unreachable') || code == 101 || code == 51) {
      return TransportFailure.noNetwork;
    }
    return TransportFailure.unreachable;
  }
  if (error is HttpException) return TransportFailure.unreachable;
  return TransportFailure.other;
}
