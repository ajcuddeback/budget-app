import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// The app never asks for storage (mobile-app.md, Screens): import uses the system document
/// picker and CSV export a FileProvider share, and neither needs a permission. The manifest strips
/// every storage permission a plugin's own manifest might merge in, so a future plugin cannot
/// widen what the app asks for without this test failing.
void main() {
  const storage = [
    'android.permission.READ_EXTERNAL_STORAGE',
    'android.permission.WRITE_EXTERNAL_STORAGE',
    'android.permission.MANAGE_EXTERNAL_STORAGE',
    'android.permission.READ_MEDIA_IMAGES',
    'android.permission.READ_MEDIA_VIDEO',
    'android.permission.READ_MEDIA_AUDIO',
    'android.permission.READ_MEDIA_VISUAL_USER_SELECTED',
  ];

  final manifest = File('android/app/src/main/AndroidManifest.xml').readAsStringSync();

  test('every storage permission is removed from the merged manifest', () {
    expect(manifest, contains('xmlns:tools="http://schemas.android.com/tools"'));
    for (final permission in storage) {
      expect(
        manifest,
        contains('<uses-permission tools:node="remove" android:name="$permission"/>'),
        reason: permission,
      );
    }
  });

  test('none is requested', () {
    for (final permission in storage) {
      final requested = RegExp('<uses-permission(?![^>]*tools:node="remove")[^>]*"$permission"');
      expect(requested.hasMatch(manifest), isFalse, reason: permission);
    }
  });

  test('iOS asks for no photo or file access: the document picker needs none', () {
    final plist = File('ios/Runner/Info.plist').readAsStringSync();
    for (final key in [
      'NSPhotoLibraryUsageDescription',
      'NSPhotoLibraryAddUsageDescription',
      'UIFileSharingEnabled',
      'LSSupportsOpeningDocumentsInPlace',
    ]) {
      expect(plist, isNot(contains(key)), reason: key);
    }
  });
}
