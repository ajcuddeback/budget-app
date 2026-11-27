import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/http/api_client.dart' show debugLogFailure;
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The native clipboard methods: MainActivity.kt on Android, AppDelegate.swift on iOS.
const MethodChannel clipboardChannel = MethodChannel('app.budgetowl/clipboard');

/// What the native side decided when asked to take a copy back.
enum ClearOutcome {
  /// The clipboard still held our copy, and was emptied.
  cleared,

  /// Something was copied since (in this app or another), or it was already empty: left alone.
  changed,

  /// Android, app not focused: Android 10+ lets no background app look at the clipboard, so the
  /// check runs when the app's window next has focus.
  deferred,
}

/// What the phone's clipboard can do for the app: the seam a test stands in for. **It has no
/// read**: whether the clipboard still holds our copy is decided on the device, from metadata,
/// never by reading what the person copied (round 5, U3).
abstract interface class ClipboardPlatform {
  /// Puts [text] on the clipboard, as protected as the platform allows, and returns the receipt:
  /// what the device needs to recognise this copy later — the clip's timestamp on Android 8+, a
  /// digest of it below, `changeCount` on iOS. Null where the platform cannot recognise it again.
  ///
  /// The receipt is kept here, in Dart, not by the native side: an activity recreated between the
  /// copy and the take-back can still compare (round 6, V2).
  Future<Map<String, Object?>?> setSensitive(String text);

  /// Empties the clipboard if it still holds the copy [receipt] describes.
  Future<ClearOutcome> clearIfUnchanged(Map<String, Object?> receipt);
}

/// One copy this app made, to take back later.
final class CopiedText {
  const CopiedText._(this._receipt);

  final Map<String, Object?>? _receipt;
}

/// The clipboard for a credential, such as an invitation link (round 4 L2, round 5 U3).
///
/// - **Android 13+**: marked `ClipDescription.EXTRA_IS_SENSITIVE`, so the system's copy preview
///   hides it and keyboards leave it out of suggestions. **Below Android 13 nothing can mark it**:
///   it is a plain clip until it is taken back (round 5, U4).
/// - **iOS**: `localOnly`, so it never reaches the person's other devices through Universal
///   Clipboard, and it expires by itself after [iosExpiry].
/// - **Taken back** with [takeBack], when the screen that put it there is done with it — but only
///   if the clipboard still holds that copy. The native side decides that **without reading the
///   clipboard**: Android compares the clip's timestamp (`ClipDescription.getTimestamp`; below
///   Android 8, a SHA-256 of the clip, where no "pasted from" notice exists), iOS compares
///   `UIPasteboard.changeCount`. So neither Android 12+'s "pasted from your clipboard" notice nor
///   iOS 16+'s "Allow Paste?" prompt is ever triggered.
///
/// The device side **needs checking on a device**: no test here runs a real clipboard.
final class SensitiveClipboard {
  const SensitiveClipboard(this._platform);

  final ClipboardPlatform _platform;

  /// How long an iOS copy lives (AppDelegate.swift sets the same).
  static const Duration iosExpiry = Duration(minutes: 10);

  Future<CopiedText> copy(String text) async => CopiedText._(await _platform.setSensitive(text));

  /// Empties the clipboard if it still holds [copy]. Never throws.
  Future<ClearOutcome?> takeBack(CopiedText copy) async {
    final receipt = copy._receipt;
    if (receipt == null) return null;
    try {
      return await _platform.clearIfUnchanged(receipt);
    } on Object catch (error) {
      debugLogFailure('taking a copied credential back from the clipboard', error);
      return null;
    }
  }
}

/// The native side on Android and iOS. Anywhere else (the app ships for neither), a plain copy
/// that is never taken back, rather than a read of the clipboard.
final class SystemClipboardPlatform implements ClipboardPlatform {
  const SystemClipboardPlatform(this.platform);

  final TargetPlatform platform;

  bool get _native => platform == TargetPlatform.android || platform == TargetPlatform.iOS;

  @override
  Future<Map<String, Object?>?> setSensitive(String text) async {
    if (!_native) {
      await Clipboard.setData(ClipboardData(text: text));
      return null;
    }
    return await clipboardChannel.invokeMapMethod<String, Object?>('setSensitive', {'text': text});
  }

  @override
  Future<ClearOutcome> clearIfUnchanged(Map<String, Object?> receipt) async {
    if (!_native) return ClearOutcome.changed;
    final answer = await clipboardChannel.invokeMethod<String>('clearIfUnchanged', receipt);
    return ClearOutcome.values.firstWhere(
      (outcome) => outcome.name == answer,
      orElse: () => ClearOutcome.changed,
    );
  }
}

final clipboardPlatformProvider = Provider<ClipboardPlatform>(
  (ref) => SystemClipboardPlatform(ref.watch(targetPlatformProvider)),
);

final sensitiveClipboardProvider = Provider<SensitiveClipboard>(
  (ref) => SensitiveClipboard(ref.watch(clipboardPlatformProvider)),
);
