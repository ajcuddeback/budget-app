import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// The platform half of the controls table (mobile-app.md, Threat model): what the Android and iOS
/// projects promise, read from the files a build actually uses.
void main() {
  const main = 'android/app/src/main';
  const debug = 'android/app/src/debug';

  group('Android', () {
    final manifest = _xml('$main/AndroidManifest.xml');

    test('nothing is backed up or carried to another phone', () {
      expect(manifest, contains('android:allowBackup="false"'));
      expect(manifest, contains('android:dataExtractionRules="@xml/data_extraction_rules"'));
      expect(manifest, contains('android:fullBackupContent="@xml/backup_rules"'));

      final rules = _xml('$main/res/xml/data_extraction_rules.xml');
      for (final section in ['cloud-backup', 'device-transfer']) {
        final body = RegExp(
          '<$section>(.*?)</$section>',
          dotAll: true,
        ).firstMatch(rules)!.group(1)!;
        for (final domain in ['root', 'file', 'database', 'sharedpref', 'external']) {
          expect(
            body,
            contains('<exclude domain="$domain" path="." />'),
            reason: '$section $domain',
          );
        }
        expect(body, isNot(contains('<include')), reason: section);
      }
      expect(_xml('$main/res/xml/backup_rules.xml'), isNot(contains('<include')));
    });

    test('a release build allows no cleartext to any host, and trusts system and user CAs', () {
      expect(manifest, contains('android:networkSecurityConfig="@xml/network_security_config"'));
      expect(manifest, contains('android:usesCleartextTraffic="false"'));
      final config = _xml('$main/res/xml/network_security_config.xml');
      expect(config, contains('<base-config cleartextTrafficPermitted="false">'));
      expect(config, isNot(contains('cleartextTrafficPermitted="true"')));
      expect(config, isNot(contains('<domain-config')));
      expect(config, isNot(contains('<pin-set')));
      expect(config, isNot(contains('overridePins')));
      expect(config, contains('<certificates src="system" />'));
      expect(config, contains('<certificates src="user" />'));
    });

    test('a debug build adds cleartext for the three development hosts and nothing else', () {
      final config = _xml('$debug/res/xml/network_security_config.xml');
      expect(config, contains('<base-config cleartextTrafficPermitted="false">'));
      final domains = RegExp('<domain[^>]*>([^<]+)</domain>')
          .allMatches(config)
          .map((m) => m.group(1))
          .toList();
      expect(domains, ['localhost', '127.0.0.1', '10.0.2.2']);
      expect(config, isNot(contains('includeSubdomains="true"')));
      expect(
        Directory('android/app/src/profile/res').existsSync(),
        isFalse,
        reason: 'a profile build takes the release policy',
      );
    });

    test('Android 13+ takes no recents screenshot of the app (audit L6)', () {
      final activity = File('android/app/src/main/kotlin/app/budgetowl/budget_owl/MainActivity.kt')
          .readAsStringSync()
          .replaceAll(RegExp('//.*'), '');
      expect(
        activity,
        matches(
          RegExp(
            r'SDK_INT\s*>=\s*Build\.VERSION_CODES\.TIRAMISU\s*\)\s*\{\s*'
            r'setRecentsScreenshotEnabled\(false\)',
          ),
        ),
      );
      expect(activity, isNot(contains('FLAG_SECURE')), reason: "the user's own screenshots work");
    });

    test(
      'AND1: the trust channel hands Dart exactly the user CAs the config trusts, read-only',
      () {
        // Dart trusts what MainActivity returns only because the app's own network security config
        // already trusts user CAs, in release and debug alike.
        for (final config in [
          '$main/res/xml/network_security_config.xml',
          '$debug/res/xml/network_security_config.xml',
        ]) {
          expect(_xml(config), contains('<certificates src="user" />'), reason: config);
        }
        final activity = File('$main/kotlin/app/budgetowl/budget_owl/MainActivity.kt')
            .readAsStringSync()
            .replaceAll(RegExp('//.*'), '')
            .replaceAll(RegExp(r'/\*.*?\*/', dotAll: true), '');
        final dart = File('lib/core/http/user_trust.dart').readAsStringSync();
        expect(activity, contains('KeyStore.getInstance("AndroidCAStore")'));
        expect(activity, contains('USER_ALIAS_PREFIX = "user:"'));
        expect(activity, contains('.filter { it.startsWith(USER_ALIAS_PREFIX) }'));
        expect(activity, contains('TRUST_CHANNEL = "app.budgetowl/trust"'));
        expect(dart, contains("MethodChannel('app.budgetowl/trust')"), reason: 'the same channel');
        expect(activity, contains('USER_CA_CERTIFICATES = "userCaCertificates"'));
        expect(dart, contains("invokeListMethod<Object?>('userCaCertificates')"));
        for (final write in ['setCertificateEntry', 'setEntry', 'deleteEntry', 'store(']) {
          expect(activity, isNot(contains(write)), reason: 'the store is only read');
        }
      },
    );

    test(
      'a copied credential is marked sensitive on Android 13+, through the same channel (L2)',
      () {
        final activity = File('$main/kotlin/app/budgetowl/budget_owl/MainActivity.kt')
            .readAsStringSync()
            .replaceAll(RegExp('//.*'), '')
            .replaceAll(RegExp(r'/\*.*?\*/', dotAll: true), '');
        final dart = File('lib/core/platform/sensitive_clipboard.dart').readAsStringSync();
        expect(activity, contains('CLIPBOARD_CHANNEL = "app.budgetowl/clipboard"'));
        expect(
          dart,
          contains("MethodChannel('app.budgetowl/clipboard')"),
          reason: 'the same channel',
        );
        expect(activity, contains('SET_SENSITIVE = "setSensitive"'));
        expect(dart, contains("invokeMethod<int>('setSensitive', {'text': text})"));
        expect(activity, contains('CLEAR_IF_UNCHANGED = "clearIfUnchanged"'));
        expect(dart, contains("invokeMethod<String>('clearIfUnchanged', {"));
        // U3: recognised by the clip's timestamp, from its description — never its content on
        // Android 8+, where reading it shows "pasted from your clipboard" (12+).
        expect(
          activity,
          matches(
            RegExp(
              r'VERSION_CODES\.O\) \{\s*val now = clipboard\.primaryClipDescription\s*'
              r'record\.timestamp != null && now != null && now\.timestamp == record\.timestamp',
            ),
          ),
        );
        expect(
          RegExp(r'\.primaryClip\b(?!Description)').allMatches(activity),
          hasLength(1),
          reason: 'the clip itself is read once: below Android 8, for its digest',
        );
        expect(activity, contains('if (!hasWindowFocus()) {'), reason: 'Android 10+: deferred');
        expect(activity, contains('override fun onWindowFocusChanged(hasFocus: Boolean)'));
        expect(
          activity,
          matches(
            RegExp(
              r'SDK_INT >= Build\.VERSION_CODES\.TIRAMISU\) \{\s*clip\.description\.extras =\s*'
              r'PersistableBundle\(\)\.apply \{\s*'
              r'putBoolean\(ClipDescription\.EXTRA_IS_SENSITIVE, true\)',
            ),
          ),
        );
        expect(
          activity,
          contains('ClipData.newPlainText("", text'),
          reason: 'no label describes it',
        );
      },
    );

    test('the app asks for the network and for nothing else', () {
      final permissions = RegExp('<uses-permission android:name="([^"]+)"')
          .allMatches(manifest)
          .map((m) => m.group(1))
          .toList();
      expect(permissions, ['android.permission.INTERNET']);
    });
  });

  group('iOS', () {
    test('a copied credential is local-only, expires, and is taken back unread (U3)', () {
      final delegate = File('ios/Runner/AppDelegate.swift')
          .readAsStringSync()
          .replaceAll(RegExp('//.*'), '');
      final dart = File('lib/core/platform/sensitive_clipboard.dart').readAsStringSync();
      expect(delegate, contains('static let clipboardChannel = "app.budgetowl/clipboard"'));
      expect(delegate, contains('static let setSensitive = "setSensitive"'));
      expect(delegate, contains('static let clearIfUnchanged = "clearIfUnchanged"'));
      expect(delegate, contains('.localOnly: true,'));
      expect(delegate, contains('.expirationDate: Date().addingTimeInterval(AppDelegate.expiry),'));
      expect(delegate, contains('static let expiry: TimeInterval = 10 * 60'));
      expect(dart, contains('iosExpiry = Duration(minutes: 10)'), reason: 'the same ten minutes');
      expect(delegate, contains('guard pasteboard.changeCount == record.changeCount else {'));
      for (final read in [
        'pasteboard.string',
        'pasteboard.strings',
        'hasStrings',
        'pasteboard.url',
      ]) {
        expect(delegate, isNot(contains(read)), reason: 'nothing is read back');
      }
      expect(
        RegExp(r'pasteboard\.items\b').allMatches(delegate).length,
        1,
        reason: 'items only written, to empty it',
      );
      expect(delegate, contains('pasteboard.items = []'));
      // U2: where the picker's copies are.
      expect(delegate, contains('static let filesChannel = "app.budgetowl/files"'));
      expect(
        File('lib/core/storage/temp_files.dart').readAsStringSync(),
        contains("MethodChannel('app.budgetowl/files')"),
      );
      expect(delegate, contains(r'appendingPathComponent("\(bundle)-Inbox", isDirectory: true)'));
      expect(delegate, contains('URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)'));
    });

    test('App Transport Security keeps its defaults: no key, so no exception', () {
      final plist = _xml('ios/Runner/Info.plist');
      expect(plist, isNot(contains('NSAppTransportSecurity')));
      expect(plist, isNot(contains('NSAllowsArbitraryLoads')));
      expect(plist, isNot(contains('NSExceptionDomains')));
    });

    test('the Podfile and the Xcode project agree on the minimum iOS', () {
      final podfile = File('ios/Podfile').readAsStringSync();
      final platform = RegExp("^platform :ios, '([0-9.]+)'", multiLine: true).firstMatch(podfile);
      expect(platform, isNotNull, reason: 'the platform line is not commented out');
      final project = File('ios/Runner.xcodeproj/project.pbxproj').readAsStringSync();
      final targets = RegExp('IPHONEOS_DEPLOYMENT_TARGET = ([0-9.]+);')
          .allMatches(project)
          .map((m) => m.group(1))
          .toSet();
      expect(targets, {platform!.group(1)});
    });
  });
}

/// The file without its XML comments, so a rule can be explained in the file it governs.
String _xml(String path) =>
    File(path).readAsStringSync().replaceAll(RegExp('<!--.*?-->', dotAll: true), '');
