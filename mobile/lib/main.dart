import 'package:budget_owl/app.dart';
import 'package:budget_owl/core/http/cleartext_policy.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Budget Owl for iOS and Android. It ships knowing no server (ADR-0016): the first screen asks
/// for one. No analytics, no crash reporting, nothing that phones anybody but that server.
///
/// A release build runs inside the zone that makes the engine refuse `http://` at the socket
/// (cleartext_policy.dart); a debug build keeps its local development exception.
void main() {
  runWithCleartextPolicy(
    () => runApp(const ProviderScope(retry: noRetry, child: BudgetOwlApp())),
    allowLocalCleartext: kDebugMode,
  );
}
