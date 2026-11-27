import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';

/// Several reads shown as one screen: data only when **every** read has some, and stale — labelled
/// — as soon as **any** refresh failed.
///
/// A screen that leads with a figure from one read and lists the rows of another (Bills & income's
/// summary over its bills and income) must not show one fresh and the other stale without saying
/// so. The combined copy is as old as its oldest part and carries the first failure, so the stale
/// label is never left off a figure whose refresh failed (mobile-app.md, The cache).
Snapshot<T> combineSnapshots<T>(List<Snapshot<Object?>> parts, T Function() data) {
  AppError? error;
  DateTime? oldest;
  for (final part in parts) {
    error ??= part.error;
    final at = part.fetchedAt;
    if (at != null && (oldest == null || at.isBefore(oldest))) oldest = at;
  }
  return Snapshot<T>(
    data: parts.every((p) => p.hasData) ? data() : null,
    fetchedAt: oldest,
    loading: parts.any((p) => p.loading),
    error: error,
  );
}
