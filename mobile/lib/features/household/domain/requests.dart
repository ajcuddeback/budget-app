import 'package:budget_owl/core/session/current_user.dart';

/// The request bodies the household and preferences screens send, **exactly as the web builds
/// them** (frontend/src/app/features/household and preferences). Each has a unit test of the
/// body in test/features/household/requests_test.dart.
///
/// None of these routes takes a `version`: the household, a membership and an invitation are not
/// optimistically locked on the server, and the web sends none. The household is never named in a
/// body or a path — the server resolves it from the caller's membership (ADR-0017).

/// `PUT /api/households/current`: both fields, always, as the web's form submits them.
Map<String, Object?> updateHouseholdBody({required String name, required String baseCurrency}) => {
  'name': name.trim(),
  'baseCurrency': baseCurrency.trim().toUpperCase(),
};

/// `PATCH /api/households/current/members/{id}`: the new role and nothing else.
Map<String, Object?> changeRoleBody(Role role) => {'role': role.wire};

/// `POST /api/households/current/invitations`. The address is a label, not an authorization:
/// possession of the link is what grants access.
Map<String, Object?> createInvitationBody({required String email, required Role role}) => {
  'email': email.trim(),
  'role': role.wire,
};

/// `PATCH /api/households/current/members/me`: a full replacement of both preferences.
///
/// An empty choice is "follow the default" and is sent as `null` — a meaning, not a gap
/// (ADR-0022, ADR-0023). Both fields always travel, as the web sends them.
Map<String, Object?> ownPreferencesBody({
  required String? displayCurrency,
  required String? locale,
}) {
  final currency = displayCurrency?.trim() ?? '';
  final tag = locale?.trim() ?? '';
  return {
    'displayCurrency': currency.isEmpty ? null : currency.toUpperCase(),
    'locale': tag.isEmpty ? null : tag,
  };
}
