import 'dart:io';

import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/insights/domain/reports.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:material_ui/material_ui.dart';
import 'package:path_provider/path_provider.dart';
import 'package:share_plus/share_plus.dart';

/// What the share sheet did with the file.
enum ShareOutcome { shared, dismissed, unavailable }

/// The platform share sheet, behind an interface so a test can stand in for it.
abstract interface class FileSharer {
  Future<ShareOutcome> share(File file, {required String mimeType, Rect? origin});

  /// Deletes any copy the platform made of the file called [name] to share it.
  Future<void> forget(String name, Directory temporary);

  /// Deletes every export copy the platform still holds from an earlier share.
  Future<void> sweep(Directory temporary);
}

/// share_plus: `ACTION_SEND` through its own FileProvider on Android, `UIActivityViewController`
/// on iOS.
///
/// **Android copies the file** into `cache/share_plus/` before handing the receiving app a content
/// URI, and only clears that folder at the next share. The receiver may read the URI after the
/// sheet returns, so a shared copy is left for it and swept at the next export or the next time
/// Insights opens; a dismissed or failed share's copy is deleted at once. iOS shares the file
/// itself and returns once the activity is over, so ours is the only copy.
final class SharePlusSharer implements FileSharer {
  const SharePlusSharer();

  static const String _folder = 'share_plus';

  @override
  Future<ShareOutcome> share(File file, {required String mimeType, Rect? origin}) async {
    final result = await SharePlus.instance.share(
      ShareParams(
        files: [XFile(file.path, mimeType: mimeType)],
        sharePositionOrigin: origin,
      ),
    );
    return switch (result.status) {
      ShareResultStatus.success => ShareOutcome.shared,
      ShareResultStatus.dismissed => ShareOutcome.dismissed,
      ShareResultStatus.unavailable => ShareOutcome.unavailable,
    };
  }

  @override
  Future<void> forget(String name, Directory temporary) async {
    if (defaultTargetPlatform != TargetPlatform.android) return;
    await _delete(File('${temporary.path}/$_folder/$name'));
  }

  @override
  Future<void> sweep(Directory temporary) async {
    if (defaultTargetPlatform != TargetPlatform.android) return;
    final folder = Directory('${temporary.path}/$_folder');
    if (!folder.existsSync()) return;
    for (final entry in folder.listSync()) {
      if (entry is File && isExportName(entry.uri.pathSegments.last)) await _delete(entry);
    }
  }
}

/// Only the files this app names: `budget-owl-<report>-<from>-<to>.csv`.
bool isExportName(String name) =>
    RegExp(r'^budget-owl-(income-and-spending|spending-by-category)-\d{4}-\d{2}-\d{4}-\d{2}\.csv$')
        .hasMatch(name);

Future<void> _delete(FileSystemEntity entity) async {
  try {
    if (entity.existsSync()) await entity.delete();
  } on FileSystemException {
    // Already gone, or not ours to delete: either way nothing of ours is left behind by trying.
  }
}

final fileSharerProvider = Provider<FileSharer>((ref) => const SharePlusSharer());

/// The app's temporary directory: where an export lives for as long as the share sheet is open.
final exportDirectoryProvider = FutureProvider<Directory>((ref) => getTemporaryDirectory());

/// A report as the CSV the web downloads, handed to the share sheet (reporting.md, CSV export;
/// mobile-app.md, Screens).
///
/// - **The same resource, asked for with `Accept: text/csv`**, so the file and the screen cannot
///   disagree. The bytes are the server's, formula-escaped by its `CsvWriter`; nothing here reads
///   or rewrites them, and they are never cached, logged or kept.
/// - **The file is written to the temporary directory, shared, and deleted** — after a share, a
///   dismissed sheet or a failure alike (`finally`). A file nobody was shown is never written: the
///   read comes first.
/// - **Its name is built from the report and the two months only**, never a household's or a
///   category's name.
final class CsvExporter {
  CsvExporter(this._ref);

  final Ref _ref;

  static const String folderName = 'budget-owl-export';

  static const String _bom = '﻿';

  Future<ShareOutcome> export(
    ReportName report, {
    required String from,
    required String to,
    Rect? origin,
  }) async {
    final api = _ref.read(apiClientProvider);
    if (api == null) throw const AppError.unexpected();
    final name = csvFileName(report, from, to);
    final text = await api.getText(
      '/api/reports/${report.path}',
      query: {'from': from, 'to': to},
      headers: const {'Accept': 'text/csv'},
    );
    final temporary = await _ref.read(exportDirectoryProvider.future);
    final sharer = _ref.read(fileSharerProvider);
    await sharer.sweep(temporary);
    final folder = Directory('${temporary.path}/$folderName');
    final file = File('${folder.path}/$name');
    ShareOutcome? outcome;
    try {
      await folder.create(recursive: true);
      // The server's byte-order mark, which the transport's UTF-8 decoding drops: without it a
      // spreadsheet mis-reads every non-Latin category name (reporting.md, CSV export).
      await file.writeAsString(text.startsWith(_bom) ? text : '$_bom$text', flush: true);
      return outcome = await sharer.share(file, mimeType: 'text/csv', origin: origin);
    } finally {
      await _delete(file);
      try {
        if (folder.existsSync() && folder.listSync().isEmpty) await folder.delete();
      } on FileSystemException {
        // Another export's file is in it: that export deletes its own.
      }
      if (outcome != ShareOutcome.shared) await sharer.forget(name, temporary);
    }
  }
}

final csvExporterProvider = Provider<CsvExporter>(CsvExporter.new);
