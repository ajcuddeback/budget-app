import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Whether the last request reached the server (mobile-app.md, Writes).
///
/// Taken from what actually happened, not from the operating system's idea of connectivity: a
/// phone on café Wi-Fi behind a captive portal is "online" and cannot reach anybody's server, and
/// a phone on a tailnet with no internet can. A write is offered only after a request has got
/// through.
enum Reachability {
  /// Nothing has been asked yet this session.
  unknown,

  /// The last request got an HTTP answer — any status, including an error.
  reached,

  /// The last request never left the phone: no network, or the name did not resolve.
  offline,

  /// The last request found nobody answering: refused, or timed out.
  noAnswer;

  bool get canWrite => this == Reachability.reached;
}

final class ReachabilityNotifier extends Notifier<Reachability> {
  @override
  Reachability build() => Reachability.unknown;

  // A setter would hide that this is driven by the HTTP layer's outcomes, one request at a time.
  void record(Reachability outcome) {
    if (state != outcome) state = outcome;
  }
}

final reachabilityProvider = NotifierProvider<ReachabilityNotifier, Reachability>(
  ReachabilityNotifier.new,
);
