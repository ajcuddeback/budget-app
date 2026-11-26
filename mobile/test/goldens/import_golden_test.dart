import 'package:budget_owl/features/import/data/imports_repository.dart';
import 'package:budget_owl/features/import/data/statement_file.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:material_ui/material_ui.dart';

import '../features/import/import_test.dart' show FakePicker, recordedFile;
import '../support/fixture_server.dart';
import '../support/harness.dart';
import 'golden_harness.dart';

/// Import a statement, from the exchanges the real API recorded (MobileWeeklyFixtureIT), with the
/// platform picker stood in for. `tools/mobile-check.sh` renders them; **read them**.
void main() => runGoldens(scenarios);

Future<TestPhone> _import(
  WidgetTester tester,
  Variant v, {
  String state = 'ready',
  String who = 'owner',
  String? format,
  FixtureServer? server,
}) async {
  final phone = TestPhone(server: server ?? FixtureServer.load('import', state: state))
    ..signedIn(who);
  await pumpApp(
    tester,
    phone,
    themeMode: v.dark ? ThemeMode.dark : ThemeMode.light,
    direction: v.rtl ? TextDirection.rtl : null,
    textScale: v.textScale,
    overrides: [
      if (format != null)
        statementPickerProvider.overrideWithValue(
          FakePicker(
            PickedStatement(
              bytes: recordedFile(format),
              name: format == 'CSV' ? 'export.csv' : 'aug.ofx',
            ),
          ),
        ),
    ],
  );
  await tester.tap(find.byIcon(Icons.more_horiz));
  await settle(tester);
  await tapVisible(tester, find.byIcon(Icons.file_upload_outlined));
  return phone;
}

Future<void> _pickAndPreview(WidgetTester tester) async {
  await tapVisible(tester, find.text('Choose an account'));
  await tapVisible(tester, find.text('Everyday Checking •4417').last);
  await tapVisible(tester, find.text('Choose a file'));
  await tapVisible(tester, find.text('Preview the import'));
}

Future<void> _show(WidgetTester tester, Finder target) async {
  await revealIn(tester, target);
  await tester.drag(target, const Offset(0, 300));
  await settle(tester);
}

final List<Scenario> scenarios = [
  const Scenario('import', everyWay, _import),

  Scenario('import-chosen', bothThemes, (tester, v) async {
    final phone = await _import(tester, v, format: 'OFX');
    await tapVisible(tester, find.text('Choose an account'));
    await tapVisible(tester, find.text('Everyday Checking •4417').last);
    await tapVisible(tester, find.text('Choose a file'));
    return phone;
  }),

  Scenario('import-preview', everyWay, (tester, v) async {
    final phone = await _import(tester, v, format: 'OFX');
    await _pickAndPreview(tester);
    await _show(tester, find.text('What will be added'));
    return phone;
  }),

  Scenario('import-preview-attention', bothThemes, (tester, v) async {
    final phone = await _import(tester, v, format: 'OFX');
    await _pickAndPreview(tester);
    await tapVisible(tester, find.byType(CheckboxListTile).first);
    await _show(tester, find.text('Needs a look'));
    return phone;
  }),

  Scenario('import-mapping', bothThemes, (tester, v) async {
    final phone = await _import(tester, v, format: 'CSV');
    await _pickAndPreview(tester);
    await _show(tester, find.text('How to read it'));
    return phone;
  }),

  Scenario('import-done', bothThemes, (tester, v) async {
    final phone = await _import(tester, v, format: 'OFX');
    await _pickAndPreview(tester);
    await tapVisible(tester, find.byType(CheckboxListTile).first);
    await tapVisible(tester, find.text('Import 4 transactions into Everyday Checking •4417'));
    await tester.drag(find.text('Import a statement').first, const Offset(0, 3000));
    await settle(tester);
    return phone;
  }),

  Scenario('import-history', bothThemes, (tester, v) async {
    final phone = await _import(tester, v, state: 'imported');
    await _show(tester, find.text('Recent imports'));
    return phone;
  }),

  Scenario('import-stale', bothThemes, (tester, v) async {
    final phone = await _import(tester, v, state: 'undone', format: 'OFX');
    await _pickAndPreview(tester);
    await tapVisible(tester, find.text('Import 3 transactions into Everyday Checking •4417'));
    await _show(tester, find.text('What will be added'));
    return phone;
  }),

  Scenario('import-viewer', bothThemes, (tester, v) {
    return _import(tester, v, state: 'viewer', who: 'viewer');
  }),

  Scenario('import-offline-stale', bothThemes, (tester, v) async {
    final phone = await _import(tester, v, state: 'imported');
    (phone.server as FixtureServer).offline = true;
    phone.now = demoNow.add(const Duration(hours: 2));
    final container = ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
    await tester.runAsync(() => container.read(importHistoryProvider.notifier).refresh());
    await settle(tester);
    return phone;
  }),

  Scenario('import-error', bothThemes, (tester, v) {
    final server = FixtureServer.load('import', state: 'ready')
      ..offlineAfter = 'GET /api/transactions';
    return _import(tester, v, server: server);
  }),
];
