import 'dart:async';
import 'dart:io';

import 'package:budget_owl/core/config/environment.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';

/// Light, dark, or whatever the phone is set to — the web's theme override, kept **on this phone**
/// as the web keeps it in one browser (`ThemeService`), and never sent to the server.
enum ThemeChoice {
  system,
  light,
  dark;

  ThemeMode get mode => switch (this) {
    ThemeChoice.system => ThemeMode.system,
    ThemeChoice.light => ThemeMode.light,
    ThemeChoice.dark => ThemeMode.dark,
  };

  /// The stored word, or [system] for anything else — a file somebody edited, or nothing at all.
  static ThemeChoice parse(String? stored) => switch (stored?.trim()) {
    'light' => ThemeChoice.light,
    'dark' => ThemeChoice.dark,
    _ => ThemeChoice.system,
  };
}

/// The choice, read once at launch and written whenever it changes.
///
/// It lives in a one-word file in Application Support, beside the install marker: kept for the
/// life of the installation, gone with the app, and holding nothing but `light`, `dark` or
/// `system`. Not in secure storage, which is for credentials and is wiped on every sign-out — a
/// theme is not a secret and, as on the web, outlives a sign-out. Not in `SharedPreferences`,
/// which the architecture guards keep out of the app altogether (mobile-app.md, Data model).
final class ThemeChoiceNotifier extends Notifier<ThemeChoice> {
  static const String fileName = 'budget_owl.theme';

  @override
  ThemeChoice build() {
    unawaited(_load());
    return ThemeChoice.system;
  }

  Future<File> get _file async =>
      File('${(await ref.read(supportDirectoryProvider.future)).path}/$fileName');

  Future<void> _load() async {
    try {
      final file = await _file;
      if (!file.existsSync()) return;
      final stored = ThemeChoice.parse(await file.readAsString());
      if (ref.mounted) state = stored;
    } on Object {
      // An unreadable file is no preference: the phone's own setting stands.
    }
  }

  /// Applies [choice] at once, then keeps it. A theme is not worth an error if the write fails.
  Future<void> choose(ThemeChoice choice) async {
    state = choice;
    try {
      final file = await _file;
      await file.create(recursive: true);
      await file.writeAsString(choice.name, flush: true);
    } on Object {
      // The choice still holds for this run.
    }
  }
}

final themeChoiceProvider = NotifierProvider<ThemeChoiceNotifier, ThemeChoice>(
  ThemeChoiceNotifier.new,
);
