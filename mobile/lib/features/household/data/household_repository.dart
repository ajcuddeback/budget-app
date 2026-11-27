import 'package:budget_owl/core/cache/cached_resource.dart';
import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/http/api_client.dart';
import 'package:budget_owl/core/http/reachability.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/current_user.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:budget_owl/features/household/domain/household.dart';
import 'package:budget_owl/features/household/domain/requests.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// `GET /api/households/current`. No id anywhere: an instance holds one household and the server
/// resolves it from the caller's verified membership (ADR-0017, ADR-0026).
final class HouseholdResource extends CachedResource<Household> {
  @override
  String get path => '/api/households/current';

  @override
  Household decode(JsonMap json) => Household.fromJson(json);
}

final householdProvider = NotifierProvider.autoDispose<HouseholdResource, Snapshot<Household>>(
  HouseholdResource.new,
);

/// `GET /api/households/current/members`: one page, of the web's size (200), which is every
/// member a household has.
final class MembersResource extends CachedResource<List<Member>> {
  @override
  String get path => '/api/households/current/members';

  @override
  Map<String, String> get query => const {'page': '0', 'size': '200'};

  @override
  List<Member> decode(JsonMap json) => [
    for (final member in json.objects('content')) Member.fromJson(member),
  ];
}

final membersProvider = NotifierProvider.autoDispose<MembersResource, Snapshot<List<Member>>>(
  MembersResource.new,
);

/// Every write the Household and Preferences screens make, each sent **only when the last request
/// reached the server** and never queued or retried (mobile-app.md decision 3).
///
/// The role checks the screens apply are UX. The server enforces every one of them (ADR-0017), and
/// a refused write comes back as an [AppError] whose code the screen translates — that is what a
/// `VIEWER`'s forced write must show, and the tests prove it does.
final class HouseholdWriter {
  HouseholdWriter(this._ref);

  final Ref _ref;

  ApiClient get _api {
    final api = _ref.read(apiClientProvider);
    if (api == null) throw const AppError.unexpected();
    return api;
  }

  Future<JsonMap?> _write(String method, String path, [Object? body]) async {
    if (!_ref.read(reachabilityProvider).canWrite) throw const AppError.offline();
    return await _api.write(method, path, body: body);
  }

  /// Rename, or change the base currency. Totals are shown in the base currency, so every screen
  /// re-reads, and the member is re-read for the household's name.
  Future<void> rename({required String name, required String baseCurrency}) async {
    await _write(
      'PUT',
      '/api/households/current',
      updateHouseholdBody(name: name, baseCurrency: baseCurrency),
    );
    _ref.read(dataRevisionProvider.notifier).bump();
    await _ref.read(sessionProvider.notifier).refreshMe();
  }

  /// Owner only, and never one's own role: the server refuses both, with their own codes. Only
  /// the member list moved, so only it is re-read.
  Future<void> changeRole(Member member, Role role) async {
    await _write(
      'PATCH',
      '/api/households/current/members/${Uri.encodeComponent(member.id)}',
      changeRoleBody(role),
    );
    await _ref.read(membersProvider.notifier).refresh();
  }

  /// Removes somebody else. Access only: what they recorded stays with the household, and the
  /// server revokes their sessions and tokens in the same transaction.
  Future<void> remove(Member member) async {
    await _write('DELETE', '/api/households/current/members/${Uri.encodeComponent(member.id)}');
    await _ref.read(membersProvider.notifier).refresh();
  }

  /// Leaves the household: the same `DELETE`, on one's own membership.
  ///
  /// **The server revokes every session and token the member has when they leave**, this phone's
  /// included. So the member is re-read at once, as the web does: that request's `401` is the
  /// session ending, which wipes the token, the encrypted cache and its key and routes to sign-in
  /// with "your session ended" — nothing of the household stays on the phone. (Were a server ever
  /// to keep the token, the re-read would find no membership and the phone would show the
  /// not-in-a-household screen, which also reads nothing from the cache.)
  Future<void> leave(String myMembershipId) async {
    await _write(
      'DELETE',
      '/api/households/current/members/${Uri.encodeComponent(myMembershipId)}',
    );
    await _ref.read(sessionProvider.notifier).refreshMe();
  }

  /// A new invitation. The response carries the link's token exactly once; it goes to the sheet
  /// that asked and nowhere else — not the cache, not a provider, not a log.
  Future<Invitation> invite({required String email, required Role role}) async {
    final body = await _write(
      'POST',
      '/api/households/current/invitations',
      createInvitationBody(email: email, role: role),
    );
    if (body == null) throw const AppError.unexpected();
    try {
      return Invitation.fromJson(body);
    } on UnexpectedJson {
      throw const AppError.unexpected();
    }
  }

  /// Takes a link back before anybody uses it.
  Future<void> revokeInvitation(Invitation invitation) =>
      _write('DELETE', '/api/households/current/invitations/${Uri.encodeComponent(invitation.id)}');

  /// The member's own display currency and language. Self-scoped, so a `VIEWER` may save them.
  /// They change what *this member* sees — the member is re-read, which carries them, and every
  /// screen re-reads because converted totals follow the display currency.
  Future<void> savePreferences({required String? displayCurrency, required String? locale}) async {
    await _write(
      'PATCH',
      '/api/households/current/members/me',
      ownPreferencesBody(displayCurrency: displayCurrency, locale: locale),
    );
    await _ref.read(sessionProvider.notifier).refreshMe();
    _ref.read(dataRevisionProvider.notifier).bump();
  }
}

final householdWriterProvider = Provider<HouseholdWriter>(HouseholdWriter.new);
