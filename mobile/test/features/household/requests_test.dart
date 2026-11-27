import 'package:budget_owl/core/server/server_address.dart';
import 'package:budget_owl/core/session/current_user.dart';
import 'package:budget_owl/features/admin/domain/admin.dart';
import 'package:budget_owl/features/admin/domain/settings_patch.dart';
import 'package:budget_owl/features/household/domain/currencies.dart';
import 'package:budget_owl/features/household/domain/household.dart';
import 'package:budget_owl/features/household/domain/requests.dart';
import 'package:budget_owl/features/preferences/domain/locales.dart';
import 'package:flutter_test/flutter_test.dart';

/// Every body the Household, Preferences and Admin screens send, exactly as the web builds it, and
/// the pure rules beside them: the invitation link, the issuer, the conflict notice.
void main() {
  /// Made up, and named so: the repository's secret guard is right to refuse anything else.
  const typedSecret = ' placeholder value with spaces ';

  group('household bodies', () {
    test('a rename sends both fields, trimmed, the currency upper-cased', () {
      expect(updateHouseholdBody(name: '  Rivera Family ', baseCurrency: 'eur'), {
        'name': 'Rivera Family',
        'baseCurrency': 'EUR',
      });
    });

    test('a role change sends the role and nothing else — never a household or a user', () {
      expect(changeRoleBody(Role.owner), {'role': 'OWNER'});
      expect(changeRoleBody(Role.viewer), {'role': 'VIEWER'});
    });

    test('an invitation sends the address, trimmed, and the role', () {
      expect(createInvitationBody(email: ' edsger@example.com ', role: Role.member), {
        'email': 'edsger@example.com',
        'role': 'MEMBER',
      });
    });

    test('preferences send both fields, always; "follow the default" is null', () {
      expect(ownPreferencesBody(displayCurrency: 'eur', locale: 'de-DE'), {
        'displayCurrency': 'EUR',
        'locale': 'de-DE',
      });
      expect(ownPreferencesBody(displayCurrency: '', locale: '  '), {
        'displayCurrency': null,
        'locale': null,
      });
      expect(ownPreferencesBody(displayCurrency: null, locale: null), {
        'displayCurrency': null,
        'locale': null,
      });
    });
  });

  group('the invitation link', () {
    final server = ServerAddress.tryRestore(
      'https://owl.example.test:8443',
      allowLocalCleartext: false,
    )!;

    test('is this server, followed by the relative path the API returned', () {
      expect(
        buildInviteLink('/join/abcdefghijklmnop', server),
        'https://owl.example.test:8443/join/abcdefghijklmnop',
      );
    });

    test('is refused when the path would take it to another host', () {
      for (final path in [
        '//evil.example.test/join/abcdefghijklmnop',
        'https://evil.example.test/join/abcdefghijklmnop',
        'join/abcdefghijklmnop',
        '/join/abcdefghijklmnop?next=https://evil.example.test',
        '/join/abcdefghijklmnop#x',
        '/other/abcdefghijklmnop',
        '/join/',
        '',
      ]) {
        expect(buildInviteLink(path, server), isNull, reason: path);
      }
    });

    test('never prints its token', () {
      final invitation = Invitation(
        id: 'inv-1',
        email: 'edsger@example.com',
        role: Role.viewer,
        acceptPath: '/join/placeholder-token-value',
        expiresAt: DateTime.utc(2026, 8, 27),
      );
      expect('$invitation', isNot(contains('placeholder-token-value')));
    });
  });

  group('the settings patch', () {
    const v0 = InstanceSettings(
      registrationOpen: false,
      passwordLoginEnabled: true,
      passwordLoginLocked: true,
      oidcEnabled: false,
      oidcClientSecretSet: false,
      oidcProvisioningEnabled: false,
      version: 0,
    );
    const providerOn = InstanceSettings(
      registrationOpen: false,
      passwordLoginEnabled: true,
      passwordLoginLocked: true,
      oidcEnabled: true,
      oidcIssuerUri: 'https://id.example.org',
      oidcClientId: 'budget-owl',
      oidcClientSecretSet: true,
      oidcProvisioningEnabled: false,
      version: 7,
    );

    SigningInForm form(
      InstanceSettings from, {
      bool? registrationOpen,
      bool? oidcEnabled,
      String? issuer,
      String? clientId,
      String typed = '',
    }) {
      final base = SigningInForm.of(from);
      return SigningInForm(
        registrationOpen: registrationOpen ?? base.registrationOpen,
        passwordLoginEnabled: base.passwordLoginEnabled,
        oidcEnabled: oidcEnabled ?? base.oidcEnabled,
        oidcIssuerUri: issuer ?? base.oidcIssuerUri,
        oidcClientId: clientId ?? base.oidcClientId,
        oidcClientSecret: typed,
        oidcProvisioningEnabled: base.oidcProvisioningEnabled,
      );
    }

    test('an untouched form is the version alone, and nothing is sent', () {
      final patch = buildSettingsPatch(v0, form(v0));
      expect(patch, {'version': 0});
      expect(settingsPatchIsEmpty(patch), isTrue);
      expect(SigningInForm.of(providerOn).oidcClientSecret, isEmpty);
    });

    test('sends only what changed, with the version that was read', () {
      expect(buildSettingsPatch(v0, form(v0, registrationOpen: true)), {
        'registrationOpen': true,
        'version': 0,
      });
    });

    test('a provider: issuer and client id trimmed, the secret exactly as typed', () {
      expect(
        buildSettingsPatch(
          v0,
          form(
            v0,
            oidcEnabled: true,
            issuer: ' https://id.example.org ',
            clientId: ' budget-owl ',
            typed: typedSecret,
          ),
        ),
        {
          'oidcEnabled': true,
          'oidcIssuerUri': 'https://id.example.org',
          'oidcClientId': 'budget-owl',
          'oidcClientSecret': typedSecret,
          'version': 0,
        },
      );
    });

    test('an empty or blank secret field is not mentioned: blank would clear the stored one', () {
      for (final typed in ['', '   ']) {
        final patch = buildSettingsPatch(providerOn, form(providerOn, typed: typed));
        expect(patch.containsKey('oidcClientSecret'), isFalse, reason: '"$typed"');
        expect(patch, {'version': 7});
      }
    });

    test('turning the provider off says only that', () {
      expect(
        buildSettingsPatch(
          providerOn,
          form(
            providerOn,
            oidcEnabled: false,
            issuer: 'https://other.example.org',
            typed: typedSecret,
          ),
        ),
        {'oidcEnabled': false, 'version': 7},
      );
    });

    test('a form holding a secret does not print it', () {
      expect('${form(v0, typed: typedSecret)}', isNot(contains('placeholder')));
    });

    test('a conflict names the fields that moved, never their values', () {
      expect(differingSettings(v0, providerOn), [
        SettingsField.oidcEnabled,
        SettingsField.oidcIssuerUri,
        SettingsField.oidcClientId,
        SettingsField.oidcClientSecret,
      ]);
      expect(differingSettings(v0, v0), isEmpty);
    });

    test('the password switch is offered unless the lockout applies, and always when off', () {
      expect(v0.offerPasswordSwitch, isFalse);
      const unlocked = InstanceSettings(
        registrationOpen: false,
        passwordLoginEnabled: true,
        passwordLoginLocked: false,
        oidcEnabled: true,
        oidcClientSecretSet: true,
        oidcProvisioningEnabled: false,
        version: 1,
      );
      expect(unlocked.offerPasswordSwitch, isTrue);
      const offAndLocked = InstanceSettings(
        registrationOpen: false,
        passwordLoginEnabled: false,
        passwordLoginLocked: true,
        oidcEnabled: true,
        oidcClientSecretSet: true,
        oidcProvisioningEnabled: false,
        version: 1,
      );
      expect(offAndLocked.offerPasswordSwitch, isTrue);
    });
  });

  group('the issuer', () {
    test('is https, or http on this machine only', () {
      for (final ok in [
        'https://id.example.org',
        'https://id.example.org/realms/home',
        'http://localhost:8080',
        'http://127.0.0.1:9000/issuer',
        'http://[::1]:8080',
        '',
      ]) {
        expect(isIssuerUrl(ok), isTrue, reason: ok);
      }
      for (final bad in [
        'http://id.example.org',
        'http://192.168.1.20',
        'ftp://id.example.org',
        'id.example.org',
        'https://',
        'javascript:alert(1)',
      ]) {
        expect(isIssuerUrl(bad), isFalse, reason: bad);
      }
    });
  });

  group('choices', () {
    test('a saved currency or locale outside the list is kept, first', () {
      expect(currencyChoices('XBT').first, 'XBT');
      expect(currencyChoices('EUR'), currencyCodes);
      expect(currencyChoices(null), currencyCodes);
      expect(localeChoices('gsw-CH').first, 'gsw-CH');
      expect(localeChoices('de-DE'), localeTags);
    });

    test('currencies are labelled with their symbol where intl has one', () {
      expect(currencyLabel('EUR', 'en'), 'EUR · €');
      expect(currencyLabel('USD', 'en'), r'USD · $');
      expect(currencyCodes.toSet().length, currencyCodes.length);
      expect(currencyCodes.every((c) => RegExp(r'^[A-Z]{3}$').hasMatch(c)), isTrue);
    });

    test('every locale the web offers is offered, named in its own language', () {
      expect(localeTags.length, 21);
      expect(localeTags.every(localeEndonyms.containsKey), isTrue);
      expect(localeLabel('de-DE'), 'Deutsch (Deutschland) · de-DE');
      expect(localeLabel('gsw-CH'), 'gsw-CH');
    });
  });
}
