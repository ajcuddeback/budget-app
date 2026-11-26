import 'package:budget_owl/app.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// Budget Owl for iOS and Android. It ships knowing no server (ADR-0016): the first screen asks
/// for one. No analytics, no crash reporting, nothing that phones anybody but that server.
void main() {
  runApp(const ProviderScope(retry: noRetry, child: BudgetOwlApp()));
}
