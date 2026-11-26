import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Whether a write may be sent now (mobile-app.md, Writes).
///
/// Taken from what actually happened, not from the operating system's idea of connectivity: a
/// phone on café Wi-Fi behind a captive portal is "online" and cannot reach anybody's server, and
/// a phone on a tailnet with no internet can. A write is offered only after a request has got
/// through **and** the server's `GET /api/meta` has confirmed, this launch, that it speaks an API
/// this app was written for.
enum Reachability {
  /// Nothing has been asked yet this session.
  unknown,

  /// The last request got an HTTP answer — any status, including an error — and the server's
  /// version was confirmed this launch.
  reached,

  /// The last request never left the phone: no network, or the name did not resolve.
  offline,

  /// The last request found nobody answering: refused, or timed out.
  noAnswer,

  /// The last request reached the server, but its version has not been confirmed this launch:
  /// `/api/meta` has not answered yet, or failed (busy, timed out, anything). Reads go on, from the
  /// cache and from the server; writes stay off, and the check is tried again on the next request
  /// that reaches the server (`SessionController`).
  unconfirmed;

  bool get canWrite => this == Reachability.reached;
}

/// The last request's outcome, combined with whether the version check has passed this launch.
///
/// The check is a gate, not a hint: it **fails closed**. Until it passes, a request that reached
/// the server shows as [Reachability.unconfirmed], so `canWriteNow` and every writer's
/// `reachabilityProvider.canWrite` check say no — without a change in any of them — and the
/// `ApiClient` refuses a write at the transport besides.
final class ReachabilityNotifier extends Notifier<Reachability> {
  Reachability _last = Reachability.unknown;
  bool _versionConfirmed = false;

  @override
  Reachability build() => Reachability.unknown;

  /// Whether `/api/meta` has confirmed the current server's version this launch.
  bool get versionConfirmed => _versionConfirmed;

  // A setter would hide that this is driven by the HTTP layer's outcomes, one request at a time.
  void record(Reachability outcome) {
    _last = outcome;
    _publish();
  }

  /// The version check passed for the current server.
  void confirmVersion() {
    _versionConfirmed = true;
    _publish();
  }

  /// The version is not (or no longer) known to be one this app speaks: a new launch, a new
  /// server, or a check that failed.
  void unconfirmVersion() {
    _versionConfirmed = false;
    _publish();
  }

  void _publish() {
    final reached = _last == Reachability.reached || _last == Reachability.unconfirmed;
    final next = reached
        ? (_versionConfirmed ? Reachability.reached : Reachability.unconfirmed)
        : _last;
    if (state != next) state = next;
  }
}

final reachabilityProvider = NotifierProvider<ReachabilityNotifier, Reachability>(
  ReachabilityNotifier.new,
);
