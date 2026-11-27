import 'package:budget_owl/features/admin/domain/admin.dart';

/// What the signing-in form holds. Strings are as typed, trimmed on the way out.
final class SigningInForm {
  const SigningInForm({
    required this.registrationOpen,
    required this.passwordLoginEnabled,
    required this.oidcEnabled,
    required this.oidcIssuerUri,
    required this.oidcClientId,
    required this.oidcClientSecret,
    required this.oidcProvisioningEnabled,
  });

  /// The form as the server's copy fills it. **The secret is empty, always**: there is no stored
  /// value to put in it and no response that carries one.
  factory SigningInForm.of(InstanceSettings settings) => SigningInForm(
    registrationOpen: settings.registrationOpen,
    passwordLoginEnabled: settings.passwordLoginEnabled,
    oidcEnabled: settings.oidcEnabled,
    oidcIssuerUri: settings.oidcIssuerUri ?? '',
    oidcClientId: settings.oidcClientId ?? '',
    oidcClientSecret: '',
    oidcProvisioningEnabled: settings.oidcProvisioningEnabled,
  );

  final bool registrationOpen;
  final bool passwordLoginEnabled;
  final bool oidcEnabled;
  final String oidcIssuerUri;
  final String oidcClientId;

  /// Empty means "nothing was typed" — which is not the same as "clear the secret".
  final String oidcClientSecret;
  final bool oidcProvisioningEnabled;

  /// Redacted: this object can hold a client secret, and a log line is how one leaves.
  @override
  String toString() => 'SigningInForm(redacted)';
}

/// The `PATCH /api/admin/settings` body: the difference between what the server last said and
/// what the form holds — nothing more. The web's `buildSettingsPatch`, rule for rule:
///
/// - **`version` always travels.** It is what the caller read; a stale one is `409 stale-version`
///   rather than two administrators silently overwriting each other.
/// - **The client secret is sent only when somebody typed one.** The server reads a present but
///   blank `oidcClientSecret` as "clear it", so a form that sent `''` on every save would wipe the
///   secret on any unrelated change. Whitespace alone counts as nothing typed; a secret that was
///   typed is sent exactly as typed, never trimmed.
/// - **Turning the provider off says only that.** The server clears the configuration and switches
///   password sign-in back on, so an issuer sent alongside would describe a configuration the
///   request is abandoning.
Map<String, Object?> buildSettingsPatch(InstanceSettings current, SigningInForm form) {
  final patch = <String, Object?>{};
  if (form.registrationOpen != current.registrationOpen) {
    patch['registrationOpen'] = form.registrationOpen;
  }
  if (form.passwordLoginEnabled != current.passwordLoginEnabled) {
    patch['passwordLoginEnabled'] = form.passwordLoginEnabled;
  }
  if (form.oidcEnabled != current.oidcEnabled) {
    patch['oidcEnabled'] = form.oidcEnabled;
  }
  if (form.oidcEnabled) {
    final issuer = form.oidcIssuerUri.trim();
    if (issuer != (current.oidcIssuerUri ?? '')) patch['oidcIssuerUri'] = issuer;
    final clientId = form.oidcClientId.trim();
    if (clientId != (current.oidcClientId ?? '')) patch['oidcClientId'] = clientId;
    if (form.oidcClientSecret.trim().isNotEmpty) {
      patch['oidcClientSecret'] = form.oidcClientSecret;
    }
    if (form.oidcProvisioningEnabled != current.oidcProvisioningEnabled) {
      patch['oidcProvisioningEnabled'] = form.oidcProvisioningEnabled;
    }
  }
  patch['version'] = current.version;
  return patch;
}

/// Nothing but the version: there is nothing to save, so nothing is sent.
bool settingsPatchIsEmpty(Map<String, Object?> patch) =>
    patch.length == 1 && patch.containsKey('version');

/// The settings two copies can disagree about, by name.
enum SettingsField {
  registrationOpen,
  passwordLoginEnabled,
  oidcEnabled,
  oidcIssuerUri,
  oidcClientId,
  oidcClientSecret,
  oidcProvisioningEnabled,
}

/// What somebody else changed, as **field names only**.
///
/// Deliberately not their values: one of these is the client secret, and a conflict notice that
/// rendered values would be the one place in the console that put a secret on screen. (For the
/// secret even the API says only whether one is set, which is what is compared.)
List<SettingsField> differingSettings(InstanceSettings opened, InstanceSettings latest) => [
  if (opened.registrationOpen != latest.registrationOpen) SettingsField.registrationOpen,
  if (opened.passwordLoginEnabled != latest.passwordLoginEnabled)
    SettingsField.passwordLoginEnabled,
  if (opened.oidcEnabled != latest.oidcEnabled) SettingsField.oidcEnabled,
  if (opened.oidcIssuerUri != latest.oidcIssuerUri) SettingsField.oidcIssuerUri,
  if (opened.oidcClientId != latest.oidcClientId) SettingsField.oidcClientId,
  if (opened.oidcClientSecretSet != latest.oidcClientSecretSet) SettingsField.oidcClientSecret,
  if (opened.oidcProvisioningEnabled != latest.oidcProvisioningEnabled)
    SettingsField.oidcProvisioningEnabled,
];

/// Field lengths, as the web's form and `AdminRequests` bound them.
abstract final class AdminLimits {
  static const int issuer = 500;
  static const int clientId = 200;
  static const int secret = 500;
}

/// An absolute `https://` URL — or `http://` for a host on the same machine (the web's
/// `issuerUrl`). The scheme matters: the client secret is sent to the provider's token endpoint,
/// and over `http://` it is sent to anybody on the path. Loopback is allowed because a self-hoster
/// running their identity provider in the next container is the ordinary case, not an attack.
/// Empty is not this rule's to refuse.
bool isIssuerUrl(String value) {
  final text = value.trim();
  if (text.isEmpty) return true;
  final uri = Uri.tryParse(text);
  if (uri == null || !uri.isAbsolute || uri.host.isEmpty) return false;
  if (uri.scheme == 'https') return true;
  const local = {'localhost', '127.0.0.1', '::1', '[::1]'};
  return uri.scheme == 'http' && local.contains(uri.host);
}
