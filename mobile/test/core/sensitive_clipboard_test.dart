import 'package:budget_owl/core/platform/sensitive_clipboard.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../support/harness.dart';

/// L2, the Dart side: a credential goes to the clipboard through MainActivity's `setSensitive` on
/// Android, and is taken back only while the clipboard still holds it. The device side —
/// `EXTRA_IS_SENSITIVE` on Android 13+, and what the system then shows — needs checking on a device.
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const link = 'https://owl.example.test/join/fixture-invitation-edsger';

  group('on Android, through MainActivity', () {
    late List<MethodCall> native;
    late String? onClipboard;

    setUp(() {
      native = [];
      onClipboard = null;
      final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        ..setMockMethodCallHandler(clipboardChannel, (call) async {
          native.add(call);
          if (call.method == 'setSensitive') {
            onClipboard = (call.arguments as Map<Object?, Object?>)['text'] as String?;
          }
          if (call.method == 'clear') onClipboard = null;
          return null;
        })
        ..setMockMethodCallHandler(SystemChannels.platform, (call) async {
          if (call.method == 'Clipboard.getData') {
            return onClipboard == null ? null : {'text': onClipboard};
          }
          if (call.method == 'Clipboard.setData') {
            fail('on Android a credential never goes through the plain clipboard');
          }
          return null;
        });
      addTearDown(() {
        messenger
          ..setMockMethodCallHandler(clipboardChannel, null)
          ..setMockMethodCallHandler(SystemChannels.platform, null);
      });
    });

    const clipboard = SensitiveClipboard(SystemClipboardPlatform(TargetPlatform.android));

    test("a copy is MainActivity's sensitive set, with the text", () async {
      await clipboard.copy(link);

      expect(native.single.method, 'setSensitive');
      expect(native.single.arguments, {'text': link});
      expect(onClipboard, link);
    });

    test('taken back while the clipboard still holds it', () async {
      await clipboard.copy(link);
      await clipboard.clearIf(link);

      expect(native.map((c) => c.method), ['setSensitive', 'clear']);
      expect(onClipboard, isNull);
    });

    test('left alone once the person has copied something else', () async {
      await clipboard.copy(link);
      onClipboard = 'flat 4, 12 Mill Lane';
      await clipboard.clearIf(link);

      expect(native.map((c) => c.method), ['setSensitive']);
      expect(onClipboard, 'flat 4, 12 Mill Lane');
    });

    test('a clipboard that cannot be read is left as it is, and nothing throws', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async => throw PlatformException(code: 'denied'),
      );
      await clipboard.clearIf(link);

      expect(native, isEmpty);
    });
  });

  test('on iOS, the plain clipboard: set, then emptied while it still holds the link', () async {
    String? onClipboard;
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
      ..setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') {
          onClipboard = (call.arguments as Map<Object?, Object?>)['text'] as String?;
        }
        if (call.method == 'Clipboard.getData') return {'text': onClipboard};
        return null;
      });
    addTearDown(() => messenger.setMockMethodCallHandler(SystemChannels.platform, null));
    const clipboard = SensitiveClipboard(SystemClipboardPlatform(TargetPlatform.iOS));

    await clipboard.copy(link);
    expect(onClipboard, link);
    await clipboard.clearIf(link);
    expect(onClipboard, '');
  });

  test('the test phone clipboard behaves as the seam promises', () async {
    final phone = MemoryClipboard();
    final clipboard = SensitiveClipboard(phone);
    await clipboard.copy(link);
    expect((phone.text, phone.sensitive), (link, true));
    phone.copiedElsewhere('other');
    await clipboard.clearIf(link);
    expect(phone.text, 'other');
  });
}
