import 'dart:io';

import 'package:budget_owl/core/config/environment.dart';
import 'package:budget_owl/core/http/api_client.dart' show debugLogFailure;
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';

/// What the app's features — or the plugins they use — leave in the temporary directory, and the
/// one sweep that removes it (round 4, M1 and L1). Core owns it so the wipe can run it without
/// depending on any feature:
///
/// - **`budget-owl-export/`**: an exported CSV while the share sheet is open. The export deletes it
///   in a `finally`, which never runs when the app is killed with the sheet open. Everything under
///   it is swept.
/// - **`share_plus/`** (Android): share_plus's copy of a shared file, left for the app receiving
///   it. Only files named as our exports are swept; anything else there is not ours.
/// - **`<uuid>/<name>`** (Android): file_selector_android's copy of a picked statement, written
///   before the picker returns. If the pick fails, or the app dies before the import's `finally`,
///   nothing knows its path. Swept: a real directory directly under the temporary directory, named
///   as a UUID, holding exactly one regular file and nothing else.
/// - **`tmp/<bundle id>-Inbox/`** (iOS): file_selector_ios opens a document "as a copy", into the
///   Inbox folder in the real `tmp/` — not Dart's temporary directory, which on iOS is
///   `Library/Caches`. AppDelegate says where both are ([PickerInbox]); everything in the Inbox
///   is swept (round 5, U2).
///
/// Swept on every wipe (sign-out, `401`, change of server) and once at start-up. Nothing outside
/// the temporary directory is ever deleted: links are never followed, and every path is checked to
/// lie inside it.
abstract final class TempFiles {
  /// Where an export is written while the share sheet is open.
  static const String exportFolder = 'budget-owl-export';

  /// Where share_plus copies a shared file on Android.
  static const String shareFolder = 'share_plus';
}

/// The app's temporary directory.
final temporaryDirectoryProvider = FutureProvider<Directory>((ref) => getTemporaryDirectory());

/// AppDelegate's file methods (ios/Runner/AppDelegate.swift).
const MethodChannel filesChannel = MethodChannel('app.budgetowl/files');

/// Where file_selector_ios leaves its copies: [inbox], inside the real [temporary] (`tmp/`).
final class PickerInbox {
  const PickerInbox({required this.temporary, required this.inbox});

  final Directory temporary;
  final Directory inbox;
}

/// The iOS picker's Inbox, as AppDelegate resolves it from the bundle id; null on any other
/// platform, or when it cannot be had (then nothing is swept there).
final pickerInboxProvider = FutureProvider<PickerInbox?>((ref) async {
  if (ref.watch(targetPlatformProvider) != TargetPlatform.iOS) return null;
  try {
    final paths = await filesChannel.invokeMapMethod<String, String>('pickerInbox');
    final temporary = paths?['temporary'];
    final inbox = paths?['inbox'];
    if (temporary == null || inbox == null) return null;
    return PickerInbox(temporary: Directory(temporary), inbox: Directory(inbox));
  } on Object catch (error) {
    debugLogFailure('finding the picker inbox', error);
    return null;
  }
});

/// Only the files this app names: `budget-owl-<report>-<from>-<to>.csv`.
bool isExportName(String name) =>
    RegExp(r'^budget-owl-(income-and-spending|spending-by-category)-\d{4}-\d{2}-\d{4}-\d{2}\.csv$')
        .hasMatch(name);

/// Whether [path] lies strictly inside [root], after normalising both (no `..` escapes).
bool isInsideDirectory(String path, String root) {
  final normal = File(path).absolute.uri.normalizePath().path;
  final base = Directory(root).absolute.uri.normalizePath().path;
  final prefix = base.endsWith('/') ? base : '$base/';
  return normal.startsWith(prefix) && !normal.contains('/../');
}

final RegExp _uuid = RegExp(
  r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$',
  caseSensitive: false,
);

/// Every export the app or share_plus still holds in [temporary]: all of `budget-owl-export/`, and
/// our export names in `share_plus/`.
Future<void> sweepExports(Directory temporary) async {
  final root = _real(temporary);
  if (root == null) return;
  final exports = Directory('$root/${TempFiles.exportFolder}');
  if (_isRealDirectoryInside(exports.path, root)) await _delete(exports, recursive: true);
  final shared = Directory('$root/${TempFiles.shareFolder}');
  if (!_isRealDirectoryInside(shared.path, root)) return;
  for (final entry in _children(shared)) {
    final name = entry.uri.pathSegments.last;
    if (entry is File && isExportName(name) && _isFileInside(entry.path, root)) {
      await _delete(entry);
    }
  }
}

