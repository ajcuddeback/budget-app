import 'dart:io';
import 'dart:typed_data';

import 'package:budget_owl/core/storage/temp_files.dart';
import 'package:file_selector/file_selector.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path_provider/path_provider.dart';

/// A statement the person picked: its bytes, in memory, and its name for choosing a format.
/// **The name is never sent** (file-import.md decision 4: names carry account numbers).
final class PickedStatement {
  const PickedStatement({required this.bytes, required this.name});

  final Uint8List bytes;
  final String name;
}

/// The platform's document picker, behind an interface so a test can stand in for it.
abstract interface class StatementPicker {
  /// The picked file's bytes, or null when the person cancelled.
  Future<PickedStatement?> pick();
}

/// file_selector's system document picker: Android `ACTION_OPEN_DOCUMENT`, iOS
/// `UIDocumentPickerViewController`. **No storage permission** is needed or declared.
///
/// **The bytes are read into memory, and the copy the platform made is deleted at once.** Both
/// platforms hand back a copy in the app's temporary directory as well as the document: Android's
/// plugin writes one to `cache/<uuid>/` beside the bytes, and iOS opens the document "as a copy"
/// into `tmp/`. The app never writes one of its own, and deletes theirs — only a file under the
/// temporary directory, never a path anywhere else — before the screen sees the bytes. Every
/// document is offered: the format comes from the name, and the server checks the bytes either
/// way. A copy whose path never came back — the pick failed, or the app died first — is swept at
/// the next start-up and every wipe (core/storage/temp_files.dart, L1).
final class SystemStatementPicker implements StatementPicker {
  SystemStatementPicker({Future<XFile?> Function()? open, Future<Directory> Function()? temporary})
    : _open = open ?? openFile,
      _temporary = temporary ?? getTemporaryDirectory;

  final Future<XFile?> Function() _open;
  final Future<Directory> Function() _temporary;

  @override
  Future<PickedStatement?> pick() async {
    final picked = await _open();
    if (picked == null) return null;
    try {
      final bytes = await picked.readAsBytes();
      return PickedStatement(bytes: bytes, name: picked.name);
    } finally {
      await forgetPlatformCopy(picked.path, await _temporary());
    }
  }
}

/// Deletes the platform's copy at [path] if — and only if — it lies inside [temporary], and then
/// the folder the plugin made for it when that folder is left empty. Never anything else.
///
/// Both the copy's folder and the root are compared with their links resolved: on iOS `/var` is a
/// link to `/private/var`, and the plugin and the system name the same folder each way, so an
/// unresolved comparison could skip the delete (round 6, V3). The copy itself is never followed:
/// a link there is deleted as a link.
Future<void> forgetPlatformCopy(String path, Directory temporary) async {
  if (path.isEmpty) return;
  try {
    final root = temporary.resolveSymbolicLinksSync();
    final named = File(path).absolute;
    final folder = Directory(named.parent.resolveSymbolicLinksSync());
    final file = File('${folder.path}/${named.uri.pathSegments.last}');
    if (!isInsideDirectory(file.path, root)) return;
    if (FileSystemEntity.typeSync(file.path, followLinks: false) != FileSystemEntityType.notFound) {
      await file.delete();
    }
    if (folder.path != root &&
        isInsideDirectory(folder.path, root) &&
        folder.listSync(followLinks: false).isEmpty) {
      await folder.delete();
    }
  } on FileSystemException {
    // Already gone, or a folder that does not exist: nothing of the statement is left behind by
    // trying.
  }
}

final statementPickerProvider = Provider<StatementPicker>(
  // On iOS the copy is in tmp/<bundle id>-Inbox/, not the temporary directory (Library/Caches):
  // without this it was never deleted there (round 5, U2).
  (ref) => SystemStatementPicker(temporary: () => ref.read(pickerCopyRootProvider.future)),
);
