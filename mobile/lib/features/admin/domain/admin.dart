import 'package:budget_owl/core/json/json.dart';

/// The instance an operator runs: who may sign in, and how (admin-console.md).
///
/// **No financial data lives in any of these shapes** — not a balance, not a count of
/// transactions (ADR-0026). `AdminResponses.java` is the contract they mirror.

/// `GET /api/admin/instance`: "is my server alright".
final class InstanceInfo {
  const InstanceInfo({
    required this.version,
    required this.administratorName,
    required this.administratorEmail,
    required this.accountCount,
    this.setupCompletedAt,
  });

  factory InstanceInfo.fromJson(JsonMap json) => InstanceInfo(
    version: json.string('version'),
    setupCompletedAt: _instant(json.stringOrNull('setupCompletedAt')),
    administratorName: json.string('administratorName'),
    administratorEmail: json.string('administratorEmail'),
    accountCount: json.integer('accountCount'),
  );

  final String version;

  /// Null on an instance whose row predates the column: "Not recorded", never a blank.
  final DateTime? setupCompletedAt;
  final String administratorName;
  final String administratorEmail;

  /// A count of people, never of their money.
  final int accountCount;
}

/// `GET /api/admin/settings`.
///
/// **There is no client-secret field, and there must never be one.** The API returns the secret to
/// nobody, including the administrator who typed it; [oidcClientSecretSet] is the only thing said
/// about it.
final class InstanceSettings {
  const InstanceSettings({
    required this.registrationOpen,
    required this.passwordLoginEnabled,
    required this.passwordLoginLocked,
    required this.oidcEnabled,
    required this.oidcClientSecretSet,
    required this.oidcProvisioningEnabled,
    required this.version,
    this.oidcIssuerUri,
    this.oidcClientId,
    this.oidcOwnerLoginAt,
  });

  factory InstanceSettings.fromJson(JsonMap json) => InstanceSettings(
    registrationOpen: json.boolean('registrationOpen'),
    passwordLoginEnabled: json.boolean('passwordLoginEnabled'),
    passwordLoginLocked: json.boolean('passwordLoginLocked'),
    oidcEnabled: json.boolean('oidcEnabled'),
    oidcIssuerUri: json.stringOrNull('oidcIssuerUri'),
    oidcClientId: json.stringOrNull('oidcClientId'),
    oidcClientSecretSet: json.boolean('oidcClientSecretSet'),
    oidcProvisioningEnabled: json.boolean('oidcProvisioningEnabled'),
    oidcOwnerLoginAt: _instant(json.stringOrNull('oidcOwnerLoginAt')),
    version: json.integer('version'),
  );

  final bool registrationOpen;
  final bool passwordLoginEnabled;

  /// Password sign-in cannot be turned off yet: no `OWNER` has proved the other route works. The
  /// console states the rule instead of offering a switch that answers `409`.
  final bool passwordLoginLocked;
  final bool oidcEnabled;
  final String? oidcIssuerUri;
  final String? oidcClientId;

  /// Whether a secret is configured. Never what it is.
  final bool oidcClientSecretSet;
  final bool oidcProvisioningEnabled;

  /// When an `OWNER` last signed in through the provider. Null is never.
  final DateTime? oidcOwnerLoginAt;

  /// Sent back on every write: optimistic locking only works if the caller says what it read.
  final int version;

  /// The password switch is offered when the lockout does not apply — and also when password
  /// sign-in is somehow already off, so turning it back on is never unreachable from here.
  bool get offerPasswordSwitch => !passwordLoginLocked || !passwordLoginEnabled;
}

/// One account that can sign in. Read-only in this slice.
final class AdminAccount {
  const AdminAccount({
    required this.id,
    required this.email,
    required this.displayName,
    required this.status,
    required this.instanceAdmin,
    required this.createdAt,
  });

  factory AdminAccount.fromJson(JsonMap json) => AdminAccount(
    id: json.string('id'),
    email: json.string('email'),
    displayName: json.string('displayName'),
    status: json.string('status'),
    instanceAdmin: json.boolean('instanceAdmin'),
    createdAt: DateTime.parse(json.string('createdAt')),
  );

  final String id;
  final String email;
  final String displayName;

  /// `ACTIVE` or `DISABLED` — kept as the wire word, so a status a newer server adds is shown as
  /// "Unknown status" rather than failing the whole list.
  final String status;

  /// Exactly one account is the administrator (ADR-0029). Not a household role.
  final bool instanceAdmin;
  final DateTime createdAt;
}

DateTime? _instant(String? wire) => wire == null ? null : DateTime.parse(wire);
