import 'package:budget_owl/core/errors/app_error.dart';
import 'package:budget_owl/core/json/json.dart';
import 'package:budget_owl/core/session/session.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// `GET /api/setup/status`: whether the server has anybody on it yet, and which sign-in it allows.
final class SetupStatus {
  const SetupStatus({required this.setupComplete, required this.passwordLoginEnabled});

  factory SetupStatus.fromJson(JsonMap json) => SetupStatus(
    setupComplete: json.boolean('setupComplete'),
    passwordLoginEnabled: json.booleanOr('passwordLoginEnabled', fallback: true),
  );

  final bool setupComplete;
  final bool passwordLoginEnabled;
}

/// Read fresh each time a screen asks — never cached, never with a token (it is public).
final setupStatusProvider = FutureProvider.autoDispose<SetupStatus>((ref) async {
  ref.watch(sessionProvider.select((s) => s is SessionSignedOut ? s.server : null));
  final api = ref.watch(apiClientProvider);
  if (api == null) throw const AppError.unexpected();
  try {
    return SetupStatus.fromJson(decodeObject(await api.getText('/api/setup/status', public: true)));
  } on UnexpectedJson {
    throw const AppError.unexpected();
  }
});
