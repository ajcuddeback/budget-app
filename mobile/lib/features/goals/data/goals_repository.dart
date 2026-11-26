import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/goals/domain/goal.dart';
import 'package:budget_owl/features/goals/domain/requests.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// `GET /api/goals?includeArchived=true&page=0&size=200` — every goal with its figures, archived
/// ones too so the list can show them apart. No household id: the server resolves it (ADR-0017).
final class GoalsResource extends CachedResource<GoalPage> {
  @override
  String get path => '/api/goals';

  @override
  Map<String, String> get query => const {'includeArchived': 'true', 'page': '0', 'size': '200'};

  @override
  GoalPage decode(JsonMap json) => GoalPage.fromJson(json);
}

final goalsProvider = NotifierProvider.autoDispose<GoalsResource, Snapshot<GoalPage>>(
  GoalsResource.new,
);

String _goal(String id) => '/api/goals/${Uri.encodeComponent(id)}';

/// `GET /api/goals/{id}`.
final class GoalResource extends CachedResource<Goal> {
  GoalResource(this.id);

  final String id;

  @override
  String get path => _goal(id);

  @override
  Goal decode(JsonMap json) => Goal.fromJson(json);
}

final goalProvider = NotifierProvider.autoDispose.family<GoalResource, Snapshot<Goal>, String>(
  GoalResource.new,
);

/// `GET /api/goals/{id}/contribution-plan?page=0&size=50` — the steps, oldest first.
final class GoalPlanResource extends CachedResource<PlanStepPage> {
  GoalPlanResource(this.id);

  final String id;

  @override
  String get path => '${_goal(id)}/contribution-plan';

  @override
  Map<String, String> get query => const {'page': '0', 'size': '50'};

  @override
  PlanStepPage decode(JsonMap json) => PlanStepPage.fromJson(json);
}

final goalPlanProvider = NotifierProvider.autoDispose
    .family<GoalPlanResource, Snapshot<PlanStepPage>, String>(GoalPlanResource.new);

/// `GET /api/goals/{id}/contributions?page=0&size=50` — newest first, in the server's order. Only
/// the first page is cached.
final class ContributionsResource extends CachedResource<ContributionPage> {
  ContributionsResource(this.id);

  final String id;

  @override
  String get path => '${_goal(id)}/contributions';

  @override
  Map<String, String> get query => const {'page': '0', 'size': '50'};

  @override
  ContributionPage decode(JsonMap json) => ContributionPage.fromJson(json);
}

final contributionsProvider = NotifierProvider.autoDispose
    .family<ContributionsResource, Snapshot<ContributionPage>, String>(ContributionsResource.new);

/// The history's size per page — the first page is [ContributionsResource]'s.
const contributionPageSize = 50;

/// *Show more*: page [page] of [goalId]'s history, read live and never cached — the web's
/// `showMore()`, which asks for page `shown / 50` and appends it.
Future<ContributionPage> readContributionsPage(Ref ref, String goalId, int page) async {
  final api = ref.read(apiClientProvider);
  if (api == null) throw const AppError.unexpected();
  try {
    return ContributionPage.fromJson(
      await api.getJson(
        '${_goal(goalId)}/contributions',
        query: {'page': '$page', 'size': '$contributionPageSize'},
      ),
    );
  } on UnexpectedJson {
    throw const AppError.unexpected();
  }
}

final contributionsPageReader = Provider<Future<ContributionPage> Function(String, int)>(
  (ref) => (goalId, page) => readContributionsPage(ref, goalId, page),
);

/// Every write the goal screens make, each sent only when writes are on. **Nothing is queued**;
/// after a success every screen re-reads. The previews are `POST`s that store nothing, so nothing
/// re-reads after them.
final class GoalsWriter {
  GoalsWriter(this._ref);

  final Ref _ref;

  Future<JsonMap?> _send(String method, String path, Object? body, {required bool bump}) async {
    if (!_ref.read(reachabilityProvider).canWrite) throw const AppError.offline();
    final api = _ref.read(apiClientProvider);
    if (api == null) throw const AppError.unexpected();
    final result = await api.write(method, path, body: body);
    if (bump) _ref.read(dataRevisionProvider.notifier).bump();
    return result;
  }

  Future<void> create(GoalForm form) =>
      _send('POST', '/api/goals', createGoalBody(form), bump: true);

  /// Sends the patch, or nothing when nothing changed. Returns whether anything was sent.
  Future<bool> update(Goal goal, GoalForm form) async {
    final patch = goalPatch(goal, form);
    if (patchIsEmpty(patch)) return false;
    await _send('PATCH', _goal(goal.id), patch, bump: true);
    return true;
  }

  /// Takes its plan and contributions and no transaction. The re-read waits for [leave], which
  /// takes the phone off the goal's own page first: re-reading a goal that no longer exists would
  /// only ask the server for three 404s.
  Future<void> delete(Goal goal, {Future<void> Function()? leave}) async {
    await _send('DELETE', _goal(goal.id), null, bump: false);
    if (leave != null) await leave();
    _ref.read(dataRevisionProvider.notifier).bump();
  }

  Future<void> archive(Goal goal) =>
      _send('POST', '${_goal(goal.id)}/archive', const {}, bump: true);

  Future<void> unarchive(Goal goal) =>
      _send('POST', '${_goal(goal.id)}/unarchive', const {}, bump: true);

  /// From [fromPeriod] on, the plan is this amount. Never a past month: the server refuses it.
  Future<void> setPlan(Goal goal, String fromPeriod, String monthlyAmount) => _send(
    'PUT',
    '${_goal(goal.id)}/contribution-plan/${Uri.encodeComponent(fromPeriod)}',
    planBody(monthlyAmount: monthlyAmount, version: goal.version),
    bump: true,
  );

  /// Writes no transaction and moves no account balance (ADR-0035).
  Future<void> contribute(Goal goal, Map<String, Object?> body) =>
      _send('POST', '${_goal(goal.id)}/contributions', body, bump: true);

  Future<void> removeContribution(Goal goal, Contribution contribution) => _send(
    'DELETE',
    '${_goal(goal.id)}/contributions/${Uri.encodeComponent(contribution.id)}',
    null,
    bump: true,
  );

  /// What [goal] would report with [body] substituted, storing nothing.
  Future<ProjectionFacts> preview(Goal goal, Map<String, Object?> body, {String? targetMonth}) =>
      _preview('${_goal(goal.id)}/projection-preview', body, targetMonth);

  /// The add sheet's preview, before a goal exists: pure arithmetic over the body, on the server.
  Future<ProjectionFacts> previewDraft(Map<String, Object?> body, {String? targetMonth}) =>
      _preview('/api/goals/projection-preview', body, targetMonth);

  Future<ProjectionFacts> _preview(String path, Object body, String? targetMonth) async {
    final answer = await _send('POST', path, body, bump: false);
    if (answer == null) throw const AppError.unexpected();
    try {
      return ProjectionFacts.fromPreview(answer, targetMonth: targetMonth);
    } on UnexpectedJson {
      // A body merely shaped like JSON is no preview: the sheet says so and still saves.
      throw const AppError.unexpected();
    }
  }
}

final goalsWriterProvider = Provider<GoalsWriter>(GoalsWriter.new);
