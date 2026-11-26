import 'package:budget_owl/core/json/json.dart';

/// A member's role (ADR-0017). Decides which controls are **offered**; the API decides what is
/// allowed, and refuses a `VIEWER`'s write with `403 read-only-role` whatever this client shows.
enum Role {
  owner,
  member,
  viewer;

  static Role parse(String wire) => switch (wire) {
    'OWNER' => Role.owner,
    'MEMBER' => Role.member,
    'VIEWER' => Role.viewer,
    _ => throw const UnexpectedJson('role'),
  };

  String get wire => name.toUpperCase();

  /// Whether to show write controls at all. UX only — never a security control.
  bool get canWrite => this == Role.owner || this == Role.member;
}

/// The signed-in member's place in their household, as `GET /api/auth/me` sends it.
final class Membership {
  const Membership({
    required this.householdId,
    required this.householdName,
    required this.baseCurrency,
    required this.role,
    required this.membershipId,
    this.displayCurrency,
    this.locale,
  });

  factory Membership.fromJson(JsonMap json) => Membership(
    householdId: json.string('householdId'),
    householdName: json.string('name'),
    baseCurrency: json.string('baseCurrency'),
    role: Role.parse(json.string('role')),
    membershipId: json.string('membershipId'),
    displayCurrency: json.stringOrNull('displayCurrency'),
    locale: json.stringOrNull('locale'),
  );

  final String householdId;
  final String householdName;
  final String baseCurrency;
  final Role role;
  final String membershipId;
  final String? displayCurrency;
  final String? locale;
}

/// `GET /api/auth/me`.
final class CurrentUser {
  const CurrentUser({
    required this.id,
    required this.email,
    required this.displayName,
    required this.instanceAdmin,
    this.membership,
  });

  factory CurrentUser.fromJson(JsonMap json) => CurrentUser(
    id: json.string('id'),
    email: json.string('email'),
    displayName: json.string('displayName'),
    instanceAdmin: json.boolean('instanceAdmin'),
    membership: switch (json.objectOrNull('household')) {
      final JsonMap household => Membership.fromJson(household),
      null => null,
    },
  );

  final String id;
  final String email;
  final String displayName;

  /// Shows the Admin entry under More. The admin console's own routes refuse anyone else.
  final bool instanceAdmin;

  /// Null for a user who is in no household yet: the "not part of a household" screen, not an
  /// error (authentication-and-households.md).
  final Membership? membership;
}