/// file_selector_android's copies of picked documents left in [temporary] (see [TempFiles]).
Future<void> sweepPickerCopies(Directory temporary) async {
  final root = _real(temporary);
  if (root == null) return;
  for (final entry in _children(Directory(root))) {
    if (entry is! Directory) continue;
    final name = entry.uri.pathSegments.where((s) => s.isNotEmpty).last;
    if (!_uuid.hasMatch(name) || !_isRealDirectoryInside(entry.path, root)) continue;
    final inside = _children(entry);
    if (inside.length != 1) continue;
    final copy = inside.single;
    if (copy is! File || !_isFileInside(copy.path, root)) continue;
    await _delete(copy);
    await _delete(entry);
  }
}

/// Where the document picker leaves its copy of a picked file, for the picker to delete it at
/// once: on iOS the real `tmp/` (its Inbox is there; Dart's temporary directory is
/// `Library/Caches`, which holds none), elsewhere the temporary directory (round 5, U2).
final pickerCopyRootProvider = FutureProvider<Directory>((ref) async {
  final inbox = await ref.watch(pickerInboxProvider.future);
  return inbox?.temporary ?? await ref.watch(temporaryDirectoryProvider.future);
});

/// file_selector_ios's copies: everything in [PickerInbox.inbox], which must be a real directory
/// directly inside [PickerInbox.temporary] whose name ends in `-Inbox`. Nothing is followed: a link
/// in it is deleted as a link, and an Inbox that is itself a link is left alone.
Future<void> sweepPickerInbox(PickerInbox picker) async {
  // The Inbox must be named by AppDelegate as directly inside tmp/ (both paths as it gave them:
  // on iOS /var is a link to /private/var, so they are compared before anything is resolved).
  String normal(Directory d) => d.absolute.uri.normalizePath().path.replaceAll(RegExp(r'/+$'), '');
  final name = picker.inbox.uri.pathSegments.where((s) => s.isNotEmpty).last;
  if (!name.endsWith('-Inbox') || normal(picker.inbox.parent) != normal(picker.temporary)) return;
  final root = _real(picker.temporary);
  if (root == null) return;
  final inbox = Directory('$root/$name');
  if (!_isRealDirectoryInside(inbox.path, root)) return;
  for (final entry in _children(inbox)) {
    if (!isInsideDirectory(entry.path, root)) continue;
    // A directory is deleted with what it holds; Dart deletes a link in it as a link.
    await _delete(entry, recursive: entry is Directory);
  }
}

/// The start-up and wipe sweep: exports everywhere, the picker's copies on Android (UUID folders)
/// and on iOS (its Inbox, when [inbox] says where it is).
Future<void> sweepTemporaryFiles(
  Directory temporary, {
  required TargetPlatform platform,
  PickerInbox? inbox,
}) async {
  await sweepExports(temporary);
  if (platform == TargetPlatform.android) await sweepPickerCopies(temporary);
  if (platform == TargetPlatform.iOS && inbox != null) await sweepPickerInbox(inbox);
}

/// [temporary] with its links resolved, or null when it does not exist.
String? _real(Directory temporary) {
  try {
    return temporary.existsSync() ? temporary.resolveSymbolicLinksSync() : null;
  } on FileSystemException {
    return null;
  }
}

/// The entries directly in [folder], links reported as links (never followed).
List<FileSystemEntity> _children(Directory folder) {
  try {
    return folder.listSync(followLinks: false);
  } on FileSystemException {
    return const [];
  }
}

bool _isRealDirectoryInside(String path, String root) =>
    FileSystemEntity.typeSync(path, followLinks: false) == FileSystemEntityType.directory &&
    isInsideDirectory(path, root);

bool _isFileInside(String path, String root) =>
    FileSystemEntity.typeSync(path, followLinks: false) == FileSystemEntityType.file &&
    isInsideDirectory(path, root);

Future<void> _delete(FileSystemEntity entity, {bool recursive = false}) async {
  try {
    await entity.delete(recursive: recursive);
  } on FileSystemException {
    // Already gone, or not ours to delete: either way nothing of ours is left behind by trying.
  }
}
