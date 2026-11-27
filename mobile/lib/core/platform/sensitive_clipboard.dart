import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/http/api_client.dart' show debugLogFailure;
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// MainActivity's clipboard methods (android/.../MainActivity.kt).
const MethodChannel clipboardChannel = MethodChannel('app.budgetowl/clipboard');

/// What the phone's clipboard can do for the app: the seam a test stands in for.
abstract interface class ClipboardPlatform {
  /// Puts [text] on the clipboard, marked sensitive where the platform can say so.
  Future<void> setSensitive(String text);

  /// The plain text on the clipboard now, if any.
  Future<String?> read();

  /// Empties the clipboard.
  Future<void> clear();
}

/// The clipboard for a credential, such as an invitation link (round 4, L2).
///
/// - **Marked sensitive** on Android 13+ (`ClipDescription.EXTRA_IS_SENSITIVE`): the system's
///   copy preview hides it, and keyboards that offer clipboard suggestions leave it out. Below
///   Android 13 there is no such flag, and it is a plain copy; on iOS, a plain copy.
/// - **Taken back** when the screen that put it there is done with it: [clearIf] reads the
///   clipboard, and clears it only when it still holds that same text, so whatever the person
///   copied since is left alone.
///
/// Neither is checked by any test here on a real clipboard: **this needs checking on a device**.
final class SensitiveClipboard {
  const SensitiveClipboard(this._platform);

  final ClipboardPlatform _platform;

  Future<void> copy(String text) => _platform.setSensitive(text);

  /// Clears the clipboard if it still holds [text]. Never throws: a clipboard that cannot be read
  /// is left as it is.
  Future<void> clearIf(String text) async {
    try {
      if (await _platform.read() == text) await _platform.clear();
    } on Object catch (error) {
      debugLogFailure('taking a copied credential back from the clipboard', error);
    }
  }
}

/// Android through MainActivity's channel; elsewhere Flutter's own clipboard.
final class SystemClipboardPlatform implements ClipboardPlatform {
  const SystemClipboardPlatform(this.platform);

  final TargetPlatform platform;

  bool get _android => platform == TargetPlatform.android;

  @override
  Future<void> setSensitive(String text) async {
    if (_android) {
      await clipboardChannel.invokeMethod<void>('setSensitive', {'text': text});
    } else {
      await Clipboard.setData(ClipboardData(text: text));
    }
  }

  @override
  Future<String?> read() async => (await Clipboard.getData(Clipboard.kTextPlain))?.text;

  @override
  Future<void> clear() async {
    if (_android) {
      await clipboardChannel.invokeMethod<void>('clear');
    } else {
      await Clipboard.setData(const ClipboardData(text: ''));
    }
  }
}

final clipboardPlatformProvider = Provider<ClipboardPlatform>(
  (ref) => SystemClipboardPlatform(ref.watch(targetPlatformProvider)),
);

final sensitiveClipboardProvider = Provider<SensitiveClipboard>(
  (ref) => SensitiveClipboard(ref.watch(clipboardPlatformProvider)),
);
