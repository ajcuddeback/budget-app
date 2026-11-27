import 'dart:io';
import 'dart:typed_data';

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
/// way.
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
Future<void> forgetPlatformCopy(String path, Directory temporary) async {
  if (path.isEmpty) return;
  final root = temporary.absolute.path;
  final file = File(path).absolute;
  if (!_isInside(file.path, root)) return;
  try {
    if (file.existsSync()) await file.delete();
    final folder = file.parent;
    if (folder.path != root &&
        _isInside(folder.path, root) &&
        folder.existsSync() &&
        folder.listSync().isEmpty) {
      await folder.delete();
    }
  } on FileSystemException {
    // Already gone: nothing of the statement is left behind by trying.
  }
}

bool _isInside(String path, String root) {
  final normal = File(path).absolute.uri.normalizePath().path;
  final base = Directory(root).absolute.uri.normalizePath().path;
  final prefix = base.endsWith('/') ? base : '$base/';
  return normal.startsWith(prefix) && !normal.contains('/../');
}

final statementPickerProvider = Provider<StatementPicker>((ref) => SystemStatementPicker());
