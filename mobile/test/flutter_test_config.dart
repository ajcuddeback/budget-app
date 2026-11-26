import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

/// Runs before every test file: loads the app's real fonts, so goldens are drawn in Figtree and
/// Caprasimo — the faces a member sees — rather than the test font's boxes. Material's icon font
/// comes from the same manifest.
Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  TestWidgetsFlutterBinding.ensureInitialized();
  final manifest = await rootBundle.loadString('FontManifest.json');
  for (final family in jsonDecode(manifest) as List<Object?>) {
    final entry = family! as Map<String, Object?>;
    final loader = FontLoader(entry['family']! as String);
    for (final font in entry['fonts']! as List<Object?>) {
      final asset = (font! as Map<String, Object?>)['asset']! as String;
      loader.addFont(rootBundle.load(asset));
    }
    await loader.load();
  }
  await testMain();
}
