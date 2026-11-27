import 'package:budget_owl/core/platform/sensitive_clipboard.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/harness.dart';

/// L2 and U3, the Dart side: a credential goes to the clipboard through the native
/// `setSensitive` (MainActivity on Android, AppDelegate on iOS), which returns a receipt, and is
/// taken back with that receipt. **Nothing here ever reads the clipboard**: whether it still holds
/// our copy is decided natively, from the clip's timestamp or `changeCount`, so no "pasted from"
/// notice or "Allow Paste?" prompt is triggered. The device side needs checking on a device.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const link = 'https://owl.example.test/join/fixture-invitation-edsger';

  late List<MethodCall> native;
  late List<String> plain;
  late String nativeAnswer;

  setUp(() {
    native = [];
    plain = [];
    nativeAnswer = 'cleared';
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      ..setMockMethodCallHandler(clipboardChannel, (call) async {
        native.add(call);
        return call.method == 'setSensitive' ? 7 : nativeAnswer;
      })
      ..setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.getData' || call.method == 'Clipboard.hasStrings') {
          fail('the clipboard is never read (U3)');
        }
        if (call.method == 'Clipboard.setData') plain.add(call.method);
        return null;
      });
    addTearDown(() {
      messenger
        ..setMockMethodCallHandler(clipboardChannel, null)
        ..setMockMethodCallHandler(SystemChannels.platform, null);
    });
  });

  for (final platform in [TargetPlatform.android, TargetPlatform.iOS]) {
    group('on ${platform.name}, through the native side', () {
      final clipboard = SensitiveClipboard(SystemClipboardPlatform(platform));

      test('a copy is the native sensitive set, and its receipt comes back', () async {
        await clipboard.copy(link);

        expect(native.single.method, 'setSensitive');
        expect(native.single.arguments, {'text': link});
        expect(plain, isEmpty, reason: 'never the plain clipboard');
      });

      test('taken back by its receipt, never by reading the clipboard', () async {
        final copied = await clipboard.copy(link);
        final outcome = await clipboard.takeBack(copied);

        expect(native.map((c) => c.method), ['setSensitive', 'clearIfUnchanged']);
        expect(native.last.arguments, {'receipt': 7}, reason: 'the receipt, not the link');
        expect(outcome, ClearOutcome.cleared);
      });

      test('what the native side says is reported, and a failure throws nothing', () async {
        final copied = await clipboard.copy(link);
        nativeAnswer = 'changed';
        expect(await clipboard.takeBack(copied), ClearOutcome.changed);
        nativeAnswer = 'deferred';
        expect(await clipboard.takeBack(copied), ClearOutcome.deferred);

        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
          clipboardChannel,
          (call) async => throw PlatformException(code: 'unavailable'),
        );
        expect(await clipboard.takeBack(copied), isNull);
      });
    });
  }

  test('a platform with no native side: a plain copy, never read and never taken back', () async {
    const clipboard = SensitiveClipboard(SystemClipboardPlatform(TargetPlatform.linux));

    final copied = await clipboard.copy(link);
    expect(await clipboard.takeBack(copied), isNull);
    expect(plain, ['Clipboard.setData']);
    expect(native, isEmpty);
  });

  group('the test phone clipboard decides as the device does', () {
    test('still ours: cleared', () async {
      final phone = MemoryClipboard();
      final clipboard = SensitiveClipboard(phone);
      final copied = await clipboard.copy(link);
      expect((phone.text, phone.sensitive), (link, true));
      expect(await clipboard.takeBack(copied), ClearOutcome.cleared);
      expect(phone.text, isNull);
    });

    test('copied over since, even with the same text: left alone', () async {
      final phone = MemoryClipboard();
      final clipboard = SensitiveClipboard(phone);
      final copied = await clipboard.copy(link);
      phone.copiedElsewhere(link);
      expect(await clipboard.takeBack(copied), ClearOutcome.changed);
      expect(phone.text, link);
    });

    test('an older copy of ours is not the one on the clipboard', () async {
      final phone = MemoryClipboard();
      final clipboard = SensitiveClipboard(phone);
      final first = await clipboard.copy('first');
      await clipboard.copy('second');
      expect(await clipboard.takeBack(first), ClearOutcome.changed);
      expect(phone.text, 'second');
    });
  });
}
