import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../support/fixture_server.dart';
import '../support/harness.dart';
import 'golden_harness.dart';

/// Shared steps for the Bills & income, debt plan and goals goldens.

/// Scrolls the screen in front until [text] is at the top of the view.
Future<void> scrollTo(WidgetTester tester, String text) async {
  await revealIn(tester, find.text(text).first);
  await tester.drag(find.byType(Scrollable).last, const Offset(0, 120));
  await settle(tester);
  await revealIn(tester, find.text(text).first);
}

/// The phone in [state] of [file], on the screen [open] leads to.
Future<TestPhone> plansPhone(
  WidgetTester tester,
  Variant v, {
  required String file,
  required Future<void> Function(WidgetTester tester) open,
  String state = 'plans',
  String who = 'owner',
}) async {
  final phone = await signedInPhone(tester, v, file: file, state: state, who: who);
  await open(tester);
  return phone;
}

/// The screen's reads fail before it has any copy: the true message and a way to try again.
Future<TestPhone> errorPhone(
  WidgetTester tester,
  Variant v, {
  required String file,
  required Future<void> Function(WidgetTester tester) beforeOpen,
  required Future<void> Function(WidgetTester tester) open,
}) async {
  final phone = await signedInPhone(tester, v, file: file, state: 'plans');
  await beforeOpen(tester);
  (phone.server as FixtureServer).offline = true;
  await open(tester);
  return phone;
}

/// Two hours on, offline, and a refresh that fails: the stale label over the cached copy.
Future<void> goStale(WidgetTester tester, TestPhone phone) async {
  (phone.server as FixtureServer).offline = true;
  phone.now = demoNow.add(const Duration(hours: 2));
  await tester.fling(find.byType(ListView).hitTestable().first, const Offset(0, 400), 1000);
  await settle(tester);
}

/// The first widget matching [target] laid out below the text [anchor] — one occurrence's "Correct
/// this", say — without counting buttons from the top of the page.
Finder below(WidgetTester tester, Finder target, String anchor) {
  final top = tester.getTopLeft(find.text(anchor).first).dy;
  Element? best;
  double? bestY;
  for (final element in target.evaluate()) {
    final box = element.renderObject! as RenderBox;
    final y = box.localToGlobal(Offset.zero).dy;
    if (y > top && (bestY == null || y < bestY)) {
      best = element;
      bestY = y;
    }
  }
  if (best == null) throw StateError('nothing below $anchor');
  final chosen = best;
  return find.byElementPredicate((e) => e == chosen);
}

/// Lets a sheet's preview settle and its answer arrive.
Future<void> settlePreview(WidgetTester tester) async {
  await tester.pump(const Duration(milliseconds: 400));
  await settle(tester);
}
